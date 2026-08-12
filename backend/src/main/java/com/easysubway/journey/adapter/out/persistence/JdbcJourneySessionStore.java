package com.easysubway.journey.adapter.out.persistence;

import com.easysubway.journey.application.JourneySessionStore;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@Profile("prod | staging | release | prod-like")
public class JdbcJourneySessionStore implements JourneySessionStore {

	private final JdbcTemplate jdbcTemplate;
	private final TransactionTemplate transactions;

	@Autowired
	public JdbcJourneySessionStore(DataSource dataSource, PlatformTransactionManager transactionManager) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
		this.transactions = new TransactionTemplate(transactionManager);
	}

	JdbcJourneySessionStore(DataSource dataSource) {
		this(dataSource, new DataSourceTransactionManager(dataSource));
	}

	@Override
	public boolean claimNonceAndSaveSession(
		String nonceSha256,
		Instant nonceExpiresAt,
		Instant now,
		Session session
	) {
		Boolean claimed = transactions.execute(status -> {
			jdbcTemplate.update(
				"DELETE FROM journey_v3_nonce_claims WHERE expires_at <= ?",
				Timestamp.from(now)
			);
			jdbcTemplate.update(
				"DELETE FROM journey_v3_sessions WHERE expires_at <= ?",
				Timestamp.from(now)
			);
			if (!claimNonce(nonceSha256, nonceExpiresAt, now)) {
				return false;
			}
			jdbcTemplate.update(
				"INSERT INTO journey_v3_sessions (token_sha256, scope, issued_at, expires_at) VALUES (?, ?, ?, ?)",
				session.tokenSha256(),
				session.scope(),
				Timestamp.from(session.issuedAt()),
				Timestamp.from(session.expiresAt())
			);
			return true;
		});
		return Boolean.TRUE.equals(claimed);
	}

	private boolean claimNonce(String nonceSha256, Instant nonceExpiresAt, Instant now) {
		return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
			Savepoint savepoint = connection.setSavepoint();
			try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO journey_v3_nonce_claims (nonce_sha256, claimed_at, expires_at) VALUES (?, ?, ?)"
			)) {
				statement.setString(1, nonceSha256);
				statement.setTimestamp(2, Timestamp.from(now));
				statement.setTimestamp(3, Timestamp.from(nonceExpiresAt));
				boolean inserted = statement.executeUpdate() == 1;
				connection.releaseSavepoint(savepoint);
				return inserted;
			} catch (SQLException exception) {
				if (!"23505".equals(exception.getSQLState())) {
					throw exception;
				}
				connection.rollback(savepoint);
				connection.releaseSavepoint(savepoint);
				return false;
			}
		}));
	}

	@Override
	@Transactional
	public SessionUse authorizeAndConsume(
		String tokenSha256,
		String requiredScope,
		Instant now,
		int maxSearchesPerSession
	) {
		int consumed = jdbcTemplate.update(
			"""
				UPDATE journey_v3_sessions
				SET request_count = request_count + 1
				WHERE token_sha256 = ?
					AND scope = ?
					AND expires_at > ?
					AND request_count < ?
				""",
			tokenSha256,
			requiredScope,
			Timestamp.from(now),
			maxSearchesPerSession
		);
		List<SessionRow> sessions = jdbcTemplate.query(
			"SELECT scope, expires_at, request_count FROM journey_v3_sessions WHERE token_sha256 = ?",
			(resultSet, rowNumber) -> new SessionRow(
				resultSet.getString("scope"),
				resultSet.getTimestamp("expires_at").toInstant(),
				resultSet.getInt("request_count")
			),
			tokenSha256
		);
		if (sessions.isEmpty()) {
			return new SessionUse(AuthorizationStatus.MISSING, null, null);
		}
		SessionRow session = sessions.getFirst();
		if (!session.expiresAt().isAfter(now)) {
			return new SessionUse(AuthorizationStatus.EXPIRED, session.scope(), session.expiresAt());
		}
		if (!session.scope().equals(requiredScope)) {
			return new SessionUse(AuthorizationStatus.SCOPE_MISMATCH, session.scope(), session.expiresAt());
		}
		if (consumed != 1 || session.requestCount() > maxSearchesPerSession) {
			return new SessionUse(AuthorizationStatus.LIMITED, session.scope(), session.expiresAt());
		}
		return new SessionUse(AuthorizationStatus.VALID, session.scope(), session.expiresAt());
	}

	private record SessionRow(String scope, Instant expiresAt, int requestCount) {
	}
}
