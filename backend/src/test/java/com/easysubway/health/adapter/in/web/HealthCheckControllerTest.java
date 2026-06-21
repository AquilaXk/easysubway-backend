package com.easysubway.health.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@AutoConfigureMockMvc
@DisplayName("헬스체크 API")
class HealthCheckControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private WebEndpointsSupplier webEndpointsSupplier;

	@Test
	@DisplayName("공통 응답 형식으로 API 헬스체크를 반환한다")
	void apiHealthReturnsCommonResponse() throws Exception {
		mockMvc.perform(get("/api/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.status").value("UP"))
			.andExpect(jsonPath("$.data.service").value("easysubway-backend"));
	}

	@Test
	@DisplayName("액추에이터 헬스체크 엔드포인트가 UP 상태를 반환한다")
	void actuatorHealthIsAvailable() throws Exception {
		mockMvc.perform(get("/actuator/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	@DisplayName("액추에이터 readiness 엔드포인트가 트래픽 수신 가능 상태를 반환한다")
	void actuatorReadinessIsAvailable() throws Exception {
		mockMvc.perform(get("/actuator/health/readiness"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	@DisplayName("Prometheus 스크랩용 액추에이터 지표 엔드포인트를 노출한다")
	void actuatorPrometheusIsAvailable() throws Exception {
		assertThat(webEndpointsSupplier.getEndpoints())
			.extracting(endpoint -> endpoint.getEndpointId().toString())
			.contains("prometheus");
	}

	@Test
	@DisplayName("명시적으로 허용되지 않은 백엔드 경로는 기본 차단된다")
	void unknownBackendPathIsDeniedByDefault() throws Exception {
		mockMvc.perform(get("/api/v1/internal-unlisted-resource"))
			.andExpect(status().isForbidden());
	}
}
