package com.easysubway.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.auth.adapter.out.redis.RedisAnonymousAuthRateLimitAdapter;
import com.easysubway.auth.application.service.AnonymousAuthTokenHasher;
import com.easysubway.auth.domain.AnonymousUserCredentials;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DisplayName("익명 인증 PostgreSQL/Redis 통합 검증")
class AnonymousAuthInfrastructureContainerTest {

	@Container
	private static final PostgreSQLContainer<?> POSTGRES =
		new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

	@Container
	private static final GenericContainer<?> REDIS =
		new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

	@Test
	@DisplayName("PostgreSQL guest 계정과 token hash는 재시작 후에도 검증되고 Redis rate limit은 TTL 카운터를 공유한다")
	void postgresBackedTokenSessionAndRedisRateLimit() {
		var jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(),
			POSTGRES.getPassword()
		));
		new AnonymousAuthSchemaInitializer(jdbcTemplate).initialize();
		var repository = new JdbcAnonymousAuthRepository(jdbcTemplate);
		var issuedAt = LocalDateTime.of(2026, 6, 19, 10, 0);
		String accessTokenHash = AnonymousAuthTokenHasher.sha256("container-access-token");
		String refreshTokenHash = AnonymousAuthTokenHasher.sha256("container-refresh-token");

		repository.registerAnonymousUser(new AnonymousUserCredentials(
			"anonymous-container-user",
			"one-time-password-not-persisted",
			issuedAt
		));
		repository.saveIssuedTokenHashes(
			"anonymous-container-user",
			accessTokenHash,
			refreshTokenHash,
			issuedAt
		);
		var restartedRepository = new JdbcAnonymousAuthRepository(jdbcTemplate);

		assertThat(restartedRepository.findUserIdByAccessTokenHash(accessTokenHash))
			.contains("anonymous-container-user");
		assertThat(restartedRepository.consumeRefreshTokenHash(refreshTokenHash, issuedAt.plusMinutes(1)))
			.contains("anonymous-container-user");
		assertThat(restartedRepository.consumeRefreshTokenHash(refreshTokenHash, issuedAt.plusMinutes(2)))
			.isEmpty();

		var connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
		connectionFactory.afterPropertiesSet();
		try {
			var redisTemplate = new StringRedisTemplate(connectionFactory);
			redisTemplate.afterPropertiesSet();
			var rateLimitAdapter = new RedisAnonymousAuthRateLimitAdapter(redisTemplate);

			assertThat(rateLimitAdapter.consume("container-client", Duration.ofMinutes(10))).isEqualTo(1L);
			assertThat(rateLimitAdapter.consume("container-client", Duration.ofMinutes(10))).isEqualTo(2L);
		} finally {
			connectionFactory.destroy();
		}
	}
}
