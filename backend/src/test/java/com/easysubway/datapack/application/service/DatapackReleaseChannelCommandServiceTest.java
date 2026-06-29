package com.easysubway.datapack.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.datapack.application.service.DatapackReleaseChannelCommandService.ReleaseChannelCommand;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@DisplayName("데이터팩 release channel command service")
class DatapackReleaseChannelCommandServiceTest {

	private static final String SHA_1 = "1".repeat(64);
	private static final String SHA_2 = "2".repeat(64);
	private static final String SHA_3 = "3".repeat(64);
	private static final String SHA_4 = "4".repeat(64);

	@Autowired
	private DatapackReleaseChannelCommandService service;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM datapack_release_channel_events");
		jdbcTemplate.update("DELETE FROM datapack_release_channels");
		jdbcTemplate.update("DELETE FROM datapack_release_evidence_bundles");
		jdbcTemplate.update("DELETE FROM datapack_candidate_inputs");
		jdbcTemplate.update("DELETE FROM datapack_candidates");
		insertCandidate("candidate-stable-1", "2026.06.27-stable.1", SHA_1, "PROMOTED");
		insertCandidate("candidate-stable-2", "2026.06.28-stable.2", SHA_2, "PROMOTED");
		insertCandidate("candidate-stable-3", "2026.06.29-stable.3", SHA_3, "PROMOTED");
		insertCandidate("candidate-stable-4", "2026.06.30-stable.4", SHA_4, "APPROVED");
		insertProductionChannel("PASS");
	}

	@Test
	@DisplayName("promotion은 channel pointer와 event를 한 transaction으로 기록한다")
	void promoteUpdatesChannelAndWritesEvent() {
		var result = service.promote(command("candidate-stable-3", "candidate-stable-4", SHA_3, SHA_4, "idem-promote-1"));

		assertThat(result.idempotentReplay()).isFalse();
		assertThat(result.operationType()).isEqualTo("PROMOTE");
		assertThat(result.nextCandidateId()).isEqualTo("candidate-stable-4");
		assertThat(channelValue("candidate_id")).isEqualTo("candidate-stable-4");
		assertThat(channelValue("manifest_sha256")).isEqualTo(SHA_4);
		assertThat(channelValue("previous_stable_candidate_id")).isEqualTo("candidate-stable-3");
		assertThat(channelValue("previous_manifest_sha256")).isEqualTo(SHA_3);
		assertThat(channelValue("last_operation_type")).isEqualTo("PROMOTE");
		assertThat(channelValue("last_operation_status")).isEqualTo("PASS");
		assertThat(eventValue("idem-promote-1", "requested_by")).isEqualTo("data-operator");
		assertThat(eventValue("idem-promote-1", "approved_by")).isEqualTo("release-approver");
		assertThat(eventValue("idem-promote-1", "reason")).isEqualTo("release request");
		assertThat(eventValue("idem-promote-1", "workflow_run_url"))
			.isEqualTo("https://github.com/AquilaXk/easysubway/actions/runs/1131");
		assertThat(eventCount("idem-promote-1")).isEqualTo(1);
	}

	@Test
	@DisplayName("같은 idempotency key 재요청은 기존 event를 재사용한다")
	void repeatedIdempotencyKeyDoesNotCreateEventAgain() {
		var first = service.promote(command("candidate-stable-3", "candidate-stable-4", SHA_3, SHA_4, "idem-replay"));
		var second = service.promote(command("candidate-stable-3", "candidate-stable-4", SHA_3, SHA_4, "idem-replay"));

		assertThat(first.eventId()).isEqualTo(second.eventId());
		assertThat(second.idempotentReplay()).isTrue();
		assertThat(eventCount("idem-replay")).isEqualTo(1);
	}

	@Test
	@DisplayName("같은 idempotency key라도 요청 본문이 다르면 거절한다")
	void idempotencyKeyRequiresSameRequestPayload() {
		service.promote(command("candidate-stable-3", "candidate-stable-4", SHA_3, SHA_4, "idem-conflict"));

		assertThatThrownBy(() -> service.rollback(
			command("candidate-stable-4", "candidate-stable-3", SHA_4, SHA_3, "idem-conflict")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("idempotency key already belongs to a different release operation");
		assertThat(eventCount("idem-conflict")).isEqualTo(1);
	}

	@Test
	@DisplayName("같은 idempotency key라도 workflow run URL이 다르면 거절한다")
	void idempotencyKeyIncludesWorkflowRunUrl() {
		service.promote(command("candidate-stable-3", "candidate-stable-4", SHA_3, SHA_4, "idem-workflow"));

		var changedWorkflowCommand = new ReleaseChannelCommand(
			"production",
			"candidate-stable-3",
			"candidate-stable-4",
			SHA_3,
			SHA_4,
			"data-operator",
			"release-approver",
			"release request",
			"idem-workflow",
			"https://github.com/AquilaXk/easysubway/actions/runs/changed"
		);

		assertThatThrownBy(() -> service.promote(changedWorkflowCommand))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("idempotency key already belongs to a different release operation");
		assertThat(eventCount("idem-workflow")).isEqualTo(1);
	}

	@Test
	@DisplayName("진행 중인 channel operation이 있으면 새 요청을 거절한다")
	void pendingOperationBlocksNewCommand() {
		jdbcTemplate.update("""
			UPDATE datapack_release_channels
			SET last_operation_status = 'PENDING', idempotency_key = 'idem-pending'
			WHERE channel = 'production'
			""");

		assertThatThrownBy(() -> service.promote(
			command("candidate-stable-3", "candidate-stable-4", SHA_3, SHA_4, "idem-new")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("already has a pending release operation");
	}

	@Test
	@DisplayName("rollback은 이전 stable candidate로만 허용된다")
	void rollbackRejectsCandidateThatIsNotPreviousStable() {
		assertThatThrownBy(() -> service.rollback(
			command("candidate-stable-3", "candidate-stable-1", SHA_3, SHA_1, "idem-bad-rollback")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("previous stable candidate");
	}

	@Test
	@DisplayName("rollback은 이전 stable pointer로 되돌리고 현재 stable을 rollback 대상으로 보관한다")
	void rollbackSwapsCurrentAndPreviousStable() {
		var result = service.rollback(command("candidate-stable-3", "candidate-stable-2", SHA_3, SHA_2, "idem-rollback"));

		assertThat(result.operationType()).isEqualTo("ROLLBACK");
		assertThat(result.nextCandidateId()).isEqualTo("candidate-stable-2");
		assertThat(channelValue("candidate_id")).isEqualTo("candidate-stable-2");
		assertThat(channelValue("manifest_sha256")).isEqualTo(SHA_2);
		assertThat(channelValue("previous_stable_candidate_id")).isEqualTo("candidate-stable-3");
		assertThat(channelValue("previous_manifest_sha256")).isEqualTo(SHA_3);
		assertThat(channelValue("last_operation_type")).isEqualTo("ROLLBACK");
		assertThat(eventCount("idem-rollback")).isEqualTo(1);
	}

	@ParameterizedTest(name = "{0} 누락은 요청을 거절한다")
	@MethodSource("missingAuditAndHashFields")
	@DisplayName("reason, actor, manifest hash 같은 필수 값이 없으면 요청을 거절한다")
	void commandRequiresAuditAndHashFields(String expectedFieldName, ReleaseChannelCommand command) {
		assertThatThrownBy(() -> service.promote(command))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(expectedFieldName);
	}

	@Test
	@DisplayName("manifest hash가 64자 hex가 아니면 요청을 거절한다")
	void commandRejectsNonHexManifestHash() {
		var command = new ReleaseChannelCommand(
			"production",
			"candidate-stable-3",
			"candidate-stable-4",
			SHA_3,
			"z".repeat(64),
			"data-operator",
			"release-approver",
			"ship stable 4",
			"idem-non-hex-hash",
			"https://github.com/AquilaXk/easysubway/actions/runs/1131"
		);

		assertThatThrownBy(() -> service.promote(command))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("nextManifestSha256 must be a sha256 hex string");
	}

	@Test
	@DisplayName("요청자와 승인자가 같으면 production 승격 요청을 거절한다")
	void commandRejectsSameRequesterAndApprover() {
		var command = new ReleaseChannelCommand(
			"production",
			"candidate-stable-3",
			"candidate-stable-4",
			SHA_3,
			SHA_4,
			"release-operator",
			"release-operator",
			"ship stable 4",
			"idem-same-actor",
			"https://github.com/AquilaXk/easysubway/actions/runs/1140"
		);

		assertThatThrownBy(() -> service.promote(command))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("approvedBy must be different from requestedBy");
		assertThat(eventCount("idem-same-actor")).isZero();
	}

	private ReleaseChannelCommand command(
		String previousCandidateId,
		String nextCandidateId,
		String previousManifestSha256,
		String nextManifestSha256,
		String idempotencyKey
	) {
		return new ReleaseChannelCommand(
			"production",
			previousCandidateId,
			nextCandidateId,
			previousManifestSha256,
			nextManifestSha256,
			"data-operator",
			"release-approver",
			"release request",
			idempotencyKey,
			"https://github.com/AquilaXk/easysubway/actions/runs/1131"
		);
	}

	private void insertCandidate(String id, String version, String manifestSha256, String approvalStatus) {
		jdbcTemplate.update("""
			INSERT INTO datapack_candidates (
				id, scope_id, artifact_kind, version, source_snapshot_set_hash,
				override_set_hash, build_spec_sha256, source_inventory_sha256,
				sqlite_sha256, gzip_sha256, manifest_sha256, coverage_status,
				validator_status, route_regression_status, android_evidence_status,
				approval_status, created_at
			)
			VALUES (?, 'capital_pilot_android_v1', 'DATAPACK',
				?, ?, ?, ?, ?, ?, ?, ?, 'PASS', 'PASS', 'PASS',
				'PASS', ?, '2026-06-29 03:00:00')
			""",
			id,
			version,
			"a".repeat(64),
			"b".repeat(64),
			"c".repeat(64),
			"d".repeat(64),
			"e".repeat(64),
			"f".repeat(64),
			manifestSha256,
			approvalStatus
		);
	}

	private void insertProductionChannel(String status) {
		jdbcTemplate.update("""
			INSERT INTO datapack_release_channels (
				channel, candidate_id, manifest_url, manifest_sha256,
				previous_stable_candidate_id, previous_manifest_sha256,
				rollback_available, last_operation_type, last_operation_status,
				requested_by, approved_by, reason, idempotency_key, updated_at
			)
			VALUES ('production', 'candidate-stable-3',
				'https://datapack.example.com/production/current.json', ?,
				'candidate-stable-2', ?, TRUE, 'PROMOTE', ?,
				'data-operator', 'release-approver', 'approval-1128',
				'idempotency-production-1128', '2026-06-29 03:10:00')
			""",
			SHA_3,
			SHA_2,
			status
		);
	}

	private String channelValue(String column) {
		return jdbcTemplate.queryForObject(
			"SELECT " + column + " FROM datapack_release_channels WHERE channel = 'production'",
			String.class
		);
	}

	private Integer eventCount(String idempotencyKey) {
		return jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM datapack_release_channel_events
			WHERE channel = 'production' AND idempotency_key = ?
			""", Integer.class, idempotencyKey);
	}

	private String eventValue(String idempotencyKey, String column) {
		return jdbcTemplate.queryForObject(
			"SELECT " + column + " FROM datapack_release_channel_events "
				+ "WHERE channel = 'production' AND idempotency_key = ?",
			String.class,
			idempotencyKey
		);
	}

	private static Stream<Arguments> missingAuditAndHashFields() {
		return Stream.of(
			Arguments.of("requestedBy", commandWithMissing("requestedBy")),
			Arguments.of("approvedBy", commandWithMissing("approvedBy")),
			Arguments.of("reason", commandWithMissing("reason")),
			Arguments.of("previousManifestSha256", commandWithMissing("previousManifestSha256")),
			Arguments.of("nextManifestSha256", commandWithMissing("nextManifestSha256"))
		);
	}

	private static ReleaseChannelCommand commandWithMissing(String fieldName) {
		return new ReleaseChannelCommand(
			"production",
			"candidate-stable-3",
			"candidate-stable-4",
			"previousManifestSha256".equals(fieldName) ? "" : SHA_3,
			"nextManifestSha256".equals(fieldName) ? "" : SHA_4,
			"requestedBy".equals(fieldName) ? "" : "data-operator",
			"approvedBy".equals(fieldName) ? "" : "release-approver",
			"reason".equals(fieldName) ? "" : "ship stable 4",
			"idem-missing-" + fieldName,
			"https://github.com/AquilaXk/easysubway/actions/runs/1131"
		);
	}

	@TestConfiguration
	static class ClockConfiguration {

		@Bean
		Clock datapackReleaseChannelCommandClock() {
			return Clock.fixed(Instant.parse("2026-06-29T03:30:00Z"), ZoneId.of("Asia/Seoul"));
		}
	}
}
