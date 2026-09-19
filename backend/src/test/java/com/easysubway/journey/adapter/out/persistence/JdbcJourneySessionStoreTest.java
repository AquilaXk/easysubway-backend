package com.easysubway.journey.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.JourneySessionStore.AuthorizationStatus;
import com.easysubway.journey.application.JourneySessionStore.Session;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
		new ResourceDatabasePopulator(
			new ClassPathResource("db/migration/h2/V71__journey_v3_sessions.sql"),
			new ClassPathResource("db/migration/h2/V72__journey_v3_session_request_limit.sql")
		).execute(dataSource);
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
	@DisplayName("authorizeAndConsume은 closed status를 반환하고 valid count만 원자적으로 소비한다")
	void authorizesAndConsumesClosedStatusesAtomically() {
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

		assertThat(store.authorizeAndConsume(TOKEN_HASH, "journey:v3", NOW, 1, 2).status())
			.isEqualTo(AuthorizationStatus.VALID);
		assertThat(store.authorizeAndConsume(TOKEN_HASH, "journey:v3", NOW, 1, 2).status())
			.isEqualTo(AuthorizationStatus.VALID);
		assertThat(store.authorizeAndConsume(TOKEN_HASH, "journey:v3", NOW, 1, 2).status())
			.isEqualTo(AuthorizationStatus.LIMITED);
		assertThat(store.authorizeAndConsume(TOKEN_HASH, "other", NOW, 1, 2).status())
			.isEqualTo(AuthorizationStatus.SCOPE_MISMATCH);
		assertThat(store.authorizeAndConsume("e".repeat(64), "journey:v3", NOW, 1, 2).status())
			.isEqualTo(AuthorizationStatus.EXPIRED);
		assertThat(store.authorizeAndConsume("f".repeat(64), "journey:v3", NOW, 1, 2).status())
			.isEqualTo(AuthorizationStatus.MISSING);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT request_count FROM journey_v3_sessions WHERE token_sha256 = ?",
			Integer.class,
			TOKEN_HASH
		)).isEqualTo(2);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT request_count FROM journey_v3_sessions WHERE token_sha256 = ?",
			Integer.class,
			"e".repeat(64)
		)).isZero();

		List<String> columns = jdbcTemplate.queryForList(
			"SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
				+ "WHERE TABLE_NAME IN ('JOURNEY_V3_NONCE_CLAIMS', 'JOURNEY_V3_SESSIONS')",
			String.class
		);
		assertThat(columns).containsExactlyInAnyOrder(
			"NONCE_SHA256", "CLAIMED_AT", "EXPIRES_AT",
			"TOKEN_SHA256", "SCOPE", "ISSUED_AT", "EXPIRES_AT", "REQUEST_COUNT"
		);
	}

	@Test
	@DisplayName("동시 max=1 consume은 정확히 한 요청만 승인한다")
	void concurrentConsumeAdmitsExactlyOneRequest() throws Exception {
		store.claimNonceAndSaveSession(
			NONCE_HASH,
			NOW.plusSeconds(120),
			NOW,
			new Session(TOKEN_HASH, "journey:v3", NOW, NOW.plusSeconds(600))
		);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var calls = List.of(
				executor.submit(() -> {
					start.await();
					return store.authorizeAndConsume(TOKEN_HASH, "journey:v3", NOW, 2, 3).status();
				}),
				executor.submit(() -> {
					start.await();
					return store.authorizeAndConsume(TOKEN_HASH, "journey:v3", NOW, 2, 3).status();
				})
			);
			start.countDown();
			assertThat(List.of(calls.get(0).get(), calls.get(1).get()))
				.containsExactlyInAnyOrder(AuthorizationStatus.VALID, AuthorizationStatus.LIMITED);
		}
		assertThat(jdbcTemplate.queryForObject(
			"SELECT request_count FROM journey_v3_sessions WHERE token_sha256 = ?",
			Integer.class,
			TOKEN_HASH
		)).isEqualTo(2);
	}

	@Test
	@DisplayName("weighted consume은 exact-unit boundary를 지키고 over-limit을 소비하지 않는다")
	void weightedConsumeUsesExactUnitsWithoutPartialConsumption() {
		store.claimNonceAndSaveSession(
			NONCE_HASH,
			NOW.plusSeconds(120),
			NOW,
			new Session(TOKEN_HASH, "journey:v3", NOW, NOW.plusSeconds(600))
		);

		assertThat(store.authorizeAndConsume(TOKEN_HASH, "journey:v3", NOW, 2, 5).status())
			.isEqualTo(AuthorizationStatus.VALID);
		assertThat(store.authorizeAndConsume(TOKEN_HASH, "journey:v3", NOW, 3, 5).status())
			.isEqualTo(AuthorizationStatus.VALID);
		assertThat(store.authorizeAndConsume(TOKEN_HASH, "journey:v3", NOW, 1, 5).status())
			.isEqualTo(AuthorizationStatus.LIMITED);
		assertThat(store.authorizeAndConsume(TOKEN_HASH, "journey:v3", NOW, 6, 5).status())
			.isEqualTo(AuthorizationStatus.LIMITED);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT request_count FROM journey_v3_sessions WHERE token_sha256 = ?",
			Integer.class,
			TOKEN_HASH
		)).isEqualTo(5);
		assertThatThrownBy(() -> store.authorizeAndConsume(TOKEN_HASH, "journey:v3", NOW, 0, 5))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("costUnits must be positive");
		assertThatThrownBy(() -> store.authorizeAndConsume(TOKEN_HASH, "journey:v3", NOW, 1, 0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("maxCostUnitsPerSession must be positive");
	}

	@Test
	@DisplayName("production repository는 Spring persistence advisor가 proxy할 수 있다")
	void remainsProxyableInProductionProfiles() {
		assertThat(Modifier.isFinal(JdbcJourneySessionStore.class.getModifiers())).isFalse();
	}
}
