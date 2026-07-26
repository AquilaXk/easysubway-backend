package com.easysubway.datapack.adapter.out.persistence;

import com.easysubway.datapack.application.port.out.DatapackReleaseRequestRepository;
import com.easysubway.datapack.domain.DatapackReleaseRequest;
import com.easysubway.datapack.domain.DatapackReleaseRequestStatus;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDatapackReleaseRequestRepository implements DatapackReleaseRequestRepository {

	private static final RowMapper<DatapackReleaseRequest> ROW_MAPPER = (rs, n) -> new DatapackReleaseRequest(
		rs.getString("approval_id"), rs.getString("candidate_id"), rs.getString("scope_id"),
		rs.getString("target_channel"), rs.getString("build_spec_sha256"),
		rs.getString("source_snapshot_set_hash"), rs.getString("approved_ledger_hash"),
		rs.getString("requested_by"), rs.getString("approved_by"),
		DatapackReleaseRequestStatus.valueOf(rs.getString("status")),
		rs.getString("dispatch_idempotency_key"), rs.getString("workflow_run_url"),
		toLdt(rs.getTimestamp("created_at")), toLdt(rs.getTimestamp("approved_at")),
		toLdt(rs.getTimestamp("updated_at")),
		rs.getString("promote_outcome"), rs.getString("promote_detail"));

	private final JdbcTemplate jdbcTemplate;

	public JdbcDatapackReleaseRequestRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void save(DatapackReleaseRequest r) {
		int updated = jdbcTemplate.update(
			"UPDATE datapack_release_request SET candidate_id=?, scope_id=?, target_channel=?,"
				+ " build_spec_sha256=?, source_snapshot_set_hash=?, approved_ledger_hash=?,"
				+ " requested_by=?, approved_by=?, status=?, dispatch_idempotency_key=?,"
				+ " workflow_run_url=?, approved_at=?, updated_at=?,"
				+ " promote_outcome=?, promote_detail=? WHERE approval_id=?",
			r.candidateId(), r.scopeId(), r.targetChannel(), r.buildSpecSha256(),
			r.sourceSnapshotSetHash(), r.approvedLedgerHash(), r.requestedBy(), r.approvedBy(),
			r.status().name(), r.dispatchIdempotencyKey(), r.workflowRunUrl(),
			toTs(r.approvedAt()), toTs(r.updatedAt()),
			r.promoteOutcome(), r.promoteDetail(), r.approvalId());
		if (updated == 0) {
			jdbcTemplate.update(
				"INSERT INTO datapack_release_request (approval_id, candidate_id, scope_id,"
					+ " target_channel, build_spec_sha256, source_snapshot_set_hash, approved_ledger_hash,"
					+ " requested_by, approved_by, status, dispatch_idempotency_key, workflow_run_url,"
					+ " created_at, approved_at, updated_at, promote_outcome, promote_detail)"
					+ " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
				r.approvalId(), r.candidateId(), r.scopeId(), r.targetChannel(), r.buildSpecSha256(),
				r.sourceSnapshotSetHash(), r.approvedLedgerHash(), r.requestedBy(), r.approvedBy(),
				r.status().name(), r.dispatchIdempotencyKey(), r.workflowRunUrl(),
				toTs(r.createdAt()), toTs(r.approvedAt()), toTs(r.updatedAt()),
				r.promoteOutcome(), r.promoteDetail());
		}
	}

	@Override
	public Optional<DatapackReleaseRequest> findByApprovalId(String approvalId) {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject(
				"SELECT * FROM datapack_release_request WHERE approval_id=?", ROW_MAPPER, approvalId));
		} catch (EmptyResultDataAccessException e) {
			return Optional.empty();
		}
	}

	@Override
	public List<DatapackReleaseRequest> findRecent(int limit) {
		return jdbcTemplate.query(
			"SELECT * FROM datapack_release_request ORDER BY created_at DESC, approval_id DESC LIMIT ?",
			ROW_MAPPER, limit);
	}

	// 콜백 유실 복구(discoverMissing) 대상 = 게시를 기다리는 미종결 상태. DISPATCH_FAILED는
	// dispatch 발화 제거(#2564) 뒤 새로 생기지 않는 이력 상태지만 그 행도 수동 게시로 종결되므로,
	// 콜백이 유실되면 같은 안전망으로 복구해야 한다(제외하면 영구 미종결로 남는다).
	private List<DatapackReleaseRequest> findReconciliationDue(
		LocalDateTime cutoff, LocalDateTime now, int limit) {
		return jdbcTemplate.query("""
			SELECT * FROM datapack_release_request
			WHERE status IN ('APPROVED', 'DISPATCHED', 'DISPATCH_FAILED') AND updated_at <= ?
			  AND (reconciliation_next_attempt_at IS NULL OR reconciliation_next_attempt_at <= ?)
			ORDER BY COALESCE(reconciliation_next_attempt_at, updated_at), approval_id
			LIMIT ?
			""", ROW_MAPPER, toTs(cutoff), toTs(now), limit);
	}

	@Override
	public List<DatapackReleaseRequest> claimReconciliationDue(
		LocalDateTime cutoff, LocalDateTime now, LocalDateTime leaseUntil, int limit) {
		var candidates = findReconciliationDue(cutoff, now, limit);
		var claimed = new ArrayList<DatapackReleaseRequest>(candidates.size());
		for (var candidate : candidates) {
			int updated = jdbcTemplate.update("""
				UPDATE datapack_release_request SET reconciliation_next_attempt_at = ?
				WHERE approval_id = ?
				  AND status IN ('APPROVED', 'DISPATCHED', 'DISPATCH_FAILED')
				  AND updated_at <= ?
				  AND (reconciliation_next_attempt_at IS NULL OR reconciliation_next_attempt_at <= ?)
				""", toTs(leaseUntil), candidate.approvalId(), toTs(cutoff), toTs(now));
			if (updated == 1) claimed.add(candidate);
		}
		return claimed;
	}

	private static Timestamp toTs(LocalDateTime v) {
		return v == null ? null : Timestamp.valueOf(v);
	}

	private static LocalDateTime toLdt(Timestamp v) {
		return v == null ? null : v.toLocalDateTime();
	}
}
