package com.easysubway.route.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteSearchUseCase;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteV2Plan;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteV2Status;
import com.easysubway.route.domain.EtaConfidence;
import com.easysubway.route.domain.EtaSource;
import com.easysubway.route.domain.RouteRefreshResult;
import com.easysubway.route.domain.RouteRefreshStatus;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:prod-route-closure;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.flyway.locations=classpath:db/migration/h2",
	"spring.sql.init.mode=always",
	"spring.sql.init.continue-on-error=true",
	"spring.batch.jdbc.initialize-schema=never",
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-password",
	"easysubway.admin.basic-auth.enabled=false",
	"easysubway.auth.client-ip.trusted-proxies=",
	"easysubway.notifications.push.external-enabled=false",
	"EASYSUBWAY_ADS_EVENT_DAILY_CAP=1000000",
	"easysubway.report.receipt-token-pepper=prod-test-receipt-token-pepper-with-enough-entropy",
	"easysubway.report.upload.intent-signing-key=prod-test-upload-intent-signing-key-with-enough-entropy",
	"easysubway.report.upload.object-storage-endpoint=https://object-storage.example.com",
	"easysubway.report.upload.public-base-url=https://uploads.easysubway.example",
	"easysubway.report.upload.bucket=easysubway-report-uploads",
	"easysubway.report.upload.object-storage-access-key=prod-object-storage-access-key",
	"easysubway.report.upload.object-storage-secret-key=prod-object-storage-secret-key-with-enough-entropy",
	"easysubway.report.upload.object-storage-region=ap-northeast-2",
	"easysubway.route-v2.play-integrity.certificate-sha256=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
})
@ActiveProfiles("prod")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("운영 경로검색 API 폐쇄")
class ProductionRouteApiClosureTest {

	private static final String PAYLOAD_MARKER = "route-payload-marker-1913";
	private static final List<String> HEADER_MARKERS = List.of(
		"198.51.100.41",
		"198.51.100.42",
		"198.51.100.43"
	);

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private RouteSearchUseCase routeSearchUseCase;

	@MockitoBean
	private RouteV2SearchUseCase routeV2SearchUseCase;

	@BeforeEach
	void setUpCurrentReachableResponses() {
		RouteSearchResult route = routeSearchResult();
		when(routeSearchUseCase.searchRoute(any())).thenReturn(route);
		when(routeV2SearchUseCase.search(any())).thenReturn(
			new RouteV2Plan(List.of(route), List.of(RouteV2Status.FOUND), "test-planner")
		);
		when(routeSearchUseCase.refreshRoute(anyString())).thenReturn(
			new RouteRefreshResult(
				route.routeSearchId(),
				RouteRefreshStatus.UPDATED_ETA,
				route,
				LocalDateTime.of(2026, 7, 15, 9, 15),
				EtaSource.PLANNED,
				EtaConfidence.LOW,
				"계획 시간 기준",
				List.of()
			)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("closedEndpoints")
	@DisplayName("운영 profile은 route endpoint를 controller 전에 거부한다")
	void productionRejectsRouteEndpoints(
		String path,
		String body,
		boolean includeMarkers,
		CapturedOutput output
	) throws Exception {
		long before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM route_search_results", Long.class);
		MockHttpServletRequestBuilder request = post(path)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
		if (includeMarkers) {
			request.header("Forwarded", "for=" + HEADER_MARKERS.getFirst())
				.header("X-Forwarded-For", HEADER_MARKERS.get(1))
				.header("X-Real-IP", HEADER_MARKERS.get(2));
		}

		mockMvc.perform(request)
			.andExpect(status().isForbidden());

		assertThat(applicationContext.containsBean("routeSearchController")).isFalse();
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM route_search_results", Long.class))
			.isEqualTo(before);
		verifyNoInteractions(routeSearchUseCase, routeV2SearchUseCase);
		if (includeMarkers) {
			assertThat(output.getOut())
				.doesNotContain(PAYLOAD_MARKER)
				.doesNotContain(HEADER_MARKERS);
		}
	}

	private static Stream<Arguments> closedEndpoints() {
		return Stream.of(
			Arguments.of("/api/v1/routes/search", """
				{
				  "originStationId": "%s",
				  "destinationStationId": "station-sadang",
				  "mobilityType": "WHEELCHAIR"
				}
				""".formatted(PAYLOAD_MARKER), true),
			Arguments.of("/api/v2/routes/search", """
				{
				  "originStationId": "station-sangnoksu",
				  "destinationStationId": "station-sadang",
				  "departureTime": "2026-07-15T09:15:00+09:00",
				  "mobilityType": "SENIOR",
				  "constraintMode": "ALLOW_WITH_WARNINGS",
				  "useRealtime": false,
				  "maxTransfers": 3,
				  "alternativeCount": 3
				}
				""", false),
			Arguments.of("/api/v2/routes/route-search-marker/refresh", "{}", false)
		);
	}

	private static RouteSearchResult routeSearchResult() {
		return new RouteSearchResult(
			"route-search-marker",
			"station-sangnoksu",
			"상록수",
			"station-sadang",
			"사당",
			MobilityType.SENIOR,
			RouteSearchStatus.FOUND,
			"line-4",
			"수도권 4호선",
			1,
			List.of(),
			List.of(),
			List.of(),
			LocalDateTime.of(2026, 7, 15, 9, 15)
		);
	}
}
