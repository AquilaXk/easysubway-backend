package com.easysubway.datapack.adapter.out.persistence;

import com.easysubway.datapack.application.port.in.DatapackReleaseBlockerSummaryUseCase;
import com.easysubway.datapack.application.port.in.DatapackReleaseBlockerSummaryUseCase.DatapackReleaseBlockerSummary;
import com.easysubway.datapack.application.port.in.DatapackReleaseBlockerSummaryUseCase.ReleaseReadinessRow;
import com.easysubway.datapack.application.port.in.DatapackReleaseBlockerSummaryUseCase.StationReleaseBlockerRow;
import com.easysubway.datapack.application.port.in.DatapackReleaseBlockerSummaryUseCase.StationReleaseBlockerSummary;
import com.easysubway.datapack.domain.DatapackFreshness;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDatapackReleaseBlockerSummaryRepository implements DatapackReleaseBlockerSummaryUseCase {

	private final JdbcTemplate jdbcTemplate;
	private final Clock clock;

	@Autowired
	public JdbcDatapackReleaseBlockerSummaryRepository(DataSource dataSource, ObjectProvider<Clock> clockProvider) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
		this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
	}

	public DatapackReleaseBlockerSummary summarize() {
		LocalDateTime evaluationAt = LocalDateTime.now(clock);
		Optional<CandidateGateSummary> candidate = latestCandidate();
		long candidateGateBlockers = candidate.map(CandidateGateSummary::blockerCount).orElse(0L);
		long aliasBlockers = count("""
			SELECT COUNT(*)
			FROM external_alias_approvals
			WHERE approval_status <> 'APPROVED'
				AND superseded_by IS NULL
			""");
		long quarantineBlockers = count("""
			SELECT COUNT(*)
			FROM source_quarantine_records
			WHERE resolution_status = 'OPEN'
			""");
		long sourceFreshnessBlockers = countSourceFreshnessBlockers(candidate, evaluationAt);
		long manualOverrideBlockers = countManualOverrideBlockers();
		long facilityBlockers = countFacilityBlockers(null, evaluationAt);
		long routeGateBlockers = countRouteGateBlockers(null);
		long callbackReconciliationBlockers = count("""
			SELECT COUNT(*) FROM datapack_release_deliveries
			WHERE channel = 'production' AND state <> 'DELIVERED'
			  AND NOT (state = 'DEAD_LETTER'
				AND http_class = 'STALE'
				AND sanitized_detail = 'CURRENT_RELEASE_ADVANCED')
			""") + count("""
			SELECT COUNT(*) FROM datapack_release_request request
			WHERE request.target_channel = 'production'
			  AND request.status IN ('APPROVED', 'DISPATCHED')
			  AND request.updated_at <= ?
			  AND NOT EXISTS (
				SELECT 1 FROM datapack_release_deliveries delivery
				WHERE delivery.release_request_id = request.approval_id
			  )
			""", evaluationAt.minusMinutes(10));
		EvidenceBundleSummary evidenceBundle = evidenceBundle(candidate);
		ManifestSignatureSummary manifestSignature = evidenceBundle.manifestSignature();
		ReleaseChannelSummary productionChannel = productionChannel();
		long totalBlockers = candidateGateBlockers
			+ aliasBlockers
			+ quarantineBlockers
			+ sourceFreshnessBlockers
			+ manualOverrideBlockers
			+ facilityBlockers
			+ routeGateBlockers
			+ callbackReconciliationBlockers
			+ evidenceBundle.blockerCount();
		return new DatapackReleaseBlockerSummary(
			candidate.map(CandidateGateSummary::candidateId).orElse("-"),
			candidate.map(CandidateGateSummary::scopeId).orElse("-"),
			candidate.map(CandidateGateSummary::sourceSnapshotSetHash).orElse("-"),
			candidate.map(CandidateGateSummary::manifestSha256).orElse("-"),
			evidenceBundle.evidenceBundleSha256(),
			evidenceBundle.workflowRunUrl(),
			productionChannel.candidateId(),
			productionChannel.rollbackCandidateId(),
			releaseStatus(candidate, totalBlockers),
			totalBlockers,
			candidateGateBlockers,
			aliasBlockers,
			quarantineBlockers,
			sourceFreshnessBlockers,
			manualOverrideBlockers,
			facilityBlockers,
			routeGateBlockers,
			manifestSignature.blockerCount(),
			readinessRows(
				candidate,
				aliasBlockers,
				quarantineBlockers,
				sourceFreshnessBlockers,
				manualOverrideBlockers,
				facilityBlockers,
				routeGateBlockers,
				callbackReconciliationBlockers,
				evidenceBundle,
				manifestSignature
			),
			candidate.map(CandidateGateSummary::createdAt).orElse(null)
		);
	}

	public StationReleaseBlockerSummary summarizeStation(String stationId) {
		LocalDateTime evaluationAt = LocalDateTime.now(clock);
		long facilityBlockers = countFacilityBlockers(stationId, evaluationAt);
		long routeGateBlockers = countRouteGateBlockers(stationId);
		long facilityEvidenceRows = countFacilityEvidenceRows(stationId);
		long routeEvidenceRows = countRouteEvidenceRows(stationId);
		long totalBlockers = facilityBlockers + routeGateBlockers;
		boolean hasAnyEvidence = facilityEvidenceRows > 0 || routeEvidenceRows > 0;
		return new StationReleaseBlockerSummary(
			stationId,
			hasAnyEvidence && totalBlockers == 0 ? "PASS" : "확인 필요",
			totalBlockers,
			List.of(
				new StationReleaseBlockerRow("시설 근거", facilityBlockers, stationRowStatus(facilityBlockers, facilityEvidenceRows)),
				new StationReleaseBlockerRow("경로 게이트", routeGateBlockers, stationRowStatus(routeGateBlockers, routeEvidenceRows))
			)
		);
	}

	private Optional<CandidateGateSummary> latestCandidate() {
		return jdbcTemplate.query("""
			SELECT id, scope_id, source_snapshot_set_hash, manifest_sha256,
				coverage_status, validator_status,
				route_regression_status, android_evidence_status, created_at
			FROM datapack_candidates
			ORDER BY created_at DESC, id ASC
			LIMIT 1
			""", this::mapCandidate).stream().findFirst();
	}

	private CandidateGateSummary mapCandidate(ResultSet resultSet, int rowNumber) throws SQLException {
		return new CandidateGateSummary(
			resultSet.getString("id"),
			resultSet.getString("scope_id"),
			resultSet.getString("source_snapshot_set_hash"),
			valueOrDash(resultSet.getString("manifest_sha256")),
			resultSet.getString("coverage_status"),
			resultSet.getString("validator_status"),
			resultSet.getString("route_regression_status"),
			resultSet.getString("android_evidence_status"),
			resultSet.getTimestamp("created_at").toLocalDateTime()
		);
	}

	private List<ReleaseReadinessRow> readinessRows(
		Optional<CandidateGateSummary> candidate,
		long aliasBlockers,
		long quarantineBlockers,
		long sourceFreshnessBlockers,
		long manualOverrideBlockers,
		long facilityBlockers,
		long routeGateBlockers,
		long callbackReconciliationBlockers,
		EvidenceBundleSummary evidenceBundle,
		ManifestSignatureSummary manifestSignature
	) {
		long sourceBlockers = candidate.map(row -> "PASS".equals(row.coverageStatus()) ? 0L : 1L).orElse(1L)
			+ aliasBlockers
			+ quarantineBlockers;
		long validatorBlockers = candidate.map(row -> "PASS".equals(row.validatorStatus()) ? 0L : 1L).orElse(1L)
			+ evidenceBundle.validatorBlocker();
		long routeBlockers = candidate.map(row -> "PASS".equals(row.routeRegressionStatus()) ? 0L : 1L).orElse(1L)
			+ routeGateBlockers
			+ evidenceBundle.routeRegressionBlocker();
		long androidBlockers = candidate.map(row -> "PASS".equals(row.androidEvidenceStatus()) ? 0L : 1L).orElse(1L)
			+ evidenceBundle.androidBlocker();
		return List.of(
			new ReleaseReadinessRow("소스 커버리지", statusFor(sourceBlockers), sourceBlockers, sourceNote(aliasBlockers, quarantineBlockers)),
			new ReleaseReadinessRow(
				"소스 최신성",
				candidate.isEmpty() ? "확인 필요" : statusFor(sourceFreshnessBlockers),
				sourceFreshnessBlockers,
				candidate.isEmpty()
					? "source snapshot 없음"
					: sourceFreshnessBlockers > 0 ? "SOURCE_SNAPSHOT_EXPIRED" : "최신 원천 스냅샷"
			),
			new ReleaseReadinessRow("검증기", statusFor(validatorBlockers), validatorBlockers, "SQLite 무결성 / 검증기 게이트"),
			new ReleaseReadinessRow("시설 근거", statusFor(facilityBlockers), facilityBlockers, "엄격 경로 대상 시설 근거"),
			new ReleaseReadinessRow("경로 게이트", statusFor(routeBlockers), routeBlockers, "ENTRY/EXIT/TRANSFER 및 생성된 커넥터 게이트"),
			new ReleaseReadinessRow("Android 증거", statusFor(androidBlockers), androidBlockers, "Android 데이터팩 도입 증거"),
			new ReleaseReadinessRow("매니페스트 서명", manifestSignature.status(), manifestSignature.blockerCount(), "릴리스 증거 번들 / 서명"),
			new ReleaseReadinessRow(
				"콜백 정합성 확인",
				candidate.isEmpty() && callbackReconciliationBlockers == 0
					? "확인 필요" : statusFor(callbackReconciliationBlockers),
				callbackReconciliationBlockers,
				callbackReconciliationBlockers > 0
					? "CALLBACK_RECONCILIATION_REQUIRED" : "발송 확인됨"),
			new ReleaseReadinessRow("수동 오버라이드", statusFor(manualOverrideBlockers), manualOverrideBlockers, "승인 / 만료 / 충돌 게이트")
		);
	}

	private EvidenceBundleSummary evidenceBundle(Optional<CandidateGateSummary> candidate) {
		if (candidate.isEmpty()) {
			return EvidenceBundleSummary.empty();
		}
		return jdbcTemplate.query("""
			SELECT evidence_bundle_sha256, workflow_run_url, validator_status,
				route_regression_status, manifest_signature_status, android_evidence_status
			FROM datapack_release_evidence_bundles
			WHERE candidate_id = ?
			""", (resultSet, rowNumber) -> new EvidenceBundleSummary(
				true,
				resultSet.getString("evidence_bundle_sha256"),
				redactedUrl(resultSet.getString("workflow_run_url")),
				resultSet.getString("validator_status"),
				resultSet.getString("route_regression_status"),
				resultSet.getString("manifest_signature_status"),
				resultSet.getString("android_evidence_status")
			), candidate.get().candidateId()).stream().findFirst().orElse(EvidenceBundleSummary.empty());
	}

	private ReleaseChannelSummary productionChannel() {
		return jdbcTemplate.query("""
			SELECT candidate_id, previous_stable_candidate_id
			FROM datapack_release_channels
			WHERE channel = 'production'
			""", (resultSet, rowNumber) -> new ReleaseChannelSummary(
			resultSet.getString("candidate_id"),
			valueOrDash(resultSet.getString("previous_stable_candidate_id"))
		)).stream().findFirst().orElse(ReleaseChannelSummary.empty());
	}

	private long countManualOverrideBlockers() {
		return count("""
			SELECT COUNT(*)
			FROM manual_overrides
			WHERE superseded_by IS NULL
				AND (
					approval_status <> 'APPROVED'
					OR conflict_status = 'UNRESOLVED'
					OR approved_by IS NULL
					OR approved_at IS NULL
					OR approved_by = requested_by
					OR (strict_route_eligible = TRUE AND route_safety_approved_by IS NULL)
				)
			""");
	}

	private long countSourceFreshnessBlockers(Optional<CandidateGateSummary> candidate, LocalDateTime evaluationAt) {
		if (candidate.isEmpty()) {
			return 0L;
		}
		List<String> snapshotIds = jdbcTemplate.query("""
			SELECT source_snapshot_ids
			FROM datapack_candidate_inputs
			WHERE candidate_id = ?
			""", (resultSet, rowNumber) -> resultSet.getString("source_snapshot_ids"), candidate.get().candidateId())
			.stream()
			.flatMap(value -> Arrays.stream(value.split(",")))
			.map(String::trim)
			.filter(value -> !value.isEmpty())
			.distinct()
			.toList();
		if (snapshotIds.isEmpty()) {
			return 1L;
		}
		String placeholders = String.join(", ", snapshotIds.stream().map(ignored -> "?").toList());
		List<LocalDateTime> expiresAtValues = jdbcTemplate.query(
			"SELECT freshness_expires_at FROM data_source_snapshots WHERE snapshot_id IN (" + placeholders + ")",
			(resultSet, rowNumber) -> resultSet.getTimestamp("freshness_expires_at").toLocalDateTime(),
			snapshotIds.toArray()
		);
		long missingSnapshotBlockers = snapshotIds.size() - expiresAtValues.size();
		return missingSnapshotBlockers + expiresAtValues.stream()
			.filter(expiresAt -> DatapackFreshness.isStale(evaluationAt, expiresAt))
			.count();
	}

	private long countFacilityBlockers(String stationId, LocalDateTime evaluationAt) {
		return countWithOptionalStationAndEvaluationAt("""
			SELECT COUNT(*)
			FROM facility_evidence
			WHERE (
				strict_route_eligible = FALSE
				OR evidence_kind = 'UNKNOWN_PENDING_REVIEW'
				OR operational_status IN ('UNKNOWN', 'CHECK_REQUIRED')
				OR conflict_status = 'UNRESOLVED'
				OR freshness_expires_at <= ?
			)
			""", stationId, evaluationAt);
	}

	private long countRouteGateBlockers(String stationId) {
		return countWithOptionalStation("""
			SELECT COUNT(*)
			FROM route_edge_evidence
			WHERE (
				strict_route_eligible = FALSE
				OR edge_type = 'GENERATED_CONNECTOR'
				OR provenance_kind IN ('GENERATED', 'UNKNOWN')
				OR verification_status IN ('UNKNOWN', 'GENERATED', 'STALE', 'MISSING')
			)
			""", stationId);
	}

	private long countFacilityEvidenceRows(String stationId) {
		Long result = jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM facility_evidence
			WHERE station_id = ?
			""", Long.class, stationId);
		return result == null ? 0L : result;
	}

	private long countRouteEvidenceRows(String stationId) {
		Long result = jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM route_edge_evidence
			WHERE station_id = ?
			""", Long.class, stationId);
		return result == null ? 0L : result;
	}

	private long countWithOptionalStation(String baseSql, String stationId) {
		if (stationId == null) {
			return count(baseSql);
		}
		Long result = jdbcTemplate.queryForObject(baseSql + " AND station_id = ?", Long.class, stationId);
		return result == null ? 0L : result;
	}

	private long countWithOptionalStationAndEvaluationAt(
		String baseSql,
		String stationId,
		LocalDateTime evaluationAt
	) {
		if (stationId == null) {
			Long result = jdbcTemplate.queryForObject(baseSql, Long.class, evaluationAt);
			return result == null ? 0L : result;
		}
		Long result = jdbcTemplate.queryForObject(
			baseSql + " AND station_id = ?",
			Long.class,
			evaluationAt,
			stationId
		);
		return result == null ? 0L : result;
	}

	private long count(String sql) {
		Long result = jdbcTemplate.queryForObject(sql, Long.class);
		return result == null ? 0L : result;
	}

	private long count(String sql, Object parameter) {
		Long result = jdbcTemplate.queryForObject(sql, Long.class, parameter);
		return result == null ? 0L : result;
	}

	private static String releaseStatus(Optional<CandidateGateSummary> candidate, long totalBlockers) {
		if (candidate.isEmpty()) {
			return "확인 필요";
		}
		return totalBlockers == 0 ? "READY" : "FAIL";
	}

	private static String statusFor(long blockerCount) {
		return blockerCount == 0 ? "PASS" : "FAIL";
	}

	private static String rowStatus(long blockerCount) {
		return blockerCount == 0 ? "PASS" : "확인 필요";
	}

	private static String stationRowStatus(long blockerCount, long evidenceRows) {
		if (evidenceRows == 0) {
			return "집계 전";
		}
		return rowStatus(blockerCount);
	}

	private static String sourceNote(long aliasBlockers, long quarantineBlockers) {
		return "alias " + aliasBlockers + " / quarantine " + quarantineBlockers;
	}

	private static String valueOrDash(String value) {
		if (value == null || value.isBlank()) {
			return "-";
		}
		return value;
	}

	private static String redactedUrl(String value) {
		String text = valueOrDash(value);
		int query = text.indexOf('?');
		int fragment = text.indexOf('#');
		int cut = query < 0 ? fragment : (fragment < 0 ? query : Math.min(query, fragment));
		return cut < 0 ? text : text.substring(0, cut) + "?redacted";
	}

	private record CandidateGateSummary(
		String candidateId,
		String scopeId,
		String sourceSnapshotSetHash,
		String manifestSha256,
		String coverageStatus,
		String validatorStatus,
		String routeRegressionStatus,
		String androidEvidenceStatus,
		LocalDateTime createdAt
	) {

		long blockerCount() {
			return List.of(coverageStatus, validatorStatus, routeRegressionStatus, androidEvidenceStatus)
				.stream()
				.filter(status -> !"PASS".equals(status))
				.count();
		}
	}

	private record ManifestSignatureSummary(String status, long blockerCount) {
	}

	private record EvidenceBundleSummary(
		boolean exists,
		String evidenceBundleSha256,
		String workflowRunUrl,
		String validatorStatus,
		String routeRegressionStatus,
		String manifestSignatureStatus,
		String androidEvidenceStatus
	) {

		long blockerCount() {
			if (!exists) {
				return 1;
			}
			return validatorBlocker() + routeRegressionBlocker() + manifestSignature().blockerCount() + androidBlocker();
		}

		long validatorBlocker() {
			return statusBlocker(validatorStatus);
		}

		long routeRegressionBlocker() {
			return statusBlocker(routeRegressionStatus);
		}

		long androidBlocker() {
			return statusBlocker(androidEvidenceStatus);
		}

		ManifestSignatureSummary manifestSignature() {
			return new ManifestSignatureSummary(statusOrNeed(manifestSignatureStatus), statusBlocker(manifestSignatureStatus));
		}

		static EvidenceBundleSummary empty() {
			return new EvidenceBundleSummary(false, "-", "-", "PASS", "PASS", "확인 필요", "PASS");
		}

		private static long statusBlocker(String status) {
			return "PASS".equals(status) ? 0 : 1;
		}

		private static String statusOrNeed(String status) {
			return status == null || status.isBlank() ? "확인 필요" : status;
		}
	}

	private record ReleaseChannelSummary(String candidateId, String rollbackCandidateId) {

		static ReleaseChannelSummary empty() {
			return new ReleaseChannelSummary("-", "-");
		}
	}
}
