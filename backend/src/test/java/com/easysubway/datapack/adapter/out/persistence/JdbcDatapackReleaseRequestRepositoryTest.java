package com.easysubway.datapack.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.datapack.application.port.out.DatapackReleaseRequestRepository;
import com.easysubway.datapack.domain.DatapackReleaseRequest;
import com.easysubway.datapack.domain.DatapackReleaseRequestStatus;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@DisplayName("JdbcDatapackReleaseRequestRepository")
class JdbcDatapackReleaseRequestRepositoryTest {

	private static final String SHA = "a".repeat(64);
	private static final LocalDateTime T0 = LocalDateTime.parse("2026-07-06T00:00:00");

	@Autowired
	private DatapackReleaseRequestRepository repository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM datapack_release_request");
	}

	@Test
	@DisplayName("save 후 findByApprovalId가 모든 필드를 왕복한다")
	void savesAndReads() {
		repository.save(DatapackReleaseRequest.requested(
			"appr-1", "cand-1", "scope-1", "staging", SHA, SHA, SHA, "alice", T0));

		var found = repository.findByApprovalId("appr-1").orElseThrow();
		assertThat(found.candidateId()).isEqualTo("cand-1");
		assertThat(found.status()).isEqualTo(DatapackReleaseRequestStatus.REQUESTED);
		assertThat(found.approvedBy()).isNull();
	}

	@Test
	@DisplayName("save는 approval_id 기준 upsert (승인 결과 반영)")
	void upserts() {
		repository.save(DatapackReleaseRequest.requested(
			"appr-1", "cand-1", "scope-1", "staging", SHA, SHA, SHA, "alice", T0));
		repository.save(repository.findByApprovalId("appr-1").orElseThrow().approve("bob", T0));

		var found = repository.findByApprovalId("appr-1").orElseThrow();
		assertThat(found.status()).isEqualTo(DatapackReleaseRequestStatus.APPROVED);
		assertThat(found.approvedBy()).isEqualTo("bob");
	}

	@Test
	@DisplayName("없는 approvalId는 empty")
	void emptyWhenMissing() {
		assertThat(repository.findByApprovalId("nope")).isEmpty();
	}

	@Test
	@DisplayName("reconciliation 후보는 bounded batch이며 defer 후 10분 동안 제외된다")
	void boundsAndDefersReconciliationCandidates() {
		for (int index = 0; index < 3; index++) {
			repository.save(DatapackReleaseRequest.requested(
				"appr-" + index, "cand-" + index, "scope-1", "production",
				SHA, SHA, SHA, "alice", T0).approve("bob", T0));
		}

		var due = repository.claimReconciliationDue(
			T0.plusMinutes(10), T0.plusMinutes(10), T0.plusMinutes(20), 2);
		assertThat(due).hasSize(2);

		assertThat(repository.claimReconciliationDue(
			T0.plusMinutes(10), T0.plusMinutes(10), T0.plusMinutes(20), 3))
				.extracting(DatapackReleaseRequest::approvalId)
				.containsExactly("appr-2");
	}

	@Test
	@DisplayName("DISPATCH_FAILED 이력 행도 콜백 유실 복구 후보로 임대한다")
	void claimsDispatchFailedHistoryRow() {
		// backend가 더 이상 만들지 않는 DISPATCH_FAILED 이력 행을 직접 재현한다.
		repository.save(new DatapackReleaseRequest(
			"appr-failed", "cand-1", "scope-1", "production", SHA, SHA, SHA, "alice", "bob",
			DatapackReleaseRequestStatus.DISPATCH_FAILED, "appr-failed", null, T0, T0, T0, null, null));

		assertThat(repository.claimReconciliationDue(
			T0.plusMinutes(10), T0.plusMinutes(10), T0.plusMinutes(20), 100))
				.extracting(DatapackReleaseRequest::approvalId)
				.containsExactly("appr-failed");
	}

	@Test
	@DisplayName("종결된 request는 콜백 유실 복구 후보가 아니다")
	void skipsTerminalRequests() {
		repository.save(DatapackReleaseRequest.requested(
			"appr-published", "cand-1", "scope-1", "production",
			SHA, SHA, SHA, "alice", T0).approve("bob", T0).markPublished("https://run/1", T0));

		assertThat(repository.claimReconciliationDue(
			T0.plusMinutes(10), T0.plusMinutes(10), T0.plusMinutes(20), 100)).isEmpty();
	}

	@Test
	@DisplayName("동시 reconciliation discovery는 request 한 건을 한 worker에게만 임대한다")
	void claimsReconciliationCandidateOnce() throws Exception {
		repository.save(DatapackReleaseRequest.requested(
			"appr-1", "cand-1", "scope-1", "production",
			SHA, SHA, SHA, "alice", T0).approve("bob", T0));
		var start = new CountDownLatch(1);

		try (var executor = Executors.newFixedThreadPool(2)) {
			var a = executor.submit(() -> {
				start.await();
				return repository.claimReconciliationDue(
					T0.plusMinutes(10), T0.plusMinutes(10), T0.plusMinutes(20), 100);
			});
			var b = executor.submit(() -> {
				start.await();
				return repository.claimReconciliationDue(
					T0.plusMinutes(10), T0.plusMinutes(10), T0.plusMinutes(20), 100);
			});
			start.countDown();

			assertThat(a.get(10, TimeUnit.SECONDS).size()
				+ b.get(10, TimeUnit.SECONDS).size()).isEqualTo(1);
		}
	}

	@Test
	@DisplayName("기존 release request 인덱스는 PostgreSQL write를 막지 않게 concurrently 생성한다")
	void createsReconciliationIndexConcurrently() throws Exception {
		var migration = java.nio.file.Files.readString(java.nio.file.Path.of(
			"src/main/resources/db/migration/postgresql/V60__datapack_release_request_reconciliation_index.sql"));

		assertThat(migration).contains(
			"CREATE INDEX CONCURRENTLY idx_datapack_release_request_reconciliation_due");
	}
}
