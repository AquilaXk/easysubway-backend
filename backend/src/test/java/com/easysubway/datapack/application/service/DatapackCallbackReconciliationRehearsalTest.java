package com.easysubway.datapack.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseDeliveryRepository;
import com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort;
import com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort.CatalogIdentity;
import com.easysubway.datapack.domain.DatapackReleaseDelivery;
import com.easysubway.datapack.domain.DatapackReleaseDelivery.State;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "easysubway.datapack.reconciliation-interval-ms=3600000")
@Import(DatapackCallbackReconciliationRehearsalTest.RehearsalConfiguration.class)
@DisplayName("Datapack callback/reconciliation production-like isolated rehearsal")
class DatapackCallbackReconciliationRehearsalTest {

	private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");
	private static final LocalDateTime T0 = LocalDateTime.parse("2026-07-16T00:00:00");
	private static final String WORKFLOW_RUN_URL =
		"https://github.com/AquilaXk/easysubway/actions/runs/2057";

	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private JdbcDatapackReleaseDeliveryRepository deliveryRepository;
	@Autowired
	private DatapackReleaseReconciliationService reconciliationService;
	@Autowired
	private MutableRehearsalClock clock;
	@Autowired
	private RehearsalCatalog catalog;
	@Autowired
	private ObjectMapper objectMapper;

	@Test
	@DisplayName("callback backend unavailable publish가 10분에 수렴하고 catalog 장애는 70분에 dead-letter된다")
	void convergesAtTenMinutesAndDeadLettersAtSeventyMinutes() throws Exception {
		var identity = rehearsalIdentity();
		var outage = callbackOutageArtifact(identity);
		clean(identity.releaseRequestId());
		insertPublishedCandidate(identity);
		insertDispatchedRequest(identity);
		catalog.available(new CatalogIdentity(
			identity.releaseSequence(), identity.manifestSha256(), "production",
			identity.releaseRequestId(), true, "b".repeat(64), false));

		clock.at(T0.plusMinutes(9));
		reconciliationService.reconcileDue();
		assertThat(deliveryRepository.findByIdempotencyKey(identity.idempotencyKey())).isEmpty();
		assertThat(requestStatus(identity.releaseRequestId())).isEqualTo("DISPATCHED");

		clock.at(T0.plusMinutes(10));
		reconciliationService.reconcileDue();
		var converged = deliveryRepository.findByIdempotencyKey(identity.idempotencyKey())
			.orElseThrow();
		assertThat(converged.state()).isEqualTo(State.DELIVERED);
		assertThat(requestStatus(identity.releaseRequestId())).isEqualTo("PUBLISHED");
		assertThat(converged.releaseSequence()).isEqualTo(identity.releaseSequence());
		assertThat(converged.manifestSha256()).isEqualTo(identity.manifestSha256());

		String terminalRequestId = identity.releaseRequestId() + "-terminal";
		var terminal = deliveryRepository.upsertSameDelivery(DatapackReleaseDelivery.pending(
			terminalRequestId, identity.releaseSequence(), identity.manifestSha256(),
			"production", "candidate-2057-terminal", null, "c".repeat(64), T0));
		deliveryRepository.mark(terminal.idempotencyKey(), State.RECONCILIATION_REQUIRED,
			3, T0.plusMinutes(69), "UNAVAILABLE", "CATALOG_UNAVAILABLE", T0);
		catalog.unavailable();

		clock.at(T0.plusMinutes(69));
		reconciliationService.reconcileDue();
		var scheduledAtBoundary = deliveryRepository.findByIdempotencyKey(terminal.idempotencyKey())
			.orElseThrow();
		assertThat(scheduledAtBoundary.state()).isEqualTo(State.RETRY_SCHEDULED);
		assertThat(scheduledAtBoundary.nextAttemptAt()).isEqualTo(T0.plusMinutes(70));

		clock.at(T0.plusMinutes(70));
		reconciliationService.reconcileDue();
		var deadLetter = deliveryRepository.findByIdempotencyKey(terminal.idempotencyKey())
			.orElseThrow();
		assertThat(deadLetter.state()).isEqualTo(State.DEAD_LETTER);
		assertThat(deadLetter.httpClass()).isEqualTo("UNAVAILABLE");
		assertThat(deadLetter.sanitizedDetail()).isEqualTo("CATALOG_UNAVAILABLE");

		writeRawRehearsal(identity, converged, deadLetter, outage);
	}

	private void insertPublishedCandidate(RehearsalIdentity identity) {
		jdbcTemplate.update("""
			INSERT INTO datapack_candidates (
			 id, scope_id, artifact_kind, version, source_snapshot_set_hash,
			 override_set_hash, build_spec_sha256, source_inventory_sha256,
			 sqlite_sha256, gzip_sha256, manifest_sha256, coverage_status,
			 validator_status, route_regression_status, android_evidence_status,
			 approval_status, created_at)
			VALUES ('candidate-2057-rehearsal', 'production-like-isolated', 'FULL', '2057',
			 ?, ?, ?, ?, ?, ?, ?, 'PASS', 'PASS', 'PASS', 'PASS', 'APPROVED', ?)
			""", "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64),
			"5".repeat(64), "6".repeat(64), identity.manifestSha256(), T0);
		jdbcTemplate.update("""
			INSERT INTO datapack_release_evidence_bundles (
			 id, candidate_id, evidence_bundle_sha256, workflow_run_url,
			 validator_status, route_regression_status, manifest_signature_status,
			 android_evidence_status, created_at)
			VALUES ('evidence-2057-rehearsal', 'candidate-2057-rehearsal', ?, ?,
			 'PASS', 'PASS', 'PASS', 'PASS', ?)
			""", "c".repeat(64), WORKFLOW_RUN_URL, T0);
	}

	private JsonNode callbackOutageArtifact(
		RehearsalIdentity identity
	) throws Exception {
		String artifactPath = System.getenv("EASYSUBWAY_CALLBACK_OUTAGE_ARTIFACT");
		assumeTrue(artifactPath != null && Files.isRegularFile(Path.of(artifactPath)),
			"production sender outage artifact is required for evidence rehearsal");
		var artifact = objectMapper.readTree(Path.of(artifactPath).toFile());
		assertThat(artifact.path("schemaVersion").asInt()).isEqualTo(1);
		assertThat(artifact.path("artifactKind").asText())
			.isEqualTo("callback-backend-unavailable-rehearsal");
		assertThat(artifact.at("/deliveryIdentity/releaseRequestId").asText())
			.isEqualTo(identity.releaseRequestId());
		assertThat(artifact.at("/deliveryIdentity/releaseSequence").asLong())
			.isEqualTo(identity.releaseSequence());
		assertThat(artifact.at("/deliveryIdentity/manifestSha256").asText())
			.isEqualTo(identity.manifestSha256());
		assertThat(artifact.at("/candidate/noChange").asBoolean()).isFalse();
		assertThat(artifact.at("/callbackDelivery/state").asText())
			.isEqualTo("RECONCILIATION_REQUIRED");
		assertThat(artifact.at("/callbackDelivery/attempts").size()).isEqualTo(4);
		assertThat(artifact.at("/callbackDelivery/attempts").findValuesAsText("httpClass"))
			.containsExactly("5XX", "5XX", "5XX", "5XX");
		assertThat(artifact.path("virtualRetryDelaysSeconds").size()).isEqualTo(3);
		assertThat(artifact.at("/virtualRetryDelaysSeconds/0").asInt()).isEqualTo(60);
		assertThat(artifact.at("/virtualRetryDelaysSeconds/1").asInt()).isEqualTo(480);
		assertThat(artifact.at("/virtualRetryDelaysSeconds/2").asInt()).isEqualTo(3600);
		return artifact;
	}

	private void insertDispatchedRequest(RehearsalIdentity identity) {
		jdbcTemplate.update("""
			INSERT INTO datapack_release_request (
			 approval_id, candidate_id, scope_id, target_channel, build_spec_sha256,
			 source_snapshot_set_hash, approved_ledger_hash, requested_by, approved_by,
			 status, dispatch_idempotency_key, workflow_run_url,
			 created_at, approved_at, updated_at)
			VALUES (?, 'candidate-2057-rehearsal', 'production-like-isolated', 'production', ?, ?, ?,
			 'rehearsal-producer', 'rehearsal-approver', 'DISPATCHED', ?, ?, ?, ?, ?)
			""", identity.releaseRequestId(), "d".repeat(64), "e".repeat(64), "f".repeat(64),
			"dispatch-" + identity.idempotencyKeySha256(), WORKFLOW_RUN_URL,
			T0, T0, T0);
	}

	private String requestStatus(String releaseRequestId) {
		return jdbcTemplate.queryForObject(
			"SELECT status FROM datapack_release_request WHERE approval_id=?",
			String.class, releaseRequestId);
	}

	private void clean(String releaseRequestId) {
		jdbcTemplate.update("DELETE FROM datapack_release_deliveries WHERE release_request_id IN (?, ?)",
			releaseRequestId, releaseRequestId + "-terminal");
		jdbcTemplate.update("DELETE FROM datapack_release_request WHERE approval_id=?", releaseRequestId);
		jdbcTemplate.update("DELETE FROM datapack_release_evidence_bundles WHERE candidate_id=?",
			"candidate-2057-rehearsal");
		jdbcTemplate.update("DELETE FROM datapack_candidates WHERE id=?", "candidate-2057-rehearsal");
	}

	private void writeRawRehearsal(
		RehearsalIdentity identity,
		DatapackReleaseDelivery converged,
		DatapackReleaseDelivery deadLetter,
		JsonNode outage
	) throws Exception {
		Map<String, Object> report = new LinkedHashMap<>();
		report.put("schemaVersion", 1);
		report.put("environment", "production-like-isolated-h2");
		report.put("deliveryIdentity", Map.of(
			"releaseRequestId", identity.releaseRequestId(),
			"releaseSequence", identity.releaseSequence(),
			"manifestSha256", identity.manifestSha256(),
			"idempotencyKeySha256", identity.idempotencyKeySha256()));
		report.put("virtualEventTimeline", Map.of(
			"candidatePublishedAt", T0.toString() + "Z",
			"callbackBackendUnavailableAt", T0.toString() + "Z",
			"reconciliationConvergedAt", T0.plusMinutes(10).toString() + "Z",
			"terminalBoundaryAt", T0.plusMinutes(70).toString() + "Z"));
		report.put("metrics", Map.of(
			"controlPlaneConvergenceP95Ms", 10 * 60 * 1_000,
			"terminalDispositionMaxMs", 70 * 60 * 1_000));
		Map<String, Object> observations = new LinkedHashMap<>();
		observations.put("candidateNoChange", outage.at("/candidate/noChange").asBoolean());
		observations.put("callbackDelivery", outage.path("callbackDelivery"));
		observations.put("virtualRetryDelaysSeconds", outage.path("virtualRetryDelaysSeconds"));
		observations.put("convergedState", converged.state().name());
		observations.put("convergedHttpClass", converged.httpClass());
		observations.put("terminalState", deadLetter.state().name());
		observations.put("terminalHttpClass", deadLetter.httpClass());
		observations.put("terminalReason", deadLetter.sanitizedDetail());
		report.put("observations", observations);
		report.put("sensitiveMaterialStored", false);

		Path output = Path.of(System.getenv().getOrDefault(
			"EASYSUBWAY_CALLBACK_REHEARSAL_OUTPUT",
			"build/reports/datapack-callback-reconciliation/raw-rehearsal.json"));
		Files.createDirectories(output.toAbsolutePath().getParent());
		Files.writeString(output,
			objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
			StandardCharsets.UTF_8);
	}

	private static RehearsalIdentity rehearsalIdentity() throws Exception {
		String requestId = System.getenv().getOrDefault(
			"EASYSUBWAY_CALLBACK_EVIDENCE_RELEASE_REQUEST_ID", "release-request-2057-rehearsal");
		String sequenceValue = System.getenv().getOrDefault(
			"EASYSUBWAY_CALLBACK_EVIDENCE_RELEASE_SEQUENCE", "42");
		String manifestSha256 = System.getenv().getOrDefault(
			"EASYSUBWAY_CALLBACK_EVIDENCE_MANIFEST_SHA256", "a".repeat(64));
		long sequence;
		try {
			sequence = Long.parseLong(sequenceValue);
		} catch (NumberFormatException invalid) {
			throw new IllegalArgumentException("release sequence must be a positive integer", invalid);
		}
		if (requestId.isBlank() || requestId.contains(":")) {
			throw new IllegalArgumentException("release request ID must be non-blank and contain no colon");
		}
		if (sequence < 1 || !SHA256.matcher(manifestSha256).matches()) {
			throw new IllegalArgumentException("final RC release identity is invalid");
		}
		String idempotencyKey = requestId + ":" + sequence + ":" + manifestSha256;
		return new RehearsalIdentity(
			requestId, sequence, manifestSha256, idempotencyKey, sha256(idempotencyKey));
	}

	private static String sha256(String value) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
			.digest(value.getBytes(StandardCharsets.UTF_8)));
	}

	private record RehearsalIdentity(
		String releaseRequestId,
		long releaseSequence,
		String manifestSha256,
		String idempotencyKey,
		String idempotencyKeySha256
	) { }

	@TestConfiguration
	static class RehearsalConfiguration {

		@Bean
		@Primary
		MutableRehearsalClock rehearsalClock() {
			return new MutableRehearsalClock(T0);
		}

		@Bean
		@Primary
		RehearsalCatalog rehearsalCatalog() {
			return new RehearsalCatalog();
		}
	}

	static final class MutableRehearsalClock extends Clock {
		private volatile Instant instant;

		MutableRehearsalClock(LocalDateTime initial) {
			at(initial);
		}

		void at(LocalDateTime value) {
			instant = value.toInstant(ZoneOffset.UTC);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			if (!ZoneOffset.UTC.equals(zone)) {
				throw new IllegalArgumentException("rehearsal clock is UTC only");
			}
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}

	static final class RehearsalCatalog implements DatapackReleaseCatalogPort {
		private volatile CatalogIdentity identity;
		private volatile boolean available;

		void available(CatalogIdentity value) {
			identity = value;
			available = true;
		}

		void unavailable() {
			available = false;
		}

		@Override
		public CatalogIdentity fetch(String channel, long releaseSequence) {
			return required(channel);
		}

		@Override
		public CatalogIdentity fetchCurrent(String channel) {
			return required(channel);
		}

		@Override
		public Optional<CatalogIdentity> findByRequest(String channel, String releaseRequestId) {
			var current = required(channel);
			return current.releaseRequestId().equals(releaseRequestId)
				? Optional.of(current)
				: Optional.empty();
		}

		private CatalogIdentity required(String channel) {
			if (!available || identity == null) {
				throw new DatapackReleaseCatalogPort.Unavailable();
			}
			if (!identity.channel().equals(channel)) {
				throw new DatapackReleaseCatalogPort.NotFound();
			}
			return identity;
		}
	}
}
