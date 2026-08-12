package com.easysubway.route.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.common.error.InvalidRequestException;
import com.easysubway.journey.bundle.RouteBundleStartupCandidateLoader;
import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteSearchUseCase;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteV2Plan;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteV2Status;
import com.easysubway.route.application.port.out.PlayIntegrityDecoder;
import com.easysubway.route.application.port.out.PlayIntegrityDecoder.PlayIntegrityVerdict;
import com.easysubway.route.application.service.RouteV2SessionService;
import com.easysubway.route.domain.EtaConfidence;
import com.easysubway.route.domain.EtaSource;
import com.easysubway.route.domain.RouteRefreshResult;
import com.easysubway.route.domain.RouteRefreshStatus;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.transit.domain.StationNotFoundException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
	"easysubway.admin.remember-me.key=prod-test-admin-remember-me-signing-key",
	"easysubway.admin.basic-auth.enabled=false",
	"easysubway.auth.client-ip.trusted-proxies=",
	"easysubway.notifications.push.external-enabled=false",
	"EASYSUBWAY_SEOUL_TOPIS_SERVICE_KEY=synthetic-test-key",
	"EASYSUBWAY_ADS_EVENT_DAILY_CAP=1000000",
	"easysubway.journey.search.timeout=PT2S",
	"easysubway.journey.search.max-searches-per-session=12",
	"easysubway.journey.session.certificate-sha256=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
	"easysubway.journey-v3.readiness.service-token=synthetic-context-readiness-token-0001",
	"easysubway.journey-v3.readiness.instance-id=backend-context-route-closure",
	"easysubway.journey-v3.readiness.release-tuple-sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
	"easysubway.journey-v3.readiness.backend-image-digest=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
	"easysubway.journey-v3.readiness.backend-config-sha256=cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
	"easysubway.journey-v3.readiness.journey-contract-sha256=dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
	"easysubway.journey-v3.readiness.traffic-generation=1",
	"easysubway.journey-v3.route-bundle-startup.descriptor-base64=e30=",
	"easysubway.journey-v3.route-bundle-startup.activation-request-identity=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
	"easysubway.journey-v3.route-bundle-startup.trusted-raw-descriptor-base-url=https://objects.example.com",
	"easysubway.journey-v3.route-bundle-startup.current-key-id=synthetic-current-key",
	"easysubway.journey-v3.route-bundle-startup.current-public-key-pem=synthetic-public-key",
	"easysubway.report.receipt-token-pepper=prod-test-receipt-token-pepper-with-enough-entropy",
	"easysubway.report.upload.intent-signing-key=prod-test-upload-intent-signing-key-with-enough-entropy",
	"easysubway.report.upload.object-storage-endpoint=https://object-storage.example.com",
	"easysubway.report.upload.public-base-url=https://uploads.easysubway.example",
	"easysubway.report.upload.bucket=easysubway-report-uploads",
	"easysubway.report.upload.object-storage-access-key=prod-object-storage-access-key",
	"easysubway.report.upload.object-storage-secret-key=prod-object-storage-secret-key-with-enough-entropy",
	"easysubway.report.upload.object-storage-region=ap-northeast-2",
	"easysubway.route-v2.origin-secret=route-v2-origin-test-secret",
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

	@MockitoBean
	private PlayIntegrityDecoder playIntegrityDecoder;

	@MockitoBean
	private RouteBundleStartupCandidateLoader startupCandidateLoader;

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
		when(playIntegrityDecoder.decode(anyString())).thenReturn(new PlayIntegrityVerdict(
			"com.easysubway.app",
			"SVOaIn_B5rcm1TVIPIEozQ_iGimOCakTxKuH3iXlD18",
			Instant.now(),
			"com.easysubway.app",
			"PLAY_RECOGNIZED",
			List.of("A".repeat(43)),
			"LICENSED",
			List.of("MEETS_DEVICE_INTEGRITY")
		));
	}

	@AfterEach
	void clearTimetableSnapshotFixture() {
		jdbcTemplate.update("DELETE FROM timetable_snapshot_active");
		jdbcTemplate.update("DELETE FROM timetable_snapshot_history");
		jdbcTemplate.update("DELETE FROM route_service_artifact_evidence");
		jdbcTemplate.update("DELETE FROM transit_stop_times WHERE trip_id = 'itx-closure-trip'");
		jdbcTemplate.update("DELETE FROM transit_trips WHERE id = 'itx-closure-trip'");
		jdbcTemplate.update("DELETE FROM transit_routes WHERE id = 'itx-closure-route'");
		jdbcTemplate.update("DELETE FROM service_calendars WHERE service_id = 'itx-closure-service'");
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

		assertThat(applicationContext.containsBean("routeSearchController")).isTrue();
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM route_search_results", Long.class))
			.isEqualTo(before);
		verifyNoInteractions(routeSearchUseCase, routeV2SearchUseCase);
		if (includeMarkers) {
			assertThat(output.getOut())
				.doesNotContain(PAYLOAD_MARKER)
				.doesNotContain(HEADER_MARKERS);
		}
	}

	@Test
	@DisplayName("gateway origin 증명과 유효 attestation이면 10분 session을 발급한다")
	void issuesSessionThroughGatewayOnly() throws Exception {
		int sessionsBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM route_v2_sessions", Integer.class);
		int noncesBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM route_v2_nonce_replays", Integer.class);
		mockMvc.perform(post("/api/v2/routes/session")
				.header("X-EasySubway-Origin-Verify", "route-v2-origin-test-secret")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"integrityToken":"integrity-token","clientNonce":"AAAAAAAAAAAAAAAAAAAAAA"}
					"""))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "private, no-store"))
			.andExpect(jsonPath("$.token").isString())
			.andExpect(jsonPath("$.scope").value("route:v2:itx"));

		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM route_v2_sessions", Integer.class))
			.isEqualTo(sessionsBefore + 1);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM route_v2_nonce_replays", Integer.class))
			.isEqualTo(noncesBefore + 1);
	}

	@Test
	@DisplayName("direct-origin Route V2는 handler와 DB write 전에 exact 403으로 거부한다")
	void directOriginRouteV2IsForbiddenBeforeHandler() throws Exception {
		long before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM route_v2_sessions", Long.class);

		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content(routeV2Body()))
			.andExpect(status().isForbidden())
			.andExpect(header().string("Cache-Control", "private, no-store"))
			.andExpect(jsonPath("$.code").value("ROUTE_ORIGIN_FORBIDDEN"));

		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM route_v2_sessions", Long.class)).isEqualTo(before);
		verifyNoInteractions(routeV2SearchUseCase);
	}

	@Test
	@DisplayName("gateway 경유 Route V2의 session 없음은 exact 401이다")
	void gatewayRouteV2RequiresSession() throws Exception {
		mockMvc.perform(post("/api/v2/routes/search")
				.header("X-EasySubway-Origin-Verify", "route-v2-origin-test-secret")
				.contentType(MediaType.APPLICATION_JSON)
				.content(routeV2Body()))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string("Cache-Control", "private, no-store"))
			.andExpect(jsonPath("$.code").value("ROUTE_SESSION_REQUIRED"));

		verifyNoInteractions(routeV2SearchUseCase);
	}

	@Test
	@DisplayName("유효 session은 카운트한 뒤 timetable artifact 없음에 exact 503으로 fail closed한다")
	void missingTimetableFailsClosedAfterCountingSession() throws Exception {
		String token = "D".repeat(43);
		insertSession(token, 0);

		mockMvc.perform(post("/api/v2/routes/search")
				.header("X-EasySubway-Origin-Verify", "route-v2-origin-test-secret")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(routeV2Body()))
			.andExpect(status().isServiceUnavailable())
			.andExpect(header().string("Cache-Control", "private, no-store"))
			.andExpect(jsonPath("$.code").value("ITX_TIMETABLE_UNAVAILABLE"));

		assertThat(jdbcTemplate.queryForObject(
			"SELECT request_count FROM route_v2_sessions WHERE token_sha256 = ?",
			Integer.class,
			RouteV2SessionService.tokenHash(token)
		)).isEqualTo(1);
		verifyNoInteractions(routeV2SearchUseCase);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidTimetableStates")
	@DisplayName("stale·schema-invalid·lineage mismatch snapshot은 exact 503으로 fail closed한다")
	void invalidTimetableSnapshotFailsClosed(String state) throws Exception {
		String token = switch (state) {
			case "stale" -> "G".repeat(43);
			case "schema-invalid" -> "H".repeat(43);
			default -> "I".repeat(43);
		};
		insertSession(token, 0);
		insertActiveTimetableSnapshot(state);

		mockMvc.perform(post("/api/v2/routes/search")
				.header("X-EasySubway-Origin-Verify", "route-v2-origin-test-secret")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(routeV2Body()))
			.andExpect(status().isServiceUnavailable())
			.andExpect(header().string("Cache-Control", "private, no-store"))
			.andExpect(jsonPath("$.code").value("ITX_TIMETABLE_UNAVAILABLE"));

		verifyNoInteractions(routeV2SearchUseCase);
	}

	@Test
	@DisplayName("유효 session의 station 위반은 timetable 부재보다 먼저 exact 422다")
	void invalidStationPrecedesMissingTimetable() throws Exception {
		String token = "F".repeat(43);
		insertSession(token, 0);
		doThrow(new StationNotFoundException())
			.when(routeSearchUseCase)
			.validateRouteSearch(argThat(command -> "missing-station".equals(command.originStationId())));

		mockMvc.perform(post("/api/v2/routes/search")
				.header("X-EasySubway-Origin-Verify", "route-v2-origin-test-secret")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(routeV2Body().replace("station-sangnoksu", "missing-station")))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(header().string("Cache-Control", "private, no-store"))
			.andExpect(jsonPath("$.code").value("ROUTE_SCOPE_INVALID"));

		assertThat(jdbcTemplate.queryForObject(
			"SELECT request_count FROM route_v2_sessions WHERE token_sha256 = ?",
			Integer.class,
			RouteV2SessionService.tokenHash(token)
		)).isEqualTo(1);
		verifyNoInteractions(routeV2SearchUseCase);
	}

	@Test
	@DisplayName("유효 session의 SUBWAY-only transport scope는 exact 422다")
	void invalidTransportScopeReturnsExact422() throws Exception {
		String token = "J".repeat(43);
		insertSession(token, 0);

		mockMvc.perform(post("/api/v2/routes/search")
				.header("X-EasySubway-Origin-Verify", "route-v2-origin-test-secret")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(routeV2Body().replace(
					"\"departureTime\"",
					"\"transportScope\":\"SUBWAY\",\"departureTime\""
				)))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(header().string("Cache-Control", "private, no-store"))
			.andExpect(jsonPath("$.code").value("ROUTE_SCOPE_INVALID"));

		verifyNoInteractions(routeV2SearchUseCase);
	}

	@Test
	@DisplayName("session 전체 50회 초과는 integer Retry-After와 exact 429다")
	void sessionLifetimeLimitReturnsExact429() throws Exception {
		String token = "E".repeat(43);
		insertSession(token, 50);

		mockMvc.perform(post("/api/v2/routes/search")
				.header("X-EasySubway-Origin-Verify", "route-v2-origin-test-secret")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(routeV2Body()))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().string("Cache-Control", "private, no-store"))
			.andExpect(header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("^[0-9]+$")))
			.andExpect(jsonPath("$.code").value("ROUTE_RATE_LIMITED"));

		verifyNoInteractions(routeV2SearchUseCase);
	}

	private void insertSession(String token, int requestCount) {
		Instant now = Instant.now();
		jdbcTemplate.update("""
			INSERT INTO route_v2_sessions (token_sha256, scope, issued_at, expires_at, request_count)
			VALUES (?, 'route:v2:itx', ?, ?, ?)
			""",
			RouteV2SessionService.tokenHash(token),
			Timestamp.from(now.minusSeconds(1)),
			Timestamp.from(now.plusSeconds(600)),
			requestCount
		);
	}

	private void insertActiveTimetableSnapshot(String state) {
		String freshUntil = state.equals("stale") ? "2000-01-01T00:00:00Z" : "2999-01-01T00:00:00Z";
		String schemaIdentity = state.equals("schema-invalid") ? "invalid" : "backend-timetable-snapshot-v1";
		String evidencePackHash = state.equals("lineage-mismatch") ? "9".repeat(64) : "b".repeat(64);
		jdbcTemplate.update("""
			INSERT INTO service_calendars (
				service_id, start_date, end_date, timezone,
				monday, tuesday, wednesday, thursday, friday, saturday, sunday
			) VALUES ('itx-closure-service', '20260101', '29991231', 'Asia/Seoul',
				TRUE, TRUE, TRUE, TRUE, TRUE, TRUE, TRUE)
			""");
		jdbcTemplate.update("""
			INSERT INTO transit_routes (id, timezone, line_id, route_short_name, route_long_name, direction_name)
			VALUES ('itx-closure-route', 'Asia/Seoul', 'line-54a7b980b7c3', 'ITX-청춘', '', 'down')
			""");
		jdbcTemplate.update("""
			INSERT INTO transit_trips (
				id, route_id, service_id, service_pattern, service_class,
				service_day_start_seconds, trip_headsign, direction_id
			) VALUES ('itx-closure-trip', 'itx-closure-route', 'itx-closure-service', 'EXPRESS',
				'ITX_CHEONGCHUN', 0, '춘천', 'down')
			""");
		jdbcTemplate.update("""
			INSERT INTO transit_stop_times (
				trip_id, stop_sequence, station_id, line_id,
				pickup_type, drop_off_type, arrival_seconds, departure_seconds
			) VALUES ('itx-closure-trip', 1, 'station-sangnoksu', 'line-54a7b980b7c3', 0, 0, 0, 0)
			""");
		jdbcTemplate.update("""
			INSERT INTO route_service_artifact_evidence (
				service_class, timetable_artifact_id, timetable_artifact_sha256,
				canonical_pack_id, canonical_pack_sha256, canonical_pack_sqlite_sha256,
				admission_status, admission_eligible, fresh_until, source_issue
			) VALUES ('ITX_CHEONGCHUN', 'itx-closure-artifact', ?, 'capital', ?, ?,
				'ADMITTED', TRUE, ?, 2135)
			""", "a".repeat(64), evidencePackHash, "c".repeat(64), freshUntil);
		jdbcTemplate.update("""
			INSERT INTO timetable_snapshot_history (
				snapshot_sha256, snapshot_id, schema_identity, fresh_until,
				source_artifact_id, source_artifact_sha256, completeness_evidence_sha256,
				canonical_pack_sha256, canonical_pack_sqlite_sha256,
				canonical_station_version, canonical_station_set_sha256, canonical_station_member_count,
				source_lineage_sha256, evidence_hash,
				calendar_count, route_count, trip_count, stop_time_count
			) VALUES (?, 'snapshot-closure', ?, ?, 'itx-closure-artifact', ?, ?, ?, ?, ?, ?, 1, ?, ?, 1, 1, 1, 1)
			""",
			"1".repeat(64), schemaIdentity, freshUntil, "a".repeat(64), "d".repeat(64),
			"b".repeat(64), "c".repeat(64), "sha256:" + "e".repeat(64), "e".repeat(64),
			"f".repeat(64), "2".repeat(64));
		jdbcTemplate.update(
			"INSERT INTO timetable_snapshot_active (singleton_id, snapshot_sha256) VALUES (1, ?)",
			"1".repeat(64)
		);
	}

	private String routeV2Body() {
		return """
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
			""";
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
			Arguments.of("/api/v2/routes/route-search-marker/refresh", "{}", false)
		);
	}

	private static Stream<String> invalidTimetableStates() {
		return Stream.of("stale", "schema-invalid", "lineage-mismatch");
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
