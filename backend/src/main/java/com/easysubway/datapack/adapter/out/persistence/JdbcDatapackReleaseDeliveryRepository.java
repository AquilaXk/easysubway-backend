package com.easysubway.datapack.adapter.out.persistence;

import com.easysubway.datapack.application.port.out.DatapackReleaseDeliveryRepository;
import com.easysubway.datapack.domain.DatapackReleaseDelivery;
import com.easysubway.datapack.domain.DatapackReleaseDelivery.State;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcDatapackReleaseDeliveryRepository implements DatapackReleaseDeliveryRepository {
	private static final Duration CLAIM_LEASE = Duration.ofMinutes(5);
	private static final RowMapper<DatapackReleaseDelivery> ROW_MAPPER = (rs, rowNum) ->
		new DatapackReleaseDelivery(
			rs.getString("idempotency_key"), rs.getString("release_request_id"),
			rs.getLong("release_sequence"), rs.getString("manifest_sha256"),
			rs.getString("channel"), rs.getString("candidate_id"),
			rs.getString("payload_sha256"), rs.getString("signature_sha256"),
			State.valueOf(rs.getString("state")), rs.getInt("attempts"),
			ldt(rs.getTimestamp("next_attempt_at")), ldt(rs.getTimestamp("reconcile_deadline")),
			ldt(rs.getTimestamp("dead_letter_deadline")), rs.getString("http_class"),
			rs.getString("sanitized_detail"), ldt(rs.getTimestamp("claimed_at")),
			rs.getString("claim_owner"), ldt(rs.getTimestamp("created_at")),
			ldt(rs.getTimestamp("updated_at")));

	private final JdbcTemplate jdbcTemplate;
	private final boolean postgresql;

	public JdbcDatapackReleaseDeliveryRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
		this.postgresql = Boolean.TRUE.equals(jdbcTemplate.execute(
			(ConnectionCallback<Boolean>) connection ->
				"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())));
	}

	public DatapackReleaseDelivery upsertSameDelivery(DatapackReleaseDelivery delivery) {
		if (postgresql) {
			if (insertPostgresql(delivery) == 1) return delivery;
			var existing = findByIdempotencyKey(delivery.idempotencyKey())
				.orElseThrow(() -> new DuplicateKeyException(
					"release request/sequence already belongs to another manifest"));
			return sameOrRefresh(existing, delivery);
		}
		try {
			insert(delivery);
			return delivery;
		} catch (DuplicateKeyException duplicate) {
			var existing = findByIdempotencyKey(delivery.idempotencyKey()).orElseThrow(() -> duplicate);
			return sameOrRefresh(existing, delivery);
		}
	}

	private DatapackReleaseDelivery sameOrRefresh(
		DatapackReleaseDelivery existing,
		DatapackReleaseDelivery incoming
	) {
		boolean sameIdentity = existing.releaseRequestId().equals(incoming.releaseRequestId())
			&& existing.releaseSequence() == incoming.releaseSequence()
			&& existing.manifestSha256().equals(incoming.manifestSha256())
			&& existing.channel().equals(incoming.channel())
			&& existing.candidateId().equals(incoming.candidateId());
		if (!sameIdentity) {
			throw new DuplicateKeyException("idempotency key already belongs to another callback payload");
		}
		if (incoming.payloadSha256() == null
			|| (incoming.payloadSha256().equals(existing.payloadSha256())
				&& incoming.signatureSha256().equals(existing.signatureSha256()))) return existing;
		if (existing.payloadSha256() != null && existing.state() != State.RECONCILIATION_REQUIRED) {
			throw new DuplicateKeyException("idempotency key already belongs to another callback payload");
		}
		int changed = jdbcTemplate.update("""
			UPDATE datapack_release_deliveries
			SET payload_sha256=?, signature_sha256=?, updated_at=?
			WHERE idempotency_key=?
			  AND (payload_sha256 IS NULL OR state='RECONCILIATION_REQUIRED')
			""", incoming.payloadSha256(), incoming.signatureSha256(), ts(incoming.updatedAt()),
			incoming.idempotencyKey());
		var refreshed = findByIdempotencyKey(incoming.idempotencyKey())
			.orElseThrow(() -> new DuplicateKeyException("callback delivery disappeared during refresh"));
		if (changed != 1 && (!incoming.payloadSha256().equals(refreshed.payloadSha256())
			|| !incoming.signatureSha256().equals(refreshed.signatureSha256()))) {
			throw new DuplicateKeyException("idempotency key already belongs to another callback payload");
		}
		return refreshed;
	}

	private int insertPostgresql(DatapackReleaseDelivery d) {
		return jdbcTemplate.update("""
			INSERT INTO datapack_release_deliveries (
			 idempotency_key, release_request_id, release_sequence, manifest_sha256, channel,
			 candidate_id, payload_sha256, signature_sha256, state, attempts, next_attempt_at,
			 reconcile_deadline, dead_letter_deadline, http_class, sanitized_detail,
			 claimed_at, claim_owner, created_at, updated_at)
			VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
			ON CONFLICT DO NOTHING
			""", d.idempotencyKey(), d.releaseRequestId(), d.releaseSequence(), d.manifestSha256(),
			d.channel(), d.candidateId(), d.payloadSha256(), d.signatureSha256(), d.state().name(),
			d.attempts(), ts(d.nextAttemptAt()), ts(d.reconcileDeadline()), ts(d.deadLetterDeadline()),
			d.httpClass(), d.sanitizedDetail(), ts(d.claimedAt()), d.claimOwner(),
			ts(d.createdAt()), ts(d.updatedAt()));
	}

	public Optional<DatapackReleaseDelivery> findByIdempotencyKey(String idempotencyKey) {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject(
				"SELECT * FROM datapack_release_deliveries WHERE idempotency_key=?",
				ROW_MAPPER, idempotencyKey));
		} catch (EmptyResultDataAccessException ignored) {
			return Optional.empty();
		}
	}

	public Optional<DatapackReleaseDelivery> findByRequestAndSequence(String requestId, long sequence) {
		return jdbcTemplate.query(
			"SELECT * FROM datapack_release_deliveries WHERE release_request_id=? AND release_sequence=?",
			ROW_MAPPER, requestId, sequence).stream().findFirst();
	}

	public List<DatapackReleaseDelivery> claimDue(LocalDateTime now, String owner, int limit) {
		if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
		var reclaimBefore = now.minus(CLAIM_LEASE);
		var keys = jdbcTemplate.queryForList("""
			SELECT idempotency_key FROM datapack_release_deliveries
			WHERE state IN ('PENDING', 'RETRY_SCHEDULED', 'RECONCILIATION_REQUIRED')
			  AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
			  AND (claimed_at IS NULL OR claimed_at <= ?)
			ORDER BY created_at, idempotency_key
			LIMIT ?
			""", String.class, ts(now), ts(reclaimBefore), limit);
		var claimed = new ArrayList<DatapackReleaseDelivery>();
		for (String key : keys) {
			int changed = claimKeyIfDue(now, owner, reclaimBefore, key);
			if (changed == 1) findByIdempotencyKey(key).ifPresent(claimed::add);
		}
		return claimed;
	}

	int claimKeyIfDue(LocalDateTime now, String owner, LocalDateTime reclaimBefore, String key) {
		return jdbcTemplate.update("""
			UPDATE datapack_release_deliveries SET claimed_at=?, claim_owner=?, updated_at=?
			WHERE idempotency_key=? AND (claimed_at IS NULL OR claimed_at <= ?)
			  AND state IN ('PENDING', 'RETRY_SCHEDULED', 'RECONCILIATION_REQUIRED')
			  AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
			""", ts(now), owner, ts(now), key, ts(reclaimBefore), ts(now));
	}

	public void mark(String idempotencyKey, State state, int attempts, LocalDateTime nextAttemptAt,
		String httpClass, String detail, LocalDateTime now) {
		jdbcTemplate.update("""
			UPDATE datapack_release_deliveries
			SET state=?, attempts=?, next_attempt_at=?, http_class=?, sanitized_detail=?,
			    claimed_at=NULL, claim_owner=NULL, updated_at=?
			WHERE idempotency_key=?
			""", state.name(), attempts, ts(nextAttemptAt), httpClass, detail, ts(now), idempotencyKey);
	}

	@Override
	public void markClaimed(String idempotencyKey, String owner, State state, int attempts,
		LocalDateTime nextAttemptAt, String httpClass, String detail, LocalDateTime now) {
		int changed = jdbcTemplate.update("""
			UPDATE datapack_release_deliveries
			SET state=?, attempts=?, next_attempt_at=?, http_class=?, sanitized_detail=?,
			    claimed_at=NULL, claim_owner=NULL, updated_at=?
			WHERE idempotency_key=? AND claim_owner=?
			""", state.name(), attempts, ts(nextAttemptAt), httpClass, detail, ts(now),
			idempotencyKey, owner);
		if (changed != 1) throw new IllegalStateException("delivery claim is no longer owned");
	}

	@Override
	@Transactional
	public ManualRepair scheduleManualRepair(String idempotencyKey, LocalDateTime now) {
		var delivery = findByIdempotencyKey(idempotencyKey)
			.orElseThrow(() -> new IllegalArgumentException("delivery not found"));
		var before = delivery.state();
		if (before != State.RECONCILIATION_REQUIRED && before != State.DEAD_LETTER) {
			throw new IllegalStateException("delivery state is not repairable: " + before);
		}
		int changed = jdbcTemplate.update("""
			UPDATE datapack_release_deliveries
			SET state='RETRY_SCHEDULED', next_attempt_at=?, claimed_at=NULL, claim_owner=NULL, updated_at=?
			WHERE idempotency_key=? AND state=?
			""", ts(now), ts(now), idempotencyKey, before.name());
		if (changed != 1) {
			throw new IllegalStateException("delivery changed while manual repair was scheduled");
		}
		return new ManualRepair(before, findByIdempotencyKey(idempotencyKey)
			.orElseThrow(() -> new IllegalStateException("delivery disappeared after manual repair")));
	}

	public List<DatapackReleaseDelivery> findRecent(int limit) {
		return jdbcTemplate.query("""
			SELECT * FROM datapack_release_deliveries
			ORDER BY created_at DESC, idempotency_key DESC LIMIT ?
			""", ROW_MAPPER, limit);
	}

	private void insert(DatapackReleaseDelivery d) {
		jdbcTemplate.update("""
			INSERT INTO datapack_release_deliveries (
			 idempotency_key, release_request_id, release_sequence, manifest_sha256, channel,
			 candidate_id, payload_sha256, signature_sha256, state, attempts, next_attempt_at,
			 reconcile_deadline, dead_letter_deadline, http_class, sanitized_detail,
			 claimed_at, claim_owner, created_at, updated_at)
			VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
			""", d.idempotencyKey(), d.releaseRequestId(), d.releaseSequence(), d.manifestSha256(),
			d.channel(), d.candidateId(), d.payloadSha256(), d.signatureSha256(), d.state().name(),
			d.attempts(), ts(d.nextAttemptAt()), ts(d.reconcileDeadline()), ts(d.deadLetterDeadline()),
			d.httpClass(), d.sanitizedDetail(), ts(d.claimedAt()), d.claimOwner(),
			ts(d.createdAt()), ts(d.updatedAt()));
	}

	private static Timestamp ts(LocalDateTime value) {
		return value == null ? null : Timestamp.valueOf(value);
	}

	private static LocalDateTime ldt(Timestamp value) {
		return value == null ? null : value.toLocalDateTime();
	}
}
