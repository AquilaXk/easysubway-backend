package com.easysubway.datapack.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDatapackReleaseChannelRepository {

	private final JdbcTemplate jdbcTemplate;

	@Autowired
	public JdbcDatapackReleaseChannelRepository(DataSource dataSource) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	public List<ReleaseChannelRow> listChannels() {
		return jdbcTemplate.query("""
			SELECT channel.channel, channel.candidate_id, candidate.version AS candidate_version,
				channel.manifest_url, channel.manifest_sha256,
				channel.previous_stable_candidate_id,
				previous_candidate.version AS previous_stable_candidate_version,
				channel.previous_manifest_sha256, channel.rollback_available,
				channel.last_operation_type, channel.last_operation_status,
				channel.requested_by, channel.approved_by, channel.reason,
				channel.idempotency_key, channel.updated_at
			FROM datapack_release_channels channel
			JOIN datapack_candidates candidate ON candidate.id = channel.candidate_id
			LEFT JOIN datapack_candidates previous_candidate
				ON previous_candidate.id = channel.previous_stable_candidate_id
			ORDER BY CASE channel.channel
				WHEN 'production' THEN 1
				WHEN 'staging' THEN 2
				WHEN 'dev' THEN 3
				ELSE 4
			END, channel.channel ASC
			""", this::mapChannel);
	}

	public List<ReleaseChannelEventRow> listRecentEvents(int limit) {
		return jdbcTemplate.query("""
			SELECT id, channel, previous_candidate_id, next_candidate_id,
				previous_manifest_sha256, next_manifest_sha256, operation_type,
				operation_status, requested_by, approved_by, reason,
				idempotency_key, workflow_run_url, created_at
			FROM datapack_release_channel_events
			ORDER BY created_at DESC, id ASC
			LIMIT ?
			""", this::mapEvent, limit);
	}

	private ReleaseChannelRow mapChannel(ResultSet resultSet, int rowNumber) throws SQLException {
		return new ReleaseChannelRow(
			resultSet.getString("channel"),
			resultSet.getString("candidate_id"),
			resultSet.getString("candidate_version"),
			resultSet.getString("manifest_url"),
			resultSet.getString("manifest_sha256"),
			resultSet.getString("previous_stable_candidate_id"),
			resultSet.getString("previous_stable_candidate_version"),
			resultSet.getString("previous_manifest_sha256"),
			resultSet.getBoolean("rollback_available"),
			resultSet.getString("last_operation_type"),
			resultSet.getString("last_operation_status"),
			resultSet.getString("requested_by"),
			resultSet.getString("approved_by"),
			resultSet.getString("reason"),
			resultSet.getString("idempotency_key"),
			resultSet.getTimestamp("updated_at").toLocalDateTime()
		);
	}

	private ReleaseChannelEventRow mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
		var workflowRunUrl = resultSet.getString("workflow_run_url");
		return new ReleaseChannelEventRow(
			resultSet.getString("id"),
			resultSet.getString("channel"),
			resultSet.getString("previous_candidate_id"),
			resultSet.getString("next_candidate_id"),
			resultSet.getString("previous_manifest_sha256"),
			resultSet.getString("next_manifest_sha256"),
			resultSet.getString("operation_type"),
			resultSet.getString("operation_status"),
			resultSet.getString("requested_by"),
			resultSet.getString("approved_by"),
			resultSet.getString("reason"),
			resultSet.getString("idempotency_key"),
			workflowRunUrl == null || workflowRunUrl.isBlank() ? "-" : workflowRunUrl,
			resultSet.getTimestamp("created_at").toLocalDateTime()
		);
	}

	public record ReleaseChannelRow(
		String channel,
		String candidateId,
		String candidateVersion,
		String manifestUrl,
		String manifestSha256,
		String previousStableCandidateId,
		String previousStableCandidateVersion,
		String previousManifestSha256,
		boolean rollbackAvailable,
		String lastOperationType,
		String lastOperationStatus,
		String requestedBy,
		String approvedBy,
		String reason,
		String idempotencyKey,
		LocalDateTime updatedAt
	) {
	}

	public record ReleaseChannelEventRow(
		String id,
		String channel,
		String previousCandidateId,
		String nextCandidateId,
		String previousManifestSha256,
		String nextManifestSha256,
		String operationType,
		String operationStatus,
		String requestedBy,
		String approvedBy,
		String reason,
		String idempotencyKey,
		String workflowRunUrl,
		LocalDateTime createdAt
	) {
	}
}
