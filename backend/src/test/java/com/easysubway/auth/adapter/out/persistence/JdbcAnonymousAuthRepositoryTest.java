package com.easysubway.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.auth.application.service.AnonymousAuthTokenHasher;
import com.easysubway.auth.domain.AnonymousUserCredentials;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("JDBC 익명 인증 저장소")
class JdbcAnonymousAuthRepositoryTest {

	private JdbcTemplate jdbcTemplate;
	private JdbcAnonymousAuthRepository repository;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:anonymous-auth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS anonymous_auth_tokens");
		jdbcTemplate.execute("DROP TABLE IF EXISTS guest_accounts");
		jdbcTemplate.execute("""
			CREATE TABLE guest_accounts (
				user_id VARCHAR(120) NOT NULL PRIMARY KEY,
				created_at TIMESTAMP NOT NULL,
				revoked_at TIMESTAMP
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE anonymous_auth_tokens (
				token_hash VARCHAR(64) NOT NULL PRIMARY KEY,
				user_id VARCHAR(120) NOT NULL,
				token_type VARCHAR(20) NOT NULL,
				issued_at TIMESTAMP NOT NULL,
				revoked_at TIMESTAMP,
				CONSTRAINT fk_anonymous_auth_tokens_user
					FOREIGN KEY (user_id) REFERENCES guest_accounts(user_id)
					ON DELETE CASCADE,
				CONSTRAINT chk_anonymous_auth_tokens_type
					CHECK (token_type IN ('ACCESS', 'REFRESH'))
			)
			""");
		repository = new JdbcAnonymousAuthRepository(jdbcTemplate);
	}

	@Test
	@DisplayName("guest account와 token hash를 저장하고 새 인스턴스에서도 access token으로 사용자를 찾는다")
	void saveGuestAccountAndTokenHashes() {
		String accessToken = "access-token-raw";
		String refreshToken = "refresh-token-raw";
		String accessTokenHash = AnonymousAuthTokenHasher.sha256(accessToken);
		String refreshTokenHash = AnonymousAuthTokenHasher.sha256(refreshToken);
		var createdAt = LocalDateTime.of(2026, 6, 19, 9, 0);

		repository.registerAnonymousUser(new AnonymousUserCredentials(
			"anonymous-user-1",
			"one-time-basic-password-not-persisted",
			createdAt
		));
		repository.saveIssuedTokenHashes("anonymous-user-1", accessTokenHash, refreshTokenHash, createdAt);
		var restartedRepository = new JdbcAnonymousAuthRepository(jdbcTemplate);

		assertThat(restartedRepository.existsByUserId("anonymous-user-1")).isTrue();
		assertThat(restartedRepository.isAnonymousUser("anonymous-user-1")).isTrue();
		assertThat(restartedRepository.findUserIdByAccessTokenHash(accessTokenHash))
			.contains("anonymous-user-1");
		assertThat(storedTokenHashes()).containsExactlyInAnyOrder(accessTokenHash, refreshTokenHash);
		assertThat(storedTokenHashes()).doesNotContain(accessToken, refreshToken);
	}

	@Test
	@DisplayName("guest account 삭제는 access token 인증도 함께 무효화한다")
	void deleteGuestAccountRevokesTokenHashes() {
		String accessTokenHash = AnonymousAuthTokenHasher.sha256("access-token-raw");
		var createdAt = LocalDateTime.of(2026, 6, 19, 9, 0);
		repository.registerAnonymousUser(new AnonymousUserCredentials("anonymous-user-1", "password", createdAt));
		repository.saveIssuedTokenHashes(
			"anonymous-user-1",
			accessTokenHash,
			AnonymousAuthTokenHasher.sha256("refresh-token-raw"),
			createdAt
		);

		boolean deleted = repository.deleteAnonymousUser("anonymous-user-1");

		assertThat(deleted).isTrue();
		assertThat(repository.isAnonymousUser("anonymous-user-1")).isFalse();
		assertThat(repository.findUserIdByAccessTokenHash(accessTokenHash)).isEmpty();
	}

	private java.util.List<String> storedTokenHashes() {
		return jdbcTemplate.queryForList("SELECT token_hash FROM anonymous_auth_tokens", String.class);
	}
}
