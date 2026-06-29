package com.easysubway.datapack.adapter.out.persistence;

import com.easysubway.datapack.application.port.in.DatapackReleaseBlockerSummaryUseCase;
import com.easysubway.datapack.application.port.in.DatapackReleaseBlockerSummaryUseCase.DatapackReleaseBlockerSummary;
import com.easysubway.datapack.application.port.in.DatapackReleaseBlockerSummaryUseCase.ReleaseReadinessRow;
import com.easysubway.datapack.application.port.in.DatapackReleaseBlockerSummaryUseCase.StationReleaseBlockerRow;
import com.easysubway.datapack.application.port.in.DatapackReleaseBlockerSummaryUseCase.StationReleaseBlockerSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDatapackReleaseBlockerSummaryRepository implements DatapackReleaseBlockerSummaryUseCase {

	private final JdbcTemplate jdbcTemplate;

	@Autowired
	public JdbcDatapackReleaseBlockerSummaryRepository(DataSource dataSource) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	public DatapackReleaseBlockerSummary summarize() {
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
		long manualOverrideBlockers = countManualOverrideBlockers();
		long facilityBlockers = countFacilityBlockers(null);
		long routeGateBlockers = countRouteGateBlockers(null);
		ManifestSignatureSummary manifestSignature = manifestSignature(candidate);
		long totalBlockers = candidateGateBlockers
			+ aliasBlockers
			+ quarantineBlockers
			+ manualOverrideBlockers
			+ facilityBlockers
			+ routeGateBlockers
			+ manifestSignature.blockerCount();
		return new DatapackReleaseBlockerSummary(
			candidate.map(CandidateGateSummary::candidateId).orElse("-"),
			candidate.map(CandidateGateSummary::scopeId).orElse("-"),
			releaseStatus(candidate, totalBlockers),
			totalBlockers,
			candidateGateBlockers,
			aliasBlockers,
			quarantineBlockers,
			manualOverrideBlockers,
			facilityBlockers,
			routeGateBlockers,
			manifestSignature.blockerCount(),
			readinessRows(
				candidate,
				aliasBlockers,
				quarantineBlockers,
				manualOverrideBlockers,
				facilityBlockers,
				routeGateBlockers,
				manifestSignature
			),
			candidate.map(CandidateGateSummary::createdAt).orElse(null)
		);
	}

	public StationReleaseBlockerSummary summarizeStation(String stationId) {
		long facilityBlockers = countFacilityBlockers(stationId);
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
				new StationReleaseBlockerRow("Facility evidence", facilityBlockers, stationRowStatus(facilityBlockers, facilityEvidenceRows)),
				new StationReleaseBlockerRow("Route gate", routeGateBlockers, stationRowStatus(routeGateBlockers, routeEvidenceRows))
			)
		);
	}

	private Optional<CandidateGateSummary> latestCandidate() {
		return jdbcTemplate.query("""
			SELECT id, scope_id, coverage_status, validator_status,
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
		long manualOverrideBlockers,
		long facilityBlockers,
		long routeGateBlockers,
		ManifestSignatureSummary manifestSignature
	) {
		long sourceBlockers = candidate.map(row -> "PASS".equals(row.coverageStatus()) ? 0L : 1L).orElse(1L)
			+ aliasBlockers
			+ quarantineBlockers;
		long validatorBlockers = candidate.map(row -> "PASS".equals(row.validatorStatus()) ? 0L : 1L).orElse(1L);
		long routeBlockers = candidate.map(row -> "PASS".equals(row.routeRegressionStatus()) ? 0L : 1L).orElse(1L)
			+ routeGateBlockers;
		long androidBlockers = candidate.map(row -> "PASS".equals(row.androidEvidenceStatus()) ? 0L : 1L).orElse(1L);
		return List.of(
			new ReleaseReadinessRow("Source coverage", statusFor(sourceBlockers), sourceBlockers, sourceNote(aliasBlockers, quarantineBlockers)),
			new ReleaseReadinessRow("Validator", statusFor(validatorBlockers), validatorBlockers, "SQLite integrity / validator gates"),
			new ReleaseReadinessRow("Facility evidence", statusFor(facilityBlockers), facilityBlockers, "strict route eligible facility evidence"),
			new ReleaseReadinessRow("Route gate", statusFor(routeBlockers), routeBlockers, "ENTRY/EXIT/TRANSFER and generated connector gates"),
			new ReleaseReadinessRow("Android evidence", statusFor(androidBlockers), androidBlockers, "Android datapack adoption evidence"),
			new ReleaseReadinessRow("Manifest signature", manifestSignature.status(), manifestSignature.blockerCount(), "release evidence bundle / signature"),
			new ReleaseReadinessRow("Manual override", statusFor(manualOverrideBlockers), manualOverrideBlockers, "approval / expiry / conflict gates")
		);
	}

	private ManifestSignatureSummary manifestSignature(Optional<CandidateGateSummary> candidate) {
		if (candidate.isEmpty()) {
			return new ManifestSignatureSummary("확인 필요", 1);
		}
		String status = jdbcTemplate.query("""
			SELECT manifest_signature_status
			FROM datapack_release_evidence_bundles
			WHERE candidate_id = ?
			""", (resultSet, rowNumber) -> resultSet.getString("manifest_signature_status"), candidate.get().candidateId())
			.stream()
			.findFirst()
			.map(manifestStatus -> "PASS".equals(manifestStatus) ? "PASS" : manifestStatus)
			.orElse("확인 필요");
		return new ManifestSignatureSummary(status, "PASS".equals(status) ? 0 : 1);
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

	private long countFacilityBlockers(String stationId) {
		return countWithOptionalStation("""
			SELECT COUNT(*)
			FROM facility_evidence
			WHERE (
				strict_route_eligible = FALSE
				OR evidence_kind = 'UNKNOWN_PENDING_REVIEW'
				OR operational_status IN ('UNKNOWN', 'CHECK_REQUIRED')
				OR conflict_status = 'UNRESOLVED'
			)
			""", stationId);
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

	private long count(String sql) {
		Long result = jdbcTemplate.queryForObject(sql, Long.class);
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

	private record CandidateGateSummary(
		String candidateId,
		String scopeId,
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
}
