package com.easysubway.health.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.actuate.health.StatusAggregator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
	webEnvironment = WebEnvironment.RANDOM_PORT,
	properties = "management.endpoint.health.show-details=always"
)
@AutoConfigureObservability
@AutoConfigureMockMvc
@Import(HealthCheckControllerTest.TimetableSnapshotGaugeStubConfig.class)
@DisplayName("헬스체크 API")
class HealthCheckControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private WebEndpointsSupplier webEndpointsSupplier;

	@Autowired
	private StatusAggregator statusAggregator;

	@Autowired
	private TestRestTemplate restTemplate;

	@LocalServerPort
	private int port;

	@Test
	@DisplayName("공통 응답 형식으로 API 헬스체크를 반환한다")
	void apiHealthReturnsCommonResponse() throws Exception {
		mockMvc.perform(get("/api/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.status").value("UP"))
			.andExpect(jsonPath("$.data.service").value("easysubway-backend"))
			.andExpect(jsonPath("$.data.components").doesNotExist());
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
	@DisplayName("액추에이터는 backend component health detail을 노출한다")
	void actuatorBackendComponentHealthIsAvailable() throws Exception {
		mockMvc.perform(get("/actuator/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"))
			.andExpect(jsonPath("$.components.backendComponent.status").value("UP"))
			.andExpect(jsonPath("$.components.backendComponent.details.summaryStatus").value("UP"))
			.andExpect(jsonPath("$.components.backendComponent.details.components[0].name").value("application"));
	}

	@Test
	@DisplayName("액추에이터 status aggregator는 degraded와 stale을 UP보다 우선한다")
	void actuatorStatusAggregatorPrioritizesCustomSummaryStates() {
		assertThat(statusAggregator.getAggregateStatus(Set.of(Status.UP, new Status("DEGRADED"))).getCode())
			.isEqualTo("DEGRADED");
		assertThat(statusAggregator.getAggregateStatus(Set.of(Status.UP, new Status("STALE"))).getCode())
			.isEqualTo("STALE");
	}

	@Test
	@DisplayName("Prometheus 액추에이터 지표는 등록하되 사설망 밖 출처의 접근은 차단한다")
	void actuatorPrometheusIsNotPublic() throws Exception {
		assertThat(webEndpointsSupplier.getEndpoints())
			.extracting(endpoint -> endpoint.getEndpointId().toString())
			.contains("prometheus");

		// MockMvc 기본 remoteAddr(127.0.0.1 loopback)은 허용 RFC1918 대역이 아니므로 공개/미상 출처와
		// 동일하게 거부된다 — /actuator/prometheus는 사설망 내부 scrape에만 열린다.
		mockMvc.perform(get("/actuator/prometheus"))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("사설망(RFC1918) 출처의 Prometheus scrape는 앱 지표와 함께 200을 반환한다")
	void actuatorPrometheusIsScrapableFromInternalNetwork() throws Exception {
		// docker network 내부 Prometheus(backend_app_metrics job, #2376)가 접근하는 사설망 출처를 모사한다.
		mockMvc.perform(get("/actuator/prometheus")
				.with(request -> {
					request.setRemoteAddr("172.18.0.5");
					return request;
				}))
			.andExpect(status().isOk())
			// alerts.yml의 freshness 경보가 참조하는 라이브 게이지가 실제로 scrape 본문에 렌더링된다.
			.andExpect(content().string(
				org.hamcrest.Matchers.containsString("easysubway_timetable_snapshot_remaining_seconds")));
	}

	@Test
	@DisplayName("제품 소개 페이지를 인증 없이 노출한다")
	void productLandingPageIsPublic() {
		for (String path : List.of("/", "/index.html")) {
			ResponseEntity<String> response = restTemplate.getForEntity(
				"http://127.0.0.1:" + port + path,
				String.class
			);

			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.getHeaders().getContentType()).isNotNull();
			assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
			assertThat(response.getBody()).contains(
				"쉬운 지하철",
				"빠른 길보다,",
				"갈 수 있는 길.",
				"Android 출시 준비 중",
				"아래 기능을 Android 앱에 담아 출시를 준비하고 있습니다.",
				"https://github.com/AquilaXk/easysubway",
				"/privacy",
				"/terms",
				"/location-terms",
				"mailto:aquila@aquilaxk.site",
				"mailto:support@aquilaxk.site"
			);
		}
	}

	@Test
	@DisplayName("제품 소개 페이지는 GET 이외 요청을 차단한다")
	void productLandingPageRejectsNonGetRequests() throws Exception {
		for (String path : List.of("/", "/index.html")) {
			mockMvc.perform(post(path))
				.andExpect(status().isForbidden());
		}
	}

	@Test
	@DisplayName("개인정보처리방침 공개 페이지를 인증 없이 노출한다")
	void privacyPolicyPageIsPublic() throws Exception {
		for (String path : List.of("/privacy", "/easysubway/privacy")) {
			mockMvc.perform(get(path))
				.andExpect(status().isOk())
				.andExpect(result -> assertThat(result.getResponse().getContentType())
					.contains("text/html", "UTF-8"))
				.andExpect(result -> assertThat(result.getResponse().getContentAsString())
					.contains(
						"쉬운 지하철 개인정보처리방침",
						"처리하는 개인정보 항목과 목적",
							"제3자 제공, 처리 위탁 및 추적",
						"외부 지도 도보 길안내",
						"카카오맵 앱",
						"카카오맵 웹",
							"이용자 및 법정대리인의 권리",
						"개인정보 보호책임자",
						"privacy@aquilaxk.site"
					));
		}
	}

	@Test
	@DisplayName("서비스 이용약관 공개 페이지를 두 경로에서 인증 없이 노출한다")
	void termsPageIsPublic() throws Exception {
		for (String path : List.of("/terms", "/easysubway/terms")) {
			mockMvc.perform(get(path))
				.andExpect(status().isOk())
				.andExpect(result -> assertThat(result.getResponse().getContentType())
					.contains("text/html", "UTF-8"))
				.andExpect(result -> assertThat(result.getResponse().getContentAsString())
					.contains("쉬운 지하철 서비스 이용약관", "서비스의 내용", "현장 안내를 우선"));
		}
	}

	@Test
	@DisplayName("위치정보 이용약관 공개 페이지를 두 경로에서 인증 없이 노출한다")
	void locationTermsPageIsPublic() throws Exception {
		for (String path : List.of("/location-terms", "/easysubway/location-terms")) {
			mockMvc.perform(get(path))
				.andExpect(status().isOk())
				.andExpect(result -> assertThat(result.getResponse().getContentType())
					.contains("text/html", "UTF-8"))
				.andExpect(result -> assertThat(result.getResponse().getContentAsString())
					.contains("쉬운 지하철 위치정보 이용약관", "제 4 조 (위치기반서비스의 내용)", "카카오맵 앱", "카카오맵 웹"));
		}
	}

	@Test
	@DisplayName("명시적으로 허용되지 않은 백엔드 경로는 기본 차단된다")
	void unknownBackendPathIsDeniedByDefault() throws Exception {
		mockMvc.perform(get("/api/v1/internal-unlisted-resource"))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("파비콘 자산이 없어도 /favicon.ico는 금지(403)가 아니라 실제 컨테이너에서 404로 응답한다(#2349)")
	void faviconRequestIsNotForbiddenWhenAssetIsAbsent() {
		// #2349 회귀의 진짜 가드: 컨테이너 ERROR dispatch는 MockMvc로 재현되지 않으므로 실제 포트에
		// 요청한다. 파비콘 정적 자산이 없어 404가 나면 컨테이너가 /error로 ERROR dispatch를 forward하고,
		// dispatcherTypeMatchers(ERROR).permitAll() 덕분에 원래 404가 403으로 뒤바뀌지 않고 그대로 렌더된다.
		ResponseEntity<String> response = restTemplate.getForEntity(
			"http://localhost:" + port + "/favicon.ico", String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getStatusCode().value()).isNotEqualTo(403);
	}

	@Test
	@DisplayName("직접 외부 요청 /error는 공개 조회 표면이 아니라 기본 차단(403)으로 남는다(#2349)")
	void directErrorRequestStaysForbidden() {
		// dispatcherTypeMatchers(ERROR)는 컨테이너 ERROR dispatch만 허용하므로, 직접 외부
		// GET /error(REQUEST dispatch)는 여전히 공개 체인 anyRequest().denyAll()에 걸려 403이다.
		// /error가 실수로 공개 조회 표면이 되지 않음을 실제 컨테이너 요청으로 고정한다.
		ResponseEntity<String> response = restTemplate.getForEntity(
			"http://localhost:" + port + "/error", String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	// TimetableFreshnessMonitor는 prod|staging|release|prod-like 프로파일에서만 활성화되므로 기본 테스트
	// 프로파일에서는 remaining-seconds 게이지가 등록되지 않는다. 이 스텁이 동일 이름의 게이지를 미터 레지스트리에
	// 등록해 /actuator/prometheus 응답이 앱 게이지를 사설망 scrape에 실제로 노출하는지 검증할 수 있게 한다.
	// 실제 모니터가 이 이름을 렌더링하는지는 TimetableFreshnessMonitorTest에서 별도로 고정한다.
	@TestConfiguration(proxyBeanMethods = false)
	static class TimetableSnapshotGaugeStubConfig {
		@Bean
		MeterBinder timetableSnapshotRemainingSecondsGaugeStub() {
			return registry -> Gauge.builder(
					"easysubway.timetable.snapshot.remaining.seconds", () -> 86400.0)
				.register(registry);
		}
	}
}
