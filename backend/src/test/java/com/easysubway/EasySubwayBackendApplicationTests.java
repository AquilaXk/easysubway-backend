package com.easysubway;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@DisplayName("백엔드 애플리케이션 컨텍스트")
class EasySubwayBackendApplicationTests {

	@Test
	@DisplayName("스프링 부트 애플리케이션 컨텍스트가 정상 로드된다")
	void contextLoads() {
	}

	@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
			"spring.datasource.url=jdbc:h2:mem:prod-readiness-context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
			"spring.datasource.username=sa",
			"spring.datasource.password=",
			"spring.datasource.driver-class-name=org.h2.Driver",
			"spring.sql.init.mode=always",
			"spring.sql.init.continue-on-error=true",
			"spring.sql.init.schema-locations=classpath:db/batch/schema-postgresql.sql",
			"spring.batch.jdbc.initialize-schema=never",
			"easysubway.admin.username=admin-user",
			"easysubway.admin.password=admin-password",
			"easysubway.auth.client-ip.trusted-proxies=",
			"easysubway.notifications.push.external-enabled=false",
			"management.endpoint.health.show-details=always"
		}
	)
	@ActiveProfiles("prod")
	@AutoConfigureMockMvc
	@DisplayName("운영 프로필 애플리케이션 컨텍스트")
	static class ProductionProfileContextTests {

		@Autowired
		private MockMvc mockMvc;

		@Test
		@DisplayName("운영 프로필은 readiness DOWN 응답까지 기동된다")
		void prodProfileStartsUntilReadinessEndpoint() throws Exception {
			mockMvc.perform(get("/actuator/health/readiness"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value("DOWN"))
				.andExpect(jsonPath("$.components.productionReadiness.status").value("DOWN"));
		}

		@TestConfiguration
		static class RedisTestConfiguration {

			@Bean
			@Primary
			RedisConnectionFactory redisConnectionFactory() {
				RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
				RedisConnection connection = mock(RedisConnection.class);
				when(factory.getConnection()).thenReturn(connection);
				when(connection.ping()).thenReturn("PONG");
				return factory;
			}
		}
	}
}
