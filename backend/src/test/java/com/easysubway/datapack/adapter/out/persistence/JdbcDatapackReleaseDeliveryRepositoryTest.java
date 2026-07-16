package com.easysubway.datapack.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.datapack.domain.DatapackReleaseDelivery;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@DisplayName("JdbcDatapackReleaseDeliveryRepository")
class JdbcDatapackReleaseDeliveryRepositoryTest {

	private static final LocalDateTime T0 = LocalDateTime.parse("2026-07-16T00:00:00");
	private static final String SHA = "a".repeat(64);

	@Autowired
	private JdbcDatapackReleaseDeliveryRepository repository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM datapack_release_deliveries");
	}

	@Test
	@DisplayName("동일 composite identity는 한 row로 멱등 저장한다")
	void upsertsSameDelivery() {
		var first = repository.upsertSameDelivery(pending(SHA));
		var second = repository.upsertSameDelivery(pending(SHA));

		assertThat(second.idempotencyKey()).isEqualTo(first.idempotencyKey());
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM datapack_release_deliveries", Integer.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("manifest SHA 대소문자 차이는 같은 release identity로 정규화한다")
	void normalizesManifestShaBeforeBuildingIdempotencyKey() {
		var delivery = pending("A".repeat(64));

		assertThat(delivery.manifestSha256()).isEqualTo(SHA);
		assertThat(delivery.idempotencyKey()).endsWith(":" + SHA);
	}

	@Test
	@DisplayName("같은 request/sequence의 다른 manifest hash는 unique constraint로 거부한다")
	void rejectsDifferentHashForSameSequence() {
		repository.upsertSameDelivery(pending(SHA));

		assertThatThrownBy(() -> repository.upsertSameDelivery(pending("b".repeat(64))))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("같은 idempotency key의 다른 callback payload와 signature는 거부한다")
	void rejectsDifferentCallbackPayloadForSameIdentity() {
		repository.upsertSameDelivery(pending(SHA, "c".repeat(64), "d".repeat(64)));

		assertThatThrownBy(() -> repository.upsertSameDelivery(
			pending(SHA, "e".repeat(64), "f".repeat(64))))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("재조정 상태는 같은 release identity의 새 callback 증적으로 갱신한다")
	void refreshesCallbackEvidenceWhileReconciliationIsRequired() {
		var original = repository.upsertSameDelivery(
			pending(SHA, "c".repeat(64), "d".repeat(64)));
		repository.mark(original.idempotencyKey(), DatapackReleaseDelivery.State.RECONCILIATION_REQUIRED,
			1, T0.plusMinutes(5), "BLOCKED", "BINDING_UNAVAILABLE", T0);

		var refreshed = repository.upsertSameDelivery(
			pending(SHA, "e".repeat(64), "f".repeat(64)));

		assertThat(refreshed.payloadSha256()).isEqualTo("e".repeat(64));
		assertThat(refreshed.signatureSha256()).isEqualTo("f".repeat(64));
		assertThat(refreshed.state()).isEqualTo(DatapackReleaseDelivery.State.RECONCILIATION_REQUIRED);
	}

	@Test
	@DisplayName("동시 claim은 delivery 한 건을 한 worker에게만 준다")
	void claimsDueOnce() throws Exception {
		repository.upsertSameDelivery(pending(SHA));
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var a = executor.submit(() -> { start.await(); return repository.claimDue(T0, "worker-a", 100); });
			var b = executor.submit(() -> { start.await(); return repository.claimDue(T0, "worker-b", 100); });
			start.countDown();
			assertThat(a.get(10, TimeUnit.SECONDS).size()
				+ b.get(10, TimeUnit.SECONDS).size()).isEqualTo(1);
		}
	}

	@Test
	@DisplayName("만료된 worker claim은 재획득하고 이전 worker의 완료 갱신은 거부한다")
	void reclaimsExpiredLease() {
		repository.upsertSameDelivery(pending(SHA));
		assertThat(repository.claimDue(T0, "worker-a", 100)).hasSize(1);
		assertThat(repository.claimDue(T0.plusMinutes(4), "worker-b", 100)).isEmpty();
		assertThat(repository.claimDue(T0.plusMinutes(5), "worker-b", 100)).hasSize(1);
		assertThatThrownBy(() -> repository.markClaimed(
			pending(SHA).idempotencyKey(), "worker-a", DatapackReleaseDelivery.State.DELIVERED,
			1, null, "RECONCILED", null, T0.plusMinutes(5)))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("select 뒤 backoff가 예약되면 지연된 claim 갱신을 거부한다")
	void refusesAStaleClaimAfterBackoffWasScheduled() {
		var delivery = repository.upsertSameDelivery(pending(SHA));
		jdbcTemplate.update("""
			UPDATE datapack_release_deliveries
			SET state='RETRY_SCHEDULED', next_attempt_at=?
			WHERE idempotency_key=?
			""", T0.plusMinutes(1), delivery.idempotencyKey());

		int changed = repository.claimKeyIfDue(
			T0, "stale-worker", T0.minusMinutes(5), delivery.idempotencyKey());

		assertThat(changed).isZero();
		assertThat(repository.findByIdempotencyKey(delivery.idempotencyKey()))
			.get().extracting(DatapackReleaseDelivery::claimOwner).isNull();
	}

	private static DatapackReleaseDelivery pending(String manifestSha256) {
		return pending(manifestSha256, "c".repeat(64), "d".repeat(64));
	}

	private static DatapackReleaseDelivery pending(
		String manifestSha256,
		String payloadSha256,
		String signatureSha256
	) {
		return DatapackReleaseDelivery.pending(
			"request-2057", 42, manifestSha256, "production", "candidate-2057",
			payloadSha256, signatureSha256, T0);
	}
}
