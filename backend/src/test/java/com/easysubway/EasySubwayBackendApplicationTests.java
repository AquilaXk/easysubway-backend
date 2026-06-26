package com.easysubway;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
			"spring.flyway.locations=classpath:db/migration/h2",
			"spring.sql.init.mode=always",
			"spring.sql.init.continue-on-error=true",
			"spring.batch.jdbc.initialize-schema=never",
			"easysubway.admin.username=admin-user",
			"easysubway.admin.password=admin-password",
			"easysubway.auth.client-ip.trusted-proxies=",
			"easysubway.notifications.push.external-enabled=false",
			"easysubway.report.receipt-token-pepper=prod-test-receipt-token-pepper-with-enough-entropy",
			"easysubway.report.upload.intent-signing-key=prod-test-upload-intent-signing-key-with-enough-entropy",
			"easysubway.report.upload.object-storage-endpoint=https://object-storage.example.com",
			"easysubway.report.upload.public-base-url=https://uploads.easysubway.example",
			"easysubway.report.upload.bucket=easysubway-report-uploads",
			"easysubway.report.upload.object-storage-access-key=prod-object-storage-access-key",
			"easysubway.report.upload.object-storage-secret-key=prod-object-storage-secret-key-with-enough-entropy",
			"easysubway.report.upload.object-storage-region=ap-northeast-2",
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
		@DisplayName("운영 프로필은 readiness UP 응답까지 기동된다")
		void prodProfileStartsUntilReadinessEndpoint() throws Exception {
			mockMvc.perform(get("/actuator/health/readiness"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.components.productionReadiness.status").value("UP"));
		}

	}
}
