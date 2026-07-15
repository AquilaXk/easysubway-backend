package com.easysubway.datapack.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.easysubway.datapack.adapter.out.persistence.JdbcDataSourceSnapshotRepository;
import com.easysubway.datapack.application.service.DatapackSourceSnapshotCommandService.SourceSnapshotCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("데이터팩 source snapshot lineage command")
class DatapackSourceSnapshotLineageTest {

	private DatapackSourceSnapshotCommandService service;
	private PlatformTransactionManager transactionManager;
	private DatapackSourceGovernancePolicy governancePolicy;
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:datapack-source-lineage;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS datapack_source_snapshot_events");
		jdbcTemplate.execute("DROP TABLE IF EXISTS datapack_source_lineage_locks");
		jdbcTemplate.execute("DROP TABLE IF EXISTS data_source_snapshots");
		jdbcTemplate.execute("CREATE TABLE datapack_source_lineage_locks (source_id VARCHAR(120) PRIMARY KEY)");
		jdbcTemplate.execute("""
			CREATE TABLE data_source_snapshots (
				snapshot_id VARCHAR(120) PRIMARY KEY,
				source_id VARCHAR(120) NOT NULL,
				provider VARCHAR(120) NOT NULL,
				retrieved_at TIMESTAMP NOT NULL,
				source_updated_at TIMESTAMP,
				freshness_basis_at TIMESTAMP,
				provider_valid_until TIMESTAMP,
				row_count INTEGER NOT NULL,
				coverage_count INTEGER,
				raw_sha256 VARCHAR(64) NOT NULL,
				raw_object_uri VARCHAR(1000) NOT NULL,
				redacted_request_fingerprint VARCHAR(64) NOT NULL,
				schema_fingerprint VARCHAR(64) NOT NULL,
				snapshot_status VARCHAR(30) NOT NULL,
				schema_status VARCHAR(30) NOT NULL,
				license_status VARCHAR(30) NOT NULL,
				fetch_status VARCHAR(30) NOT NULL,
				redistribution_allowed BOOLEAN NOT NULL,
				credential_redacted BOOLEAN NOT NULL,
				previous_snapshot_id VARCHAR(120),
				diff_summary VARCHAR(1000),
				diff_summary_json CLOB,
				freshness_expires_at TIMESTAMP NOT NULL,
				raw_retention_expires_at TIMESTAMP NOT NULL,
				governance_policy_version VARCHAR(32),
				governance_policy_sha256 VARCHAR(64)
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE datapack_source_snapshot_events (
				id VARCHAR(120) PRIMARY KEY,
				source_id VARCHAR(120) NOT NULL,
				snapshot_id VARCHAR(120) NOT NULL,
				operation_type VARCHAR(40) NOT NULL,
				operation_status VARCHAR(30) NOT NULL,
				requested_by VARCHAR(120) NOT NULL,
				reason VARCHAR(500) NOT NULL,
				idempotency_key VARCHAR(160) NOT NULL,
				created_at TIMESTAMP NOT NULL,
				UNIQUE (source_id, idempotency_key)
			)
			""");
		@SuppressWarnings("unchecked")
		ObjectProvider<Clock> clockProvider = mock(ObjectProvider.class);
		when(clockProvider.getIfAvailable(any())).thenReturn(
			Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC)
		);
		transactionManager = new DataSourceTransactionManager(dataSource);
		governancePolicy = testGovernancePolicy();
		service = new DatapackSourceSnapshotCommandService(
			new JdbcDataSourceSnapshotRepository(dataSource),
			transactionManager,
			clockProvider,
			new ObjectMapper(),
			governancePolicy
		);
	}

	@Test
	@DisplayName("최초 snapshot만 previous와 diff가 없는 root로 저장한다")
	void firstSnapshotCreatesRoot() {
		assertThat(service.createLockedSnapshot(command("source-a", "snapshot-a-1", null, null)))
			.isEqualTo("snapshot-a-1");

		assertThatThrownBy(() -> service.createLockedSnapshot(command("source-a", "snapshot-a-orphan", "missing", changedDiff())))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("SOURCE_LINEAGE_BROKEN");
	}

	@Test
	@DisplayName("두 번째 snapshot은 같은 source의 현재 head와 structured diff를 요구한다")
	void laterSnapshotRequiresExactSourceHeadAndDiff() {
		service.createLockedSnapshot(command("source-a", "snapshot-a-1", null, null));
		service.createLockedSnapshot(command("source-b", "snapshot-b-1", null, null));

		assertThatThrownBy(() -> service.createLockedSnapshot(command("source-a", "snapshot-a-null", null, null)))
			.hasMessageContaining("SOURCE_LINEAGE_BROKEN");
		assertThatThrownBy(() -> service.createLockedSnapshot(command("source-a", "snapshot-a-cross", "snapshot-b-1", changedDiff())))
			.hasMessageContaining("SOURCE_LINEAGE_BROKEN");

		assertThat(service.createLockedSnapshot(command("source-a", "snapshot-a-2", "snapshot-a-1", changedDiff())))
			.isEqualTo("snapshot-a-2");
		assertThatThrownBy(() -> service.createLockedSnapshot(command("source-a", "snapshot-a-fork", "snapshot-a-1", changedDiff())))
			.hasMessageContaining("SOURCE_LINEAGE_BROKEN");
	}

	@Test
	@DisplayName("실제 변경과 맞지 않는 NO_CHANGE diff는 거부한다")
	void noChangeDiffMustMatchSnapshotFields() {
		service.createLockedSnapshot(command("source-a", "snapshot-a-1", null, null));

		assertThatThrownBy(() -> service.createLockedSnapshot(command(
			"source-a",
			"snapshot-a-2",
			"snapshot-a-1",
			noChangeDiff()
		)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("SOURCE_DIFF_MISSING");
	}

	@Test
	@DisplayName("같은 idempotency key와 byte-equivalent snapshot은 head가 전진해도 재생한다")
	void sameCommandReplaysIdempotently() {
		var first = command("source-a", "snapshot-a-1", null, null);

		assertThat(service.createLockedSnapshot(first)).isEqualTo("snapshot-a-1");
		service.createLockedSnapshot(command("source-a", "snapshot-a-2", "snapshot-a-1", changedDiff()));
		assertThat(service.createLockedSnapshot(first)).isEqualTo("snapshot-a-1");
		assertThatThrownBy(() -> service.createLockedSnapshot(new SourceSnapshotCommand(
			first.snapshotId(),
			first.sourceId(),
			first.provider(),
			first.retrievedAt(),
			first.sourceUpdatedAt(),
			first.freshnessBasisAt(),
			first.providerValidUntil(),
			first.rowCount(),
			first.coverageCount() + 1,
			first.rawSha256(),
			first.rawObjectUri(),
			first.redactedRequestFingerprint(),
			first.schemaFingerprint(),
			first.schemaStatus(),
			first.licenseStatus(),
			first.fetchStatus(),
			first.redistributionAllowed(),
			first.credentialRedacted(),
			first.previousSnapshotId(),
			first.diffSummary(),
			first.diffSummaryJson(),
			first.freshnessExpiresAt(),
			first.rawRetentionExpiresAt(),
			first.governancePolicyVersion(),
			first.governancePolicySha256(),
			first.requestedBy(),
			first.reason(),
			first.idempotencyKey()
		))).hasMessageContaining("idempotency key");
	}

	@Test
	@DisplayName("기존 snapshot ID는 새 idempotency key로 다시 등록할 수 없다")
	void existingSnapshotCannotBeReRegisteredWithNewIdempotencyKey() {
		var first = command("source-a", "snapshot-a-1", null, null);
		service.createLockedSnapshot(first);

		assertThatThrownBy(() -> service.createLockedSnapshot(copyCommand(
			first,
			first.freshnessExpiresAt(),
			"different-idempotency-key"
		)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("snapshot ID");
	}

	@Test
	@DisplayName("정책이 갱신되어도 기존 idempotency key는 당시 snapshot을 재생한다")
	void existingCommandReplaysAfterGovernancePolicyUpdate() {
		var first = command("source-a", "snapshot-a-1", null, null);
		assertThat(service.createLockedSnapshot(first)).isEqualTo("snapshot-a-1");

		service = new DatapackSourceSnapshotCommandService(
			new JdbcDataSourceSnapshotRepository(((DataSourceTransactionManager) transactionManager).getDataSource()),
			transactionManager,
			fixedClockProvider(),
			new ObjectMapper(),
			governancePolicy("2026-08-01")
		);

		assertThat(service.createLockedSnapshot(first)).isEqualTo("snapshot-a-1");
	}

	@Test
	@DisplayName("V51 이전 governance binding이 없는 요청도 기존 idempotency key로 재생한다")
	void legacyCommandWithoutGovernanceBindingReplaysIdempotently() {
		jdbcTemplate.update("""
			INSERT INTO data_source_snapshots (
				snapshot_id, source_id, provider, retrieved_at, source_updated_at, row_count, coverage_count,
				raw_sha256, raw_object_uri, redacted_request_fingerprint, schema_fingerprint,
				snapshot_status, schema_status, license_status, fetch_status, redistribution_allowed,
				credential_redacted, previous_snapshot_id, diff_summary, diff_summary_json,
				freshness_expires_at, raw_retention_expires_at, governance_policy_version, governance_policy_sha256
			) VALUES (?, ?, ?, ?, ?, ?, 10, ?, ?, ?, ?, 'LOCKED', 'PASS', 'PASS', 'SUCCESS', TRUE,
				TRUE, NULL, NULL, NULL, ?, ?, NULL, NULL)
			""",
			"snapshot-legacy", "source-a", "provider",
			LocalDateTime.of(2026, 7, 1, 0, 0), LocalDateTime.of(2026, 7, 1, 0, 0), 10,
			"a".repeat(64), "s3://bucket/snapshot-legacy.json", "b".repeat(64), "c".repeat(64),
			LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 9, 29, 0, 0)
		);
		jdbcTemplate.update("""
			INSERT INTO datapack_source_snapshot_events (
				id, source_id, snapshot_id, operation_type, operation_status,
				requested_by, reason, idempotency_key, created_at
			) VALUES (?, ?, ?, 'CREATE_LOCKED', 'PASS', ?, ?, ?, ?)
			""",
			"legacy-event", "source-a", "snapshot-legacy", "qa-role", "legacy fixture",
			"legacy-idempotency", LocalDateTime.of(2026, 7, 1, 0, 1)
		);
		var legacy = new SourceSnapshotCommand(
			"snapshot-legacy", "source-a", "provider",
			LocalDateTime.of(2026, 7, 1, 0, 0), LocalDateTime.of(2026, 7, 1, 0, 0),
			null, null,
			10, 0, "a".repeat(64), "s3://bucket/snapshot-legacy.json", "b".repeat(64), "c".repeat(64),
			"PASS", "PASS", "SUCCESS", true, true, null, null, null,
			LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 9, 29, 0, 0),
			null, null, "qa-role", "legacy fixture", "legacy-idempotency"
		);

		assertThat(service.createLockedSnapshot(legacy)).isEqualTo("snapshot-legacy");
		assertThatThrownBy(() -> service.createLockedSnapshot(new SourceSnapshotCommand(
			"snapshot-new", "source-a", "provider",
			LocalDateTime.of(2026, 7, 2, 0, 0), LocalDateTime.of(2026, 7, 2, 0, 0),
			null, null,
			11, 1, "d".repeat(64), "s3://bucket/snapshot-new.json", "b".repeat(64), "c".repeat(64),
			"PASS", "PASS", "SUCCESS", true, true, "snapshot-legacy", "CHANGED", changedDiff(),
			LocalDateTime.of(2026, 8, 2, 0, 0), LocalDateTime.of(2026, 9, 30, 0, 0),
			null, null, "qa-role", "new fixture", "new-idempotency"
		))).hasMessageContaining("governancePolicyVersion");
	}

	@Test
	@DisplayName("같은 source의 동시 최초 snapshot은 하나만 root로 저장한다")
	void concurrentFirstSnapshotsCreateOneRoot() throws Exception {
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> createAfter(start, command("source-a", "snapshot-a-1", null, null)));
			var second = executor.submit(() -> createAfter(start, command("source-a", "snapshot-a-2", null, null)));
			start.countDown();

			var results = java.util.List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
			assertThat(results).contains("SOURCE_LINEAGE_BROKEN");
			assertThat(results).filteredOn(result -> result.startsWith("snapshot-a-")).hasSize(1);
		}
	}

	@Test
	@DisplayName("임의 governance hash와 90일을 넘는 raw retention은 저장하지 않는다")
	void rejectsForgedGovernanceBindingAndRetention() {
		var command = command("source-a", "snapshot-a-forged", null, null);
		var forged = new SourceSnapshotCommand(
			command.snapshotId(), command.sourceId(), command.provider(), command.retrievedAt(),
			command.sourceUpdatedAt(), command.freshnessBasisAt(), command.providerValidUntil(),
			command.rowCount(), command.coverageCount(), command.rawSha256(),
			command.rawObjectUri(), command.redactedRequestFingerprint(), command.schemaFingerprint(),
			command.schemaStatus(), command.licenseStatus(), command.fetchStatus(), command.redistributionAllowed(),
			command.credentialRedacted(), command.previousSnapshotId(), command.diffSummary(), command.diffSummaryJson(),
			command.freshnessExpiresAt(), LocalDateTime.of(2099, 1, 1, 0, 0),
			"forged-policy", "f".repeat(64), command.requestedBy(), command.reason(), command.idempotencyKey()
		);

		assertThatThrownBy(() -> service.createLockedSnapshot(forged))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("SOURCE_GOVERNANCE_OWNER_MISSING");

		var overdue = new SourceSnapshotCommand(
			command.snapshotId(), command.sourceId(), command.provider(), command.retrievedAt(),
			command.sourceUpdatedAt(), command.freshnessBasisAt(), command.providerValidUntil(),
			command.rowCount(), command.coverageCount(), command.rawSha256(),
			command.rawObjectUri(), command.redactedRequestFingerprint(), command.schemaFingerprint(),
			command.schemaStatus(), command.licenseStatus(), command.fetchStatus(), command.redistributionAllowed(),
			command.credentialRedacted(), command.previousSnapshotId(), command.diffSummary(), command.diffSummaryJson(),
			command.freshnessExpiresAt(), LocalDateTime.of(2099, 1, 1, 0, 0),
			governancePolicy.version(), governancePolicy.sha256(), command.requestedBy(), command.reason(),
			command.idempotencyKey()
		);
		assertThatThrownBy(() -> service.createLockedSnapshot(overdue))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("RAW_RETENTION_OVERDUE");
	}

	@Test
	@DisplayName("freshness expiry는 신뢰한 정책에서 파생한 값만 저장한다")
	void rejectsForgedFreshnessExpiry() {
		var command = command("source-a", "snapshot-a-forged-freshness", null, null);

		assertThatThrownBy(() -> service.createLockedSnapshot(copyCommand(
			command,
			LocalDateTime.of(2099, 1, 1, 0, 0),
			command.idempotencyKey()
		)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("SOURCE_FRESHNESS_DERIVATION_MISMATCH");
	}

	@Test
	@DisplayName("P1Y freshness는 Node producer와 같은 UTC calendar overflow를 사용한다")
	void yearlyFreshnessMatchesNodeCalendarOverflow() {
		governancePolicy = governancePolicy("2026-07-15", "P1Y");
		var leapDay = LocalDateTime.of(2024, 2, 29, 0, 0);

		var binding = governancePolicy.requireBinding(
			"source-a",
			leapDay,
			null,
			null,
			LocalDateTime.of(2025, 3, 1, 0, 0),
			LocalDateTime.of(2024, 5, 29, 0, 0),
			governancePolicy.version(),
			governancePolicy.sha256()
		);

		assertThat(binding.freshnessExpiresAt()).isEqualTo(LocalDateTime.of(2025, 3, 1, 0, 0));
	}

	@Test
	@DisplayName("retention과 freshness source binding이 다르면 기동 시 fail closed한다")
	void policySourceBindingsMustMatchAtStartup() {
		String policy = """
			{"policyVersion":"2026-07-15","retentionClasses":[{"id":"standard-90d","retentionDays":90}],"sources":[{"sourceId":"source-a","retentionClassId":"standard-90d"}]}
			""";
		String freshnessPolicy = """
			{"sourceClasses":[{"id":"test","sourceIds":["source-b"],"basisField":"retrievedAt","reverificationCadence":"P31D"}]}
			""";

		assertThatThrownBy(() -> new DatapackSourceGovernancePolicy(
			new ObjectMapper(),
			new ByteArrayResource(policy.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
			new ByteArrayResource(freshnessPolicy.getBytes(java.nio.charset.StandardCharsets.UTF_8))
		))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("source bindings");
	}

	@Test
	@DisplayName("provider validity가 있는 source는 policy basis와 validity로 freshness를 파생한다")
	void plannedSourceUsesPolicyBasisAndProviderValidity() {
		governancePolicy = plannedGovernancePolicy();
		service = new DatapackSourceSnapshotCommandService(
			new JdbcDataSourceSnapshotRepository(((DataSourceTransactionManager) transactionManager).getDataSource()),
			transactionManager,
			fixedClockProvider(),
			new ObjectMapper(),
			governancePolicy
		);
		var base = command("planned-a", "snapshot-planned-a", null, null);
		var planned = new SourceSnapshotCommand(
			base.snapshotId(), base.sourceId(), base.provider(), base.retrievedAt(), base.sourceUpdatedAt(),
			LocalDateTime.of(2026, 7, 1, 0, 0), LocalDateTime.of(2026, 7, 20, 0, 0),
			base.rowCount(), base.coverageCount(), base.rawSha256(), base.rawObjectUri(),
			base.redactedRequestFingerprint(), base.schemaFingerprint(), base.schemaStatus(), base.licenseStatus(),
			base.fetchStatus(), base.redistributionAllowed(), base.credentialRedacted(), base.previousSnapshotId(),
			base.diffSummary(), base.diffSummaryJson(), LocalDateTime.of(2026, 7, 20, 0, 0),
			base.rawRetentionExpiresAt(), governancePolicy.version(), governancePolicy.sha256(), base.requestedBy(),
			base.reason(), base.idempotencyKey()
		);

		assertThat(service.createLockedSnapshot(planned)).isEqualTo("snapshot-planned-a");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT freshness_basis_at FROM data_source_snapshots WHERE snapshot_id = ?",
			LocalDateTime.class,
			"snapshot-planned-a"
		)).isEqualTo(LocalDateTime.of(2026, 7, 1, 0, 0));
		assertThat(jdbcTemplate.queryForObject(
			"SELECT provider_valid_until FROM data_source_snapshots WHERE snapshot_id = ?",
			LocalDateTime.class,
			"snapshot-planned-a"
		)).isEqualTo(LocalDateTime.of(2026, 7, 20, 0, 0));
	}

	private String createAfter(CountDownLatch start, SourceSnapshotCommand command) throws InterruptedException {
		start.await();
		try {
			return new TransactionTemplate(transactionManager).execute(status -> service.createLockedSnapshot(command));
		} catch (IllegalArgumentException exception) {
			assertThat(exception).hasMessageContaining("SOURCE_LINEAGE_BROKEN");
			return "SOURCE_LINEAGE_BROKEN";
		}
	}

	private SourceSnapshotCommand command(
		String sourceId,
		String snapshotId,
		String previousSnapshotId,
		String diffSummaryJson
	) {
		boolean root = previousSnapshotId == null;
		return new SourceSnapshotCommand(
			snapshotId,
			sourceId,
			"provider",
			LocalDateTime.of(2026, 7, root ? 1 : 2, 0, 0),
			LocalDateTime.of(2026, 7, root ? 1 : 2, 0, 0),
			null,
			null,
			root ? 10 : 12,
			root ? 8 : 9,
			(root ? "a" : "d").repeat(64),
			"s3://bucket/%s.json".formatted(snapshotId),
			"b".repeat(64),
			"c".repeat(64),
			"PASS",
			"PASS",
			"SUCCESS",
			true,
			true,
			previousSnapshotId,
			root ? null : "CHANGED",
			diffSummaryJson,
			LocalDateTime.of(2026, 8, root ? 1 : 2, 0, 0),
			LocalDateTime.of(2026, 9, root ? 29 : 30, 0, 0),
			governancePolicy.version(),
			governancePolicy.sha256(),
			"qa-role",
			"source governance fixture",
			"idempotency-" + snapshotId
		);
	}

	private String changedDiff() {
		return """
			{"status":"CHANGED","rawHashChanged":true,"schemaHashChanged":false,"requestHashChanged":false,"sourceUpdatedAtChanged":true,"rowDelta":2,"coverageDelta":1}
			""".trim();
	}

	private String noChangeDiff() {
		return """
			{"status":"NO_CHANGE","rawHashChanged":false,"schemaHashChanged":false,"requestHashChanged":false,"sourceUpdatedAtChanged":false,"rowDelta":0,"coverageDelta":0}
			""".trim();
	}

	private SourceSnapshotCommand copyCommand(
		SourceSnapshotCommand command,
		LocalDateTime freshnessExpiresAt,
		String idempotencyKey
	) {
		return new SourceSnapshotCommand(
			command.snapshotId(), command.sourceId(), command.provider(), command.retrievedAt(), command.sourceUpdatedAt(),
			command.freshnessBasisAt(), command.providerValidUntil(),
			command.rowCount(), command.coverageCount(), command.rawSha256(), command.rawObjectUri(),
			command.redactedRequestFingerprint(), command.schemaFingerprint(), command.schemaStatus(), command.licenseStatus(),
			command.fetchStatus(), command.redistributionAllowed(), command.credentialRedacted(), command.previousSnapshotId(),
			command.diffSummary(), command.diffSummaryJson(), freshnessExpiresAt, command.rawRetentionExpiresAt(),
			command.governancePolicyVersion(), command.governancePolicySha256(), command.requestedBy(), command.reason(),
			idempotencyKey
		);
	}

	private DatapackSourceGovernancePolicy testGovernancePolicy() {
		return governancePolicy("2026-07-15");
	}

	private DatapackSourceGovernancePolicy governancePolicy(String version) {
		return governancePolicy(version, "P31D");
	}

	private DatapackSourceGovernancePolicy governancePolicy(String version, String cadence) {
		String policy = """
			{"policyVersion":"%s","retentionClasses":[{"id":"standard-90d","retentionDays":90}],"sources":[{"sourceId":"source-a","retentionClassId":"standard-90d"},{"sourceId":"source-b","retentionClassId":"standard-90d"}]}
			""".formatted(version);
		String freshnessPolicy = """
			{"sourceClasses":[{"id":"test","sourceIds":["source-a","source-b"],"basisField":"retrievedAt","reverificationCadence":"%s"}]}
			""".formatted(cadence);
		return new DatapackSourceGovernancePolicy(
			new ObjectMapper(),
			new ByteArrayResource(policy.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
			new ByteArrayResource(freshnessPolicy.getBytes(java.nio.charset.StandardCharsets.UTF_8))
		);
	}

	private DatapackSourceGovernancePolicy plannedGovernancePolicy() {
		String policy = """
			{"policyVersion":"2026-07-15","retentionClasses":[{"id":"standard-90d","retentionDays":90}],"sources":[{"sourceId":"planned-a","retentionClassId":"standard-90d"}]}
			""";
		String freshnessPolicy = """
			{"clockSkewSeconds":300,"sourceClasses":[{"id":"planned","sourceIds":["planned-a"],"basisField":"serviceEffectiveAt","maximumReverificationCadence":"P30D","futureBasisAllowed":true,"providerValidityEndField":"serviceEffectiveUntil"}]}
			""";
		return new DatapackSourceGovernancePolicy(
			new ObjectMapper(),
			new ByteArrayResource(policy.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
			new ByteArrayResource(freshnessPolicy.getBytes(java.nio.charset.StandardCharsets.UTF_8))
		);
	}

	@SuppressWarnings("unchecked")
	private ObjectProvider<Clock> fixedClockProvider() {
		ObjectProvider<Clock> clockProvider = mock(ObjectProvider.class);
		when(clockProvider.getIfAvailable(any())).thenReturn(
			Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC)
		);
		return clockProvider;
	}
}
