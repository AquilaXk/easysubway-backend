package com.easysubway.auth.adapter.out.persistence;

import com.easysubway.auth.application.port.out.AnonymousAuthTokenPort;
import com.easysubway.auth.application.port.out.RegisterAnonymousUserPort;
import com.easysubway.auth.domain.AnonymousUserCredentials;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAnonymousAuthRepository implements RegisterAnonymousUserPort, AnonymousAuthTokenPort {

	private final JdbcTemplate jdbcTemplate;

	public JdbcAnonymousAuthRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public boolean existsByUserId(String userId) {
		Integer count = jdbcTemplate.queryForObject(
			"""
			SELECT COUNT(*)
			FROM guest_accounts
			WHERE user_id = ?
				AND revoked_at IS NULL
			""",
			Integer.class,
			userId
		);
		return count != null && count > 0;
	}

	@Override
	public boolean isAnonymousUser(String userId) {
		return existsByUserId(userId);
	}

	@Override
	public void registerAnonymousUser(AnonymousUserCredentials credentials) {
		if (hasGuestAccount(credentials.userId())) {
			jdbcTemplate.update(
				"""
				UPDATE guest_accounts
				SET created_at = ?,
					revoked_at = NULL
				WHERE user_id = ?
				""",
				Timestamp.valueOf(credentials.createdAt()),
				credentials.userId()
			);
			return;
		}

		jdbcTemplate.update(
			"""
			INSERT INTO guest_accounts (
				user_id,
				created_at,
				revoked_at
			)
			VALUES (?, ?, NULL)
			""",
			credentials.userId(),
			Timestamp.valueOf(credentials.createdAt())
		);
	}

	@Override
	public boolean deleteAnonymousUser(String userId) {
		jdbcTemplate.update("DELETE FROM anonymous_auth_tokens WHERE user_id = ?", userId);
		int updated = jdbcTemplate.update(
			"""
			UPDATE guest_accounts
			SET revoked_at = ?
			WHERE user_id = ?
				AND revoked_at IS NULL
			""",
			Timestamp.valueOf(LocalDateTime.now()),
			userId
		);
		return updated > 0;
	}

	@Override
	public void saveIssuedTokenHashes(
		String userId,
		String accessTokenHash,
		String refreshTokenHash,
		LocalDateTime issuedAt
	) {
		insertTokenHash(userId, accessTokenHash, "ACCESS", issuedAt);
		insertTokenHash(userId, refreshTokenHash, "REFRESH", issuedAt);
	}

	@Override
	public Optional<String> findUserIdByAccessTokenHash(String accessTokenHash) {
		var userIds = jdbcTemplate.queryForList(
			"""
			SELECT tokens.user_id
			FROM anonymous_auth_tokens tokens
			JOIN guest_accounts accounts ON accounts.user_id = tokens.user_id
			WHERE tokens.token_hash = ?
				AND tokens.token_type = 'ACCESS'
				AND tokens.revoked_at IS NULL
				AND accounts.revoked_at IS NULL
			""",
			String.class,
			accessTokenHash
		);
		return userIds.stream().findFirst();
	}

	@Override
	public Optional<String> consumeRefreshTokenHash(String refreshTokenHash, LocalDateTime consumedAt) {
		var userIds = jdbcTemplate.queryForList(
			"""
			SELECT tokens.user_id
			FROM anonymous_auth_tokens tokens
			JOIN guest_accounts accounts ON accounts.user_id = tokens.user_id
			WHERE tokens.token_hash = ?
				AND tokens.token_type = 'REFRESH'
				AND tokens.revoked_at IS NULL
				AND accounts.revoked_at IS NULL
			""",
			String.class,
			refreshTokenHash
		);
		Optional<String> userId = userIds.stream().findFirst();
		if (userId.isEmpty()) {
			return Optional.empty();
		}

		int updated = jdbcTemplate.update(
			"""
			UPDATE anonymous_auth_tokens
			SET revoked_at = ?
			WHERE token_hash = ?
				AND token_type = 'REFRESH'
				AND revoked_at IS NULL
			""",
			Timestamp.valueOf(consumedAt),
			refreshTokenHash
		);
		return updated > 0 ? userId : Optional.empty();
	}

	@Override
	public void saveAuditEvent(String eventType, String userId, LocalDateTime occurredAt) {
		jdbcTemplate.update(
			"""
			INSERT INTO anonymous_auth_audit_events (
				event_type,
				user_id,
				occurred_at
			)
			VALUES (?, ?, ?)
			""",
			eventType,
			userId,
			Timestamp.valueOf(occurredAt)
		);
	}

	@Override
	public void deleteTokenHashesByUserId(String userId) {
		jdbcTemplate.update("DELETE FROM anonymous_auth_tokens WHERE user_id = ?", userId);
	}

	private boolean hasGuestAccount(String userId) {
		Integer count = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM guest_accounts WHERE user_id = ?",
			Integer.class,
			userId
		);
		return count != null && count > 0;
	}

	private void insertTokenHash(String userId, String tokenHash, String tokenType, LocalDateTime issuedAt) {
		jdbcTemplate.update(
			"""
			INSERT INTO anonymous_auth_tokens (
				token_hash,
				user_id,
				token_type,
				issued_at,
				revoked_at
			)
			VALUES (?, ?, ?, ?, NULL)
			""",
			tokenHash,
			userId,
			tokenType,
			Timestamp.valueOf(issuedAt)
		);
	}
}
