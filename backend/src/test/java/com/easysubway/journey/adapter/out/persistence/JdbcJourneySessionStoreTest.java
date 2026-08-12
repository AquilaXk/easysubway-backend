package com.easysubway.journey.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.JourneySessionStore.AuthorizationStatus;
import com.easysubway.journey.application.JourneySessionStore.Session;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@DisplayName("Journey V3 JDBC session store")
class JdbcJourneySessionStoreTest {

	private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");
	private static final String TOKEN_HASH = "a".repeat(64);
	private static final String NONCE_HASH = "b".repeat(64);

	private JdbcJourneySessionStore store;
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:journey-v3-session;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS journey_v3_sessions");
		jdbcTemplate.execute("DROP TABLE IF EXISTS journey_v3_nonce_claims");
		new ResourceDatabasePopulator(new ClassPathResource(
			"db/migration/h2/V71__journey_v3_sessions.sql"
		)).execute(dataSource);
		store = new JdbcJourneySessionStore(dataSource);
	}

	@Test
	@DisplayName("nonce claim과 hashed session 저장은 원자적이고 duplicate nonce는 false다")
	void claimsNonceAndStoresSessionAtomically() {
		String expiredNonceHash = "ab".repeat(32);
		String liveNonceHash = "bc".repeat(32);
		String expiredTokenHash = "cd".repeat(32);
		String liveTokenHash = "de".repeat(32);
		jdbcTemplate.update(
			"INSERT INTO journey_v3_nonce_claims (nonce_sha256, claimed_at, expires_at) VALUES (?, ?, ?)",
			expiredNonceHash, NOW.minusSeconds(120), NOW
		);
		jdbcTemplate.update(
			"INSERT INTO journey_v3_nonce_claims (nonce_sha256, claimed_at, expires_at) VALUES (?, ?, ?)",
			liveNonceHash, NOW, NOW.plusSeconds(300)
		);
		jdbcTemplate.update(
			"INSERT INTO journey_v3_sessions (token_sha256, scope, issued_at, expires_at) VALUES (?, ?, ?, ?)",
			expiredTokenHash, "journey:v3", NOW.minusSeconds(600), NOW
		);
		jdbcTemplate.update(
			"INSERT INTO journey_v3_sessions (token_sha256, scope, issued_at, expires_at) VALUES (?, ?, ?, ?)",
			liveTokenHash, "journey:v3", NOW, NOW.plusSeconds(600)
		);
		var session = new Session(TOKEN_HASH, "journey:v3", NOW, NOW.plusSeconds(600));

		assertThat(store.claimNonceAndSaveSession(NONCE_HASH, NOW.plusSeconds(120), NOW, session)).isTrue();
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM journey_v3_nonce_claims WHERE nonce_sha256 = ?",
			Integer.class,
			expiredNonceHash
		)).isZero();
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM journey_v3_sessions WHERE token_sha256 = ?",
			Integer.class,
			expiredTokenHash
		)).isZero();
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM journey_v3_nonce_claims WHERE nonce_sha256 = ?",
			Integer.class,
			liveNonceHash
		)).isOne();
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM journey_v3_sessions WHERE token_sha256 = ?",
			Integer.class,
			liveTokenHash
		)).isOne();
		assertThat(store.claimNonceAndSaveSession(NONCE_HASH, NOW.plusSeconds(121), NOW.plusSeconds(1),
			new Session("c".repeat(64), "journey:v3", NOW.plusSeconds(1), NOW.plusSeconds(601)))).isFalse();
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM journey_v3_sessions", Integer.class)).isEqualTo(2);

		assertThatThrownBy(() -> store.claimNonceAndSaveSession(
			"d".repeat(64),
			NOW.plusSeconds(122),
			NOW.plusSeconds(2),
			session
		)).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM journey_v3_nonce_claims WHERE nonce_sha256 = ?",
			Integer.class,
			"d".repeat(64)
		)).isZero();
	}

	@Test
	@DisplayName("authorize는 네 상태만 반환하고 schema에는 hash identity만 저장한다")
	void authorizesClosedStatusesWithHashedOnlySchema() {
		store.claimNonceAndSaveSession(
			NONCE_HASH,
			NOW.plusSeconds(120),
			NOW,
			new Session(TOKEN_HASH, "journey:v3", NOW, NOW.plusSeconds(600))
		);
		jdbcTemplate.update(
			"INSERT INTO journey_v3_sessions (token_sha256, scope, issued_at, expires_at) VALUES (?, ?, ?, ?)",
			"e".repeat(64), "journey:v3", NOW.minusSeconds(601), NOW
		);

		assertThat(store.authorize(TOKEN_HASH, "journey:v3", NOW).status()).isEqualTo(AuthorizationStatus.VALID);
		assertThat(store.authorize(TOKEN_HASH, "other", NOW).status())
			.isEqualTo(AuthorizationStatus.SCOPE_MISMATCH);
		assertThat(store.authorize("e".repeat(64), "journey:v3", NOW).status())
			.isEqualTo(AuthorizationStatus.EXPIRED);
		assertThat(store.authorize("f".repeat(64), "journey:v3", NOW).status())
			.isEqualTo(AuthorizationStatus.MISSING);

		List<String> columns = jdbcTemplate.queryForList(
			"SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
				+ "WHERE TABLE_NAME IN ('JOURNEY_V3_NONCE_CLAIMS', 'JOURNEY_V3_SESSIONS')",
			String.class
		);
		assertThat(columns).containsExactlyInAnyOrder(
			"NONCE_SHA256", "CLAIMED_AT", "EXPIRES_AT",
			"TOKEN_SHA256", "SCOPE", "ISSUED_AT", "EXPIRES_AT"
		);
	}

	@Test
	@DisplayName("production repository는 Spring persistence advisor가 proxy할 수 있다")
	void remainsProxyableInProductionProfiles() {
		assertThat(Modifier.isFinal(JdbcJourneySessionStore.class.getModifiers())).isFalse();
	}
}
