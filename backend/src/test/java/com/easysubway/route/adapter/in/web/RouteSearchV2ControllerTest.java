package com.easysubway.route.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteSearchUseCase;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.model.PlannerIdentity;
import com.easysubway.route.domain.EtaConfidence;
import com.easysubway.route.domain.EtaSource;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.RouteRefreshResult;
import com.easysubway.route.domain.RouteRefreshStatus;
import com.easysubway.route.domain.RouteNotFoundException;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteStep;
import com.easysubway.route.domain.RouteWarning;
import com.easysubway.route.domain.RouteWarningCode;
import com.easysubway.route.domain.StairAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DisplayName("공개 경로 검색 V2 API")
class RouteSearchV2ControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private RouteSearchUseCase routeSearchUseCase;

	@BeforeEach
	void setUpRealtimeOverlayCapability() {
		when(routeSearchUseCase.supportsRealtimeOverlay()).thenReturn(true);
		when(routeSearchUseCase.stabilizeTimetableRouteCandidatesWithSource(
			any(), anyInt(), anyInt(), any(), any(), eq(false)
		)).thenAnswer(invocation -> {
			var command = (com.easysubway.route.application.port.in.SearchRouteCommand) invocation.getArgument(0);
			int alternativeCount = invocation.getArgument(2);
			List<RouteSearchResult> timetableResults = invocation.getArgument(3);
			java.util.function.UnaryOperator<List<RouteSearchResult>> selectCandidates = invocation.getArgument(4);
			List<RouteSearchResult> controllerFixture = routeSearchUseCase.searchRouteAlternatives(
				command, alternativeCount);
			return new RouteSearchUseCase.TimetableCandidateSelection(
				selectCandidates.apply(controllerFixture.isEmpty() ? timetableResults : controllerFixture),
				RouteSearchUseCase.TimetableCandidateSource.TIMETABLE_SCAN
			);
		});
		when(routeSearchUseCase.applyRealtimeToTimetableCandidates(any(), any()))
			.thenAnswer(invocation -> invocation.getArgument(1));
	}

	@Test
	@DisplayName("Route V2 request에는 일반·급행 직접 선택 필드가 없다")
	void routeSearchV2RequestHasNoServicePatternSelector() throws Exception {
		var fields = Arrays.stream(Class.forName(
			"com.easysubway.route.adapter.in.web.RouteSearchController$RouteSearchV2Request"
		).getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();

		assertThat(fields).doesNotContain("servicePattern", "expressOnly", "trainType");
	}

	@TestConfiguration
	static class RouteTimetableTestConfiguration {

		@Bean
		LoadRouteTimetablePort routeTimetablePort() {
			var timetable = new LoadRouteTimetablePort.RouteTimetable(
				List.of(new LoadRouteTimetablePort.ServiceCalendar(
					"weekday-2026",
					true,
					true,
					true,
					true,
					true,
					false,
					false,
					LocalDate.parse("2026-06-30"),
					LocalDate.parse("2026-12-31"),
					"Asia/Seoul"
				)),
				List.of(),
				List.of(new LoadRouteTimetablePort.TransitRoute(
					"route-seoul-4",
					"seoul-4",
					"4",
					"수도권 4호선",
					"사당 방면",
					"Asia/Seoul"
				)),
				List.of(new LoadRouteTimetablePort.TransitTrip(
					"trip-seoul-4-0900",
					"route-seoul-4",
					"weekday-2026",
					"사당",
					"0",
					"LOCAL",
					0
				)),
				List.of(
					new LoadRouteTimetablePort.TransitStopTime(
						"trip-seoul-4-0900",
						1,
						"station-sangnoksu",
						"seoul-4",
						33900,
						33900,
						0,
						0
					),
					new LoadRouteTimetablePort.TransitStopTime(
						"trip-seoul-4-0900",
						2,
						"station-sadang",
						"seoul-4",
						34500,
						34500,
						0,
						0
					)
				),
				List.of(),
				List.of(),
				null,
				verifiedAccessData()
			);
			return new LoadRouteTimetablePort() {
				@Override
				public RouteTimetable loadRouteTimetable() {
					return timetable;
				}

				@Override
				public RouteTimetableSnapshot loadRouteTimetableSnapshot() {
					return new RouteTimetableSnapshot(
						"snapshot-cache",
						"snapshot-test",
						plannerIdentity(),
						timetable
					);
				}

				@Override
				public Optional<String> activeItxTimetableArtifactId() {
					return Optional.of("snapshot-test");
				}
			};
		}

		private static PlannerIdentity plannerIdentity() {
			return new PlannerIdentity(
				"a".repeat(64),
				"b".repeat(64),
				"c".repeat(64),
				"sha256:" + "d".repeat(64),
				"d".repeat(64),
				"e".repeat(64),
				"f".repeat(64)
			);
		}
		private static LoadRouteTimetablePort.RouteAccessData verifiedAccessData() {
			var entry = new LoadRouteTimetablePort.PathwayEdge(
				"entry", "entrance", "platform-a", 120, 80, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED");
			var exit = new LoadRouteTimetablePort.PathwayEdge(
				"exit", "platform-b", "gate", 90, 60, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED");
			return new LoadRouteTimetablePort.RouteAccessData(
				List.of(
					new LoadRouteTimetablePort.PathwayNode("entrance", "station-sangnoksu", null, "ENTRANCE"),
					new LoadRouteTimetablePort.PathwayNode("platform-a", "station-sangnoksu", "seoul-4", "PLATFORM"),
					new LoadRouteTimetablePort.PathwayNode("platform-b", "station-sadang", "seoul-4", "PLATFORM"),
					new LoadRouteTimetablePort.PathwayNode("gate", "station-sadang", null, "EXIT")),
				List.of(entry, exit),
				List.of(),
				List.of(
					new LoadRouteTimetablePort.RouteEdgeEvidence(
						"entry-evidence", "station-sangnoksu", "seoul-4", "entry", "ENTRY",
						"OFFICIAL_SOURCE", "VERIFIED", true, null),
					new LoadRouteTimetablePort.RouteEdgeEvidence(
						"exit-evidence", "station-sadang", "seoul-4", "exit", "EXIT",
						"OFFICIAL_SOURCE", "VERIFIED", true, null)
				)
			);
		}
	}

	@Test
	@DisplayName("모바일 V2 계약으로 itinerary와 leg 단위 ETA 필드를 반환한다")
	void routeSearchV2ReturnsItineraryContract() throws Exception {
		when(routeSearchUseCase.searchRouteAlternatives(argThat(command ->
			"station-sangnoksu".equals(command.originStationId())
				&& "station-sadang".equals(command.destinationStationId())
				&& command.mobilityType() == MobilityType.STROLLER
				&& command.constraintMode() == ConstraintMode.STRICT_STEP_FREE
				&& command.maxTransfers() == 3
		), eq(3))).thenReturn(List.of(foundRouteSearch(), blockedRouteSearch()));

		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "mobilityType": "STROLLER",
					  "constraintMode": "STRICT_STEP_FREE",
					  "useRealtime": true,
					  "maxTransfers": 3,
					  "alternativeCount": 3
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.contractVersion").value("ROUTE_SEARCH_V2"))
			.andExpect(jsonPath("$.data.originStationId").value("station-sangnoksu"))
			.andExpect(jsonPath("$.data.destinationStationId").value("station-sadang"))
			.andExpect(jsonPath("$.data.departureTime").value("2026-06-30T09:15:00+09:00"))
			.andExpect(jsonPath("$.data.constraintMode").value("STRICT_STEP_FREE"))
			.andExpect(jsonPath("$.data.useRealtime").value(true))
			.andExpect(jsonPath("$.data.maxTransfers").value(3))
			.andExpect(jsonPath("$.data.alternativeCount").value(3))
			.andExpect(jsonPath("$.data.statuses[0]").value("FOUND"))
			.andExpect(jsonPath("$.data.statuses[1]").value("REALTIME_UNAVAILABLE_PLANNED_USED"))
			.andExpect(jsonPath("$.data.statuses[2]").value("BLOCKED_ACCESSIBILITY"))
			.andExpect(jsonPath("$.data.itineraries[0].itineraryId")
				.value(org.hamcrest.Matchers.startsWith("route-v2-state-")))
			.andExpect(jsonPath("$.data.itineraries[0].status").value("FOUND"))
			.andExpect(jsonPath("$.data.itineraries[0].plannedArrivalTime").value("2026-06-30T09:22:00+09:00"))
			.andExpect(jsonPath("$.data.itineraries[0].realtimeArrivalTime").doesNotExist())
			.andExpect(jsonPath("$.data.itineraries[0].etaSource").value("STATIC_BACKEND_ESTIMATE"))
			.andExpect(jsonPath("$.data.itineraries[0].etaConfidence").value("LOW"))
			.andExpect(jsonPath("$.data.itineraries[0].durationSeconds").value(420))
			.andExpect(jsonPath("$.data.itineraries[0].transferCount").value(0))
			.andExpect(jsonPath("$.data.itineraries[0].walkingDistanceMeters").value(300))
			.andExpect(jsonPath("$.data.itineraries[0].accessibilityRisk.level").value("LOW"))
			.andExpect(jsonPath("$.data.itineraries[0].accessibilityRisk.riskLevel").value("NONE"))
			.andExpect(jsonPath("$.data.itineraries[0].accessibilityRisk.stairCount").value(0))
			.andExpect(jsonPath("$.data.itineraries[0].accessibilityRisk.unknownAccessibilityCount").value(0))
			.andExpect(jsonPath("$.data.itineraries[0].accessibilityRisk.generatedConnectorCount").value(0))
			.andExpect(jsonPath("$.data.itineraries[0].accessibilityRisk.staleDataCount").value(0))
			.andExpect(jsonPath("$.data.itineraries[0].accessibilityRisk.lowConfidenceCount").value(0))
			.andExpect(jsonPath("$.data.itineraries[0].accessibilityRisk.unavailableFacilityCount").value(0))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].legType").value("ACCESS"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].plannedDepartureTime").value("2026-06-30T09:15:00+09:00"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].plannedArrivalTime").value("2026-06-30T09:19:00+09:00"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].plannedDepartureTime").value("2026-06-30T09:19:00+09:00"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].plannedArrivalTime").value("2026-06-30T09:22:00+09:00"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].waitTimeSeconds").value(0))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].slackSeconds").value(0))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].etaSource").value("STATIC_BACKEND_ESTIMATE"))
			.andExpect(jsonPath("$.data.itineraries[0].commercialEtaEligible").value(false))
			.andExpect(jsonPath("$.data.itineraries[1].status").value("BLOCKED_ACCESSIBILITY"))
			.andExpect(jsonPath("$.data.itineraries[2]").doesNotExist());
	}

	@Test
	@DisplayName("동일한 V2 요청도 persisted itinerary ID는 매번 고유하다")
	void routeSearchV2UsesUniqueItineraryIdPerRequest() throws Exception {
		when(routeSearchUseCase.searchRouteAlternatives(any(), eq(1))).thenReturn(List.of(foundRouteSearch()));
		String body = """
			{
			  "originStationId": "station-sangnoksu",
			  "destinationStationId": "station-sadang",
			  "departureTime": "2026-06-30T09:15:00+09:00",
			  "mobilityType": "WHEELCHAIR",
			  "constraintMode": "STRICT_STEP_FREE",
			  "useRealtime": false,
			  "maxTransfers": 1,
			  "alternativeCount": 1
			}
			""";

		String first = mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String second = mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		String firstId = objectMapper.readTree(first).at("/data/itineraries/0/itineraryId").asText();
		String secondId = objectMapper.readTree(second).at("/data/itineraries/0/itineraryId").asText();
		assertThat(firstId).startsWith("route-v2-state-").isNotEqualTo(secondId);
	}

	@Test
	@DisplayName("Route V2는 objective·official fare와 RIDE trip class/pattern을 보존한다")
	void routeSearchV2ReturnsPlannerObjectiveFareAndTypedRideMetadata() throws Exception {
		when(routeSearchUseCase.searchRouteAlternatives(argThat(command ->
			command.constraintMode() == ConstraintMode.STRICT_STEP_FREE
		), eq(1))).thenReturn(List.of(foundTypedTimetableRouteSearch()));

		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "transportScope": "SUBWAY_AND_ITX_CHEONGCHUN",
					  "objective": "FASTEST",
					  "mobilityType": "STROLLER",
					  "constraintMode": "STRICT_STEP_FREE",
					  "useRealtime": false,
					  "maxTransfers": 1,
					  "alternativeCount": 1
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.transportScope").value("SUBWAY_AND_ITX_CHEONGCHUN"))
			.andExpect(jsonPath("$.data.objective").value("FASTEST"))
			.andExpect(jsonPath("$.data.itineraries[0].objectiveTags[0]").value("FASTEST"))
			.andExpect(jsonPath("$.data.itineraries[0].officialFare.adultFareWon").value(1_950))
			.andExpect(jsonPath("$.data.itineraries[0].officialFare.currency").value("KRW"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].tripId").doesNotExist())
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].serviceClass").doesNotExist())
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].tripId").value("trip-seoul-4-K4422"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].trainNo").value("K4422"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].serviceClass").value("SUBWAY"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].servicePattern").value("EXPRESS"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].plannedDepartureTime").value("2026-06-30T09:20:00+09:00"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].plannedArrivalTime").value("2026-06-30T09:30:30+09:00"));
	}

	@Test
	@DisplayName("authenticated ITX endpoint는 SUBWAY transport scope를 거부한다")
	void routeSearchV2RejectsSubwayOnlyTransportScope() throws Exception {
		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "transportScope": "SUBWAY",
					  "objective": "FASTEST",
					  "mobilityType": "STROLLER",
					  "constraintMode": "STRICT_STEP_FREE",
					  "useRealtime": false,
					  "maxTransfers": 1,
					  "alternativeCount": 1
					}
					"""))
			.andExpect(status().isBadRequest());

		verifyNoInteractions(routeSearchUseCase);
	}

	@Test
	@DisplayName("V2 경로 검색은 시간표 서비스가 없으면 NO_TIMETABLE_SERVICE status와 빈 itinerary를 반환한다")
	void routeSearchV2ReturnsNoTimetableServiceStatus() throws Exception {
		when(routeSearchUseCase.searchRouteAlternatives(argThat(command ->
			"station-no-service-origin".equals(command.originStationId())
				&& "station-no-service-destination".equals(command.destinationStationId())
		), eq(3))).thenThrow(new RouteNotFoundException());

		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-no-service-origin",
					  "destinationStationId": "station-no-service-destination",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "mobilityType": "SENIOR",
					  "constraintMode": "PREFER_STEP_FREE",
					  "useRealtime": false,
					  "maxTransfers": 1,
					  "alternativeCount": 3
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.statuses[0]").value("NO_TIMETABLE_SERVICE"))
			.andExpect(jsonPath("$.data.statuses[1]").doesNotExist())
			.andExpect(jsonPath("$.data.itineraries").isEmpty());
	}

	@Test
	@DisplayName("V2 경로 검색은 막차 이후 다음 운행 시각을 함께 반환한다")
	void routeSearchV2ReturnsNextServiceTimeAfterLastTrain() throws Exception {
		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "departureTime": "2026-07-01T23:55:00+09:00",
					  "mobilityType": "SENIOR",
					  "constraintMode": "PREFER_STEP_FREE",
					  "useRealtime": false,
					  "maxTransfers": 1,
					  "alternativeCount": 3
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.statuses[0]").value("NO_TIMETABLE_SERVICE"))
			.andExpect(jsonPath("$.data.nextServiceTime").value("2026-07-02T09:25:00+09:00"))
			.andExpect(jsonPath("$.data.plannerIdentity.timetableSnapshotSha256").value("a".repeat(64)))
			.andExpect(jsonPath("$.data.plannerIdentity.canonicalPackSha256").value("b".repeat(64)))
			.andExpect(jsonPath("$.data.plannerIdentity.canonicalStationSetSha256").value("d".repeat(64)))
			.andExpect(jsonPath("$.data.plannerIdentity.sourceLineageSha256").value("e".repeat(64)))
			.andExpect(jsonPath("$.data.itineraries").isEmpty());
	}

	@Test
	@DisplayName("V2 route refresh는 저장된 itinerary와 refresh 상태를 반환한다")
	void routeRefreshV2ReturnsRefreshStatusAndStoredRoute() throws Exception {
		when(routeSearchUseCase.refreshRoute("route-search-1"))
			.thenReturn(new RouteRefreshResult(
				"route-search-1",
				RouteRefreshStatus.STALE_FALLBACK,
				fallbackRouteSearch(),
				LocalDateTime.of(2026, 7, 1, 15, 30),
				EtaSource.FALLBACK,
				EtaConfidence.LOW,
				"최근 확인 시간이 오래되어 계획 시간으로 안내",
				List.of("STALE_FALLBACK", "STALE_ACCESSIBILITY_DATA")
			));

		mockMvc.perform(post("/api/v2/routes/route-search-1/refresh"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.routeSearchId").value("route-search-1"))
			.andExpect(jsonPath("$.data.status").value("STALE_FALLBACK"))
			.andExpect(jsonPath("$.data.etaSource").value("FALLBACK"))
			.andExpect(jsonPath("$.data.etaConfidence").value("LOW"))
			.andExpect(jsonPath("$.data.sourceLabel").value("최근 확인 시간이 오래되어 계획 시간으로 안내"))
			.andExpect(jsonPath("$.data.reasonCodes[0]").value("STALE_FALLBACK"))
			.andExpect(jsonPath("$.data.route.routeSearchId").value("route-search-1"))
			.andExpect(jsonPath("$.data.route.etaSource").value("FALLBACK"));
	}

	@Test
	@DisplayName("V2 route refresh는 없는 routeSearchId를 안정 JSON 404로 반환한다")
	void routeRefreshV2UnknownRouteSearchIdReturnsJsonNotFound() throws Exception {
		when(routeSearchUseCase.refreshRoute("route-missing"))
			.thenThrow(new com.easysubway.route.domain.RouteSearchNotFoundException());

		mockMvc.perform(post("/api/v2/routes/route-missing/refresh"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").value("경로 검색 결과를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("모바일 V2 계약으로 accessibility risk vector count를 반환한다")
	void routeSearchV2ReturnsAccessibilityRiskVectorCounts() throws Exception {
		when(routeSearchUseCase.searchRouteAlternatives(argThat(command ->
			"station-risk-origin".equals(command.originStationId())
				&& "station-risk-destination".equals(command.destinationStationId())
		), eq(1))).thenReturn(List.of(riskyRouteSearch()));

		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-risk-origin",
					  "destinationStationId": "station-risk-destination",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "mobilityType": "STROLLER",
					  "constraintMode": "ALLOW_WITH_WARNINGS",
					  "useRealtime": true,
					  "maxTransfers": 1,
					  "alternativeCount": 1
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.itineraries[0].accessibilityRisk.riskLevel").value("HIGH"))
			.andExpect(jsonPath("$.data.itineraries[0].accessibilityRisk.stairCount").value(1))
			.andExpect(jsonPath("$.data.itineraries[0].accessibilityRisk.unknownAccessibilityCount").value(1))
			.andExpect(jsonPath("$.data.itineraries[0].accessibilityRisk.generatedConnectorCount").value(0))
			.andExpect(jsonPath("$.data.itineraries[0].accessibilityRisk.staleDataCount").value(1))
			.andExpect(jsonPath("$.data.itineraries[0].accessibilityRisk.lowConfidenceCount").value(2))
			.andExpect(jsonPath("$.data.itineraries[0].accessibilityRisk.unavailableFacilityCount").value(0))
			.andExpect(jsonPath("$.data.itineraries[0].accessibilityRisk.reasonCodes[0]").value("LOW_DATA_CONFIDENCE"))
			.andExpect(jsonPath("$.data.itineraries[0].accessibilityRisk.reasonCodes[1]").value("STALE_ACCESSIBILITY_DATA"))
			.andExpect(jsonPath("$.data.itineraries[0].accessibilityRisk.reasonCodes[2]").value("ACCESSIBILITY_CHECK_REQUIRED"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].accessibilityRisk.riskLevel").value("HIGH"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].accessibilityRisk.stairCount").value(1))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].accessibilityRisk.unknownAccessibilityCount").value(0))
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].accessibilityRisk.riskLevel").value("MEDIUM"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].accessibilityRisk.stairCount").value(0))
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].accessibilityRisk.unknownAccessibilityCount").value(1));
	}

	@Test
	@DisplayName("승차 leg는 계단 개념 비적용이라 경로 계단 판정을 막지 않는다")
	void routeSearchV2RideLegDoesNotBlockStepFreeJudgment() throws Exception {
		stubStairAccessRoute("station-stepfree-origin", false, true, List.of());

		mockMvc.perform(stairAccessSearch("station-stepfree-origin"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.itineraries[0].stairAccess").value("STEP_FREE"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].stairAccess").value("STEP_FREE"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].stairAccess").value("NOT_APPLICABLE"))
			// 원자료는 그대로 둔다 — 판정 필드만 새로 싣는다.
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].accessibilityRisk.unknownAccessibilityCount").value(1));
	}

	@Test
	@DisplayName("계단 전이가 있으면 경로 계단 판정은 STAIR_ONLY다")
	void routeSearchV2StairTransitionMakesItineraryStairOnly() throws Exception {
		stubStairAccessRoute("station-stair-origin", true, true,
			List.of(new RouteWarning(RouteWarningCode.STAIR_ONLY_ACCESS)));

		mockMvc.perform(stairAccessSearch("station-stair-origin"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.itineraries[0].stairAccess").value("STAIR_ONLY"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].stairAccess").value("STAIR_ONLY"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].stairAccess").value("NOT_APPLICABLE"));
	}

	@Test
	@DisplayName("실제 미확인 사유가 있는 경로는 무단차로 승격되지 않는다")
	void routeSearchV2DoesNotPromoteUnverifiedRouteToStepFree() throws Exception {
		stubStairAccessRoute("station-unverified-origin", false, false, List.of(
			new RouteWarning(RouteWarningCode.STALE_ACCESSIBILITY_DATA),
			new RouteWarning(RouteWarningCode.LOW_DATA_CONFIDENCE)
		));

		mockMvc.perform(stairAccessSearch("station-unverified-origin"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.itineraries[0].stairAccess").value("UNKNOWN"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].stairAccess").value("UNKNOWN"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].stairAccess").value("NOT_APPLICABLE"))
			// 경로 단위 신뢰도 경고는 이 구간에서 확인한 사실을 지우지 않는다. leg마다 복제하면
			// 검증된 구간까지 미확인으로 뒤집혀 실제로 확인한 것을 잃는다(#2590).
			.andExpect(jsonPath("$.data.itineraries[0].legs[2].stairAccess").value("STEP_FREE"))
			// 접근·하차 leg는 판정과 위험 요약이 같은 근거에서 나온다.
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].accessibilityRisk.riskLevel").value("MEDIUM"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].accessibilityRisk.unknownAccessibilityCount").value(1))
			.andExpect(jsonPath("$.data.itineraries[0].legs[2].accessibilityRisk.riskLevel").value("NONE"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[2].accessibilityRisk.unknownAccessibilityCount").value(0))
			// 승차 leg는 그렇지 않다. 원자료 stairAccessState가 컬럼 기본값 "UNKNOWN"이라 카운터가
			// 1로 잡히고 riskLevel도 MEDIUM이 되지만 판정은 NOT_APPLICABLE이다. 이 어긋남이 곧
			// #2590의 원인이며, 카운터는 응답 형태를 지키려 남긴 자리다. 화면이 카운터가 아니라
			// 판정과 requiresAccessibilityCheck를 읽는 이유가 여기 있다.
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].accessibilityRisk.unknownAccessibilityCount").value(1))
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].accessibilityRisk.riskLevel").value("MEDIUM"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].requiresAccessibilityCheck").value(false))
			// 신뢰도 사유는 경로 단위 경고이므로 leg 카운터가 아니라 경로 카운터에만 잡힌다.
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].accessibilityRisk.staleDataCount").value(0))
			.andExpect(jsonPath("$.data.itineraries[0].accessibilityRisk.staleDataCount").value(1));
	}

	@Test
	@DisplayName("계단으로 확인된 구간도 근거가 없으면 확인 필요 표기를 함께 싣는다")
	void routeSearchV2CarriesAccessibilityCheckAlongsideStairJudgment() throws Exception {
		stubStairAccessRoute("station-stair-origin", true, false, List.of());

		// 계단 사실과 검증 여부는 다른 축이다. 확인 필요 표기를 계단 판정에서 파생하면 계단이 있고
		// 근거도 없는 — 가장 확인이 필요한 — 조합에서 표기가 사라진다(#2590).
		mockMvc.perform(stairAccessSearch("station-stair-origin"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].stairAccess").value("STAIR_ONLY"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].requiresAccessibilityCheck").value(true))
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].stairAccess").value("NOT_APPLICABLE"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].requiresAccessibilityCheck").value(false))
			.andExpect(jsonPath("$.data.itineraries[0].legs[2].stairAccess").value("STEP_FREE"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[2].requiresAccessibilityCheck").value(false));
	}

	@Test
	@DisplayName("경로 계단 판정은 leg 판정을 접은 값보다 덜 신중해지지 않는다")
	void routeSearchV2ItineraryJudgmentIsNeverLessCautiousThanFoldedLegs() throws Exception {
		stubStairAccessRoute("station-stepfree-origin", false, true, List.of());
		stubStairAccessRoute("station-stair-origin", true, true,
			List.of(new RouteWarning(RouteWarningCode.STAIR_ONLY_ACCESS)));
		stubStairAccessRoute("station-unverified-origin", false, false,
			List.of(new RouteWarning(RouteWarningCode.STALE_ACCESSIBILITY_DATA)));

		for (String originStationId : List.of(
			"station-stepfree-origin", "station-stair-origin", "station-unverified-origin"
		)) {
			JsonNode itinerary = objectMapper.readTree(
				mockMvc.perform(stairAccessSearch(originStationId))
					.andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString()
			).at("/data/itineraries/0");
			List<StairAccess> legJudgments = new ArrayList<>();
			itinerary.at("/legs").forEach(leg -> legJudgments.add(StairAccess.valueOf(leg.get("stairAccess").asText())));
			StairAccess judged = StairAccess.valueOf(itinerary.get("stairAccess").asText());

			// 일치는 계약이 아니다. 보장하는 것은 한 방향 — 경로 판정이 leg를 접은 값보다
			// 덜 신중해지지 않는다. 판정 필드를 잃은 화면이 leg로 폴백해도 표시가 실제 근거보다
			// 강해질 수 없다는 뜻이다.
			assertThat(judged.merge(StairAccess.ofStepJudgments(legJudgments))).as(originStationId).isEqualTo(judged);
		}
	}

	@Test
	@DisplayName("계단 판정이 없는 BLOCKED 경로는 계단 경고를 그대로 말한다")
	void routeSearchV2BlockedItineraryReportsStairOnly() throws Exception {
		when(routeSearchUseCase.searchRouteAlternatives(argThat(command ->
			"station-blocked-origin".equals(command.originStationId())
		), eq(1))).thenReturn(List.of(blockedStairAccessRouteSearch()));

		mockMvc.perform(stairAccessSearch("station-blocked-origin"))
			.andExpect(status().isOk())
			// leg가 없어 접을 근거가 없지만 STAIR_ONLY_ACCESS 경고는 경로 단위 사실이다.
			.andExpect(jsonPath("$.data.itineraries[0].status").value("BLOCKED_ACCESSIBILITY"))
			.andExpect(jsonPath("$.data.itineraries[0].legs").isEmpty())
			.andExpect(jsonPath("$.data.itineraries[0].stairAccess").value("STAIR_ONLY"));
	}

	@Test
	@DisplayName("V2 leg ETA source와 confidence는 step data에서 파생한다")
	void routeSearchV2MapsLegEtaSourceAndConfidence() throws Exception {
		when(routeSearchUseCase.searchRouteAlternatives(argThat(command ->
			command != null && "station-realtime-origin".equals(command.originStationId())
		), eq(1))).thenReturn(List.of(realtimeRouteSearch()));

		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-realtime-origin",
					  "destinationStationId": "station-sadang",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "mobilityType": "STROLLER",
					  "constraintMode": "ALLOW_WITH_WARNINGS",
					  "useRealtime": true,
					  "maxTransfers": 1,
					  "alternativeCount": 1
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.itineraries[0].etaSource").value("REALTIME"))
			.andExpect(jsonPath("$.data.itineraries[0].etaConfidence").value("HIGH"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].etaSource").value("REALTIME"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].confidence").value("HIGH"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].reasonCodes[0]").value("MATCHED_REALTIME"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].providerSnapshotId").value("seoul-topis:2026-06-30T00:14:30Z"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].providerObservedAt").value("2026-06-30T00:14:20Z"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].gatewayReceivedAt").value("2026-06-30T00:14:30Z"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].servedAt").value("2026-06-30T00:15:00Z"));
	}

	@Test
	@DisplayName("V2 첫 탑승과 환승 후 탑승은 이동약자 준비시간 이후로 계산한다")
	void routeSearchV2AppliesBoardingAndTransferSlackBeforeRideLegs() throws Exception {
		when(routeSearchUseCase.searchRouteAlternatives(argThat(command ->
			command != null && "station-boarding-origin".equals(command.originStationId())
		), eq(1))).thenReturn(List.of(boardingTransferRouteSearch()));

		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-boarding-origin",
					  "destinationStationId": "station-boarding-destination",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "mobilityType": "STROLLER",
					  "constraintMode": "PREFER_STEP_FREE",
					  "useRealtime": true,
					  "maxTransfers": 1,
					  "alternativeCount": 1
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.itineraries[0].plannedArrivalTime").value("2026-06-30T09:33:00+09:00"))
			.andExpect(jsonPath("$.data.itineraries[0].durationSeconds").value(1080))
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].plannedDepartureTime").value("2026-06-30T09:21:00+09:00"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].waitTimeSeconds").value(120))
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].slackSeconds").value(120))
			.andExpect(jsonPath("$.data.itineraries[0].legs[3].plannedDepartureTime").value("2026-06-30T09:28:00+09:00"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[3].waitTimeSeconds").value(120))
			.andExpect(jsonPath("$.data.itineraries[0].legs[3].slackSeconds").value(120));
	}

	@Test
	@DisplayName("V2 큰 짐 이동은 탑승 준비시간 60초를 적용한다")
	void routeSearchV2AppliesLuggageBoardingSlack() throws Exception {
		when(routeSearchUseCase.searchRouteAlternatives(argThat(command ->
			command != null && command.mobilityType() == MobilityType.LUGGAGE
		), eq(1))).thenReturn(List.of(boardingTransferRouteSearch(MobilityType.LUGGAGE)));

		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-boarding-origin",
					  "destinationStationId": "station-boarding-destination",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "mobilityType": "LUGGAGE",
					  "constraintMode": "PREFER_STEP_FREE",
					  "useRealtime": true,
					  "maxTransfers": 1,
					  "alternativeCount": 1
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.itineraries[0].plannedArrivalTime").value("2026-06-30T09:31:00+09:00"))
			.andExpect(jsonPath("$.data.itineraries[0].durationSeconds").value(960))
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].waitTimeSeconds").value(60))
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].slackSeconds").value(60))
			.andExpect(jsonPath("$.data.itineraries[0].legs[3].waitTimeSeconds").value(60))
			.andExpect(jsonPath("$.data.itineraries[0].legs[3].slackSeconds").value(60));
	}

	@Test
	@DisplayName("모바일 V2 계약의 blocked reasonCodes는 사용자 문장 대신 안정적인 코드만 반환한다")
	void routeSearchV2BlockedRiskReasonCodesAreStableCodes() throws Exception {
		when(routeSearchUseCase.searchRouteAlternatives(argThat(command ->
			"station-blocked-origin".equals(command.originStationId())
				&& "station-blocked-destination".equals(command.destinationStationId())
		), eq(1))).thenReturn(List.of(blockedRouteSearch()));

		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-blocked-origin",
					  "destinationStationId": "station-blocked-destination",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "mobilityType": "WHEELCHAIR",
					  "constraintMode": "STRICT_STEP_FREE",
					  "useRealtime": true,
					  "maxTransfers": 1,
					  "alternativeCount": 1
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.itineraries[0].status").value("BLOCKED_ACCESSIBILITY"))
			.andExpect(jsonPath("$.data.itineraries[0].accessibilityRisk.riskLevel").value("BLOCKED"))
			.andExpect(jsonPath("$.data.itineraries[0].accessibilityRisk.reasonCodes[0]").value("BLOCKED_ACCESSIBILITY"))
			.andExpect(jsonPath("$.data.itineraries[0].accessibilityRisk.reasonCodes[1]").doesNotExist());
	}

	@Test
	@DisplayName("잘못된 V2 출발 시간은 search 저장 전에 JSON 400으로 거부한다")
	void invalidRouteSearchV2DepartureTimeReturnsBadRequestBeforeSearch() throws Exception {
		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "departureTime": "2026-99-99T09:15:00+09:00",
					  "mobilityType": "STROLLER",
					  "constraintMode": "STRICT_STEP_FREE",
					  "useRealtime": true,
					  "maxTransfers": 3,
					  "alternativeCount": 3
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").value("출발 시간은 ISO offset 형식이어야 합니다."));

		verifyNoInteractions(routeSearchUseCase);
	}

	@Test
	@DisplayName("V2 최대 환승 수는 3회를 초과하면 search 저장 전에 JSON 400으로 거부한다")
	void routeSearchV2MaxTransfersAboveThreeReturnsBadRequestBeforeSearch() throws Exception {
		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "mobilityType": "STROLLER",
					  "constraintMode": "STRICT_STEP_FREE",
					  "useRealtime": true,
					  "maxTransfers": 4,
					  "alternativeCount": 3
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").exists());

		verifyNoInteractions(routeSearchUseCase);
	}

	@Test
	@DisplayName("V2 최대 환승 수 누락은 search 저장 전에 JSON 400으로 거부한다")
	void routeSearchV2MissingMaxTransfersReturnsBadRequestBeforeSearch() throws Exception {
		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "mobilityType": "STROLLER",
					  "constraintMode": "STRICT_STEP_FREE",
					  "useRealtime": true,
					  "alternativeCount": 3
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").exists());

		verifyNoInteractions(routeSearchUseCase);
	}

	@Test
	@DisplayName("V2 대안 경로 수는 3개를 초과하면 search 저장 전에 JSON 400으로 거부한다")
	void routeSearchV2AlternativeCountAboveThreeReturnsBadRequestBeforeSearch() throws Exception {
		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "mobilityType": "STROLLER",
					  "constraintMode": "STRICT_STEP_FREE",
					  "useRealtime": true,
					  "maxTransfers": 3,
					  "alternativeCount": 4
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").exists());

		verifyNoInteractions(routeSearchUseCase);
	}

	@Test
	@DisplayName("V2 prefer step-free는 mobility type을 유지한 채 command에 전달한다")
	void routeSearchV2PreferStepFreeKeepsMobilityType() throws Exception {
		when(routeSearchUseCase.searchRouteAlternatives(argThat(command ->
			command.mobilityType() == MobilityType.STROLLER
				&& command.constraintMode() == ConstraintMode.PREFER_STEP_FREE
		), eq(3))).thenReturn(List.of(foundRouteSearch()));

		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "mobilityType": "STROLLER",
					  "constraintMode": "PREFER_STEP_FREE",
					  "useRealtime": true,
					  "maxTransfers": 3,
					  "alternativeCount": 3
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.constraintMode").value("PREFER_STEP_FREE"));
	}

	@Test
	@DisplayName("V2 PROFILE_DEFAULT는 기존 client 호환을 위해 mobility type 기본 constraint로 처리한다")
	void routeSearchV2ProfileDefaultUsesMobilityTypeDefaultConstraintMode() throws Exception {
		when(routeSearchUseCase.searchRouteAlternatives(argThat(command ->
			command.mobilityType() == MobilityType.STROLLER
				&& command.constraintMode() == ConstraintMode.PREFER_STEP_FREE
		), eq(3))).thenReturn(List.of(foundRouteSearch()));

		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "mobilityType": "STROLLER",
					  "constraintMode": "PROFILE_DEFAULT",
					  "useRealtime": true,
					  "maxTransfers": 3,
					  "alternativeCount": 3
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.constraintMode").value("PROFILE_DEFAULT"));
	}

	@Test
	@DisplayName("V2 명시적 mobilityPreset은 응답 계약에 그대로 노출한다")
	void routeSearchV2EchoesExplicitMobilityPreset() throws Exception {
		when(routeSearchUseCase.searchRouteAlternatives(argThat(command ->
			command.mobilityType() == MobilityType.STROLLER
				&& command.constraintMode() == ConstraintMode.STRICT_STEP_FREE
		), eq(1))).thenReturn(List.of(foundRouteSearch()));

		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "mobilityType": "STROLLER",
					  "mobilityPreset": "STEP_FREE",
					  "constraintMode": "STRICT_STEP_FREE",
					  "useRealtime": true,
					  "maxTransfers": 1,
					  "alternativeCount": 1
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.mobilityPreset").value("STEP_FREE"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].walkSeconds").value(240))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].timeSource").value("MEASURED_PATHWAY"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].appliedPreset").value("STEP_FREE"));
	}

	@Test
	@DisplayName("V2 TIMETABLE entry leg는 대기 포함 분이 아니라 순수 보행 초를 노출한다")
	void routeSearchV2DoesNotExposeTimetableWaitAsWalkSeconds() throws Exception {
		when(routeSearchUseCase.searchRouteAlternatives(argThat(command ->
			command.mobilityType() == MobilityType.STROLLER
				&& command.constraintMode() == ConstraintMode.STRICT_STEP_FREE
		), eq(1))).thenReturn(List.of(foundTimetableRouteSearch()));

		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "mobilityType": "STROLLER",
					  "mobilityPreset": "STEP_FREE",
					  "constraintMode": "STRICT_STEP_FREE",
					  "useRealtime": true,
					  "maxTransfers": 1,
					  "alternativeCount": 1
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].walkSeconds").value(160))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].timeSource").value("OFFICIAL_BASELINE"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].appliedPreset").value("STEP_FREE"));
	}

	@Test
	@DisplayName("V2 TIMETABLE egress leg는 반올림된 분이 아니라 순수 보행 초를 노출한다")
	void routeSearchV2ExposesTimetableEgressWalkSeconds() throws Exception {
		when(routeSearchUseCase.searchRouteAlternatives(argThat(command ->
			command.mobilityType() == MobilityType.STROLLER
				&& command.constraintMode() == ConstraintMode.STRICT_STEP_FREE
		), eq(1))).thenReturn(List.of(foundTimetableEgressRouteSearch()));

		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "mobilityType": "STROLLER",
					  "mobilityPreset": "STEP_FREE",
					  "constraintMode": "STRICT_STEP_FREE",
					  "useRealtime": true,
					  "maxTransfers": 1,
					  "alternativeCount": 1
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].walkSeconds").value(160))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].timeSource").value("OFFICIAL_BASELINE"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].appliedPreset").value("STEP_FREE"));
	}

	@Test
	@DisplayName("strict V2 RAPTOR 요청은 명시적 mobilityPreset을 허용한다")
	void strictRouteSearchV2AllowsExplicitMobilityPresetOnRaptor() throws Exception {
		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "mobilityType": "STROLLER",
					  "mobilityPreset": "STANDARD",
					  "constraintMode": "STRICT_STEP_FREE",
					  "useRealtime": true,
					  "maxTransfers": 1,
					  "alternativeCount": 1
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.mobilityPreset").value("STANDARD"))
			.andExpect(jsonPath("$.data.statuses[0]").value("FOUND"));
	}

	@Test
	@DisplayName("알 수 없는 V2 mobilityPreset은 search 저장 전에 JSON 400으로 거부한다")
	void unknownRouteSearchV2MobilityPresetReturnsBadRequestBeforeSearch() throws Exception {
		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "mobilityType": "STROLLER",
					  "mobilityPreset": "FAST",
					  "constraintMode": "STRICT_STEP_FREE",
					  "useRealtime": true,
					  "maxTransfers": 1,
					  "alternativeCount": 1
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").value("보행 프리셋을 확인해야 합니다."));

		verifyNoInteractions(routeSearchUseCase);
	}

	@Test
	@DisplayName("V2 allow-with-warnings는 constraintMode를 command와 응답에 반영한다")
	void routeSearchV2AllowWithWarningsKeepsConstraintMode() throws Exception {
		when(routeSearchUseCase.searchRouteAlternatives(argThat(command ->
			command.mobilityType() == MobilityType.STROLLER
				&& command.constraintMode() == ConstraintMode.ALLOW_WITH_WARNINGS
		), eq(3))).thenReturn(List.of(foundRouteSearch()));

		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "mobilityType": "STROLLER",
					  "constraintMode": "ALLOW_WITH_WARNINGS",
					  "useRealtime": true,
					  "maxTransfers": 3,
					  "alternativeCount": 3
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.constraintMode").value("ALLOW_WITH_WARNINGS"));
	}

	@Test
	@DisplayName("알 수 없는 V2 constraintMode는 search 저장 전에 JSON 400으로 거부한다")
	void unknownRouteSearchV2ConstraintModeReturnsBadRequestBeforeSearch() throws Exception {
		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "mobilityType": "STROLLER",
					  "constraintMode": "STAIRS_ARE_FINE",
					  "useRealtime": true,
					  "maxTransfers": 3,
					  "alternativeCount": 3
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").value("지원하지 않는 이동 제약 조건입니다."));

		verifyNoInteractions(routeSearchUseCase);
	}

	private RouteSearchResult foundRouteSearch() {
		return new RouteSearchResult(
			"route-search-1",
			"station-sangnoksu",
			"상록수",
			"station-sadang",
			"사당",
			MobilityType.STROLLER,
			RouteSearchStatus.FOUND,
			"line-4",
			"수도권 4호선",
			18,
			List.of(new RouteStep(
				1,
				"entry",
				"상록수역 진입",
				"엘리베이터를 이용해 승강장으로 이동",
				"line-4",
				"수도권 4호선",
				"station-sangnoksu",
				"station-sangnoksu",
				4,
				180,
				false,
				"VERIFIED",
				false,
				"STATIC_BACKEND_V1",
				"STATIC_BACKEND_V1",
				"LEGACY_STATIC"
			), new RouteStep(
				2,
				"exit",
				"사당역 출구 이동",
				"출구 엘리베이터를 확인합니다.",
				"line-4",
				"수도권 4호선",
				"station-sadang",
				"station-sadang",
				3,
				120,
				false,
				"VERIFIED",
				false,
				"STATIC_BACKEND_V1",
				"STATIC_BACKEND_V1",
				"LEGACY_STATIC"
			)),
			List.of(),
			List.of(),
			LocalDateTime.of(2026, 6, 30, 9, 0)
		);
	}

	private RouteSearchResult foundTypedTimetableRouteSearch() {
		return new RouteSearchResult(
			"route-search-typed",
			"station-sangnoksu",
			"상록수",
			"station-sadang",
			"사당",
			MobilityType.STROLLER,
			RouteSearchStatus.FOUND,
			"line-4",
			"수도권 4호선",
			18,
			List.of(
				new RouteStep(1, "entry", "진입", "진입", "line-4", "수도권 4호선",
					"station-sangnoksu", "station-sangnoksu", 5, 180, false, "UNKNOWN", true,
					"PLANNED", "TIMETABLE", "LOW", List.of(), null, null, null, null, 240,
					null, null, null, null, "2026-06-30T09:15:00+09:00", "2026-06-30T09:20:00+09:00"),
				new RouteStep(2, "ride", "승차", "승차", "line-4", "수도권 4호선",
					"station-sangnoksu", "station-sadang", 11, 0, false, "UNKNOWN", false,
					"PLANNED", "TIMETABLE", "LOW", List.of(), null, null, null, null, null,
					"trip-seoul-4-K4422", "K4422", "SUBWAY", "EXPRESS",
					"2026-06-30T09:20:00+09:00", "2026-06-30T09:30:30+09:00"),
				new RouteStep(3, "exit", "하차", "하차", "line-4", "수도권 4호선",
					"station-sadang", "station-sadang", 3, 120, false, "UNKNOWN", true,
					"PLANNED", "TIMETABLE", "LOW", List.of(), null, null, null, null, 180,
					null, null, null, null, "2026-06-30T09:30:30+09:00", "2026-06-30T09:33:30+09:00")
			),
			List.of(),
			List.of(),
			LocalDateTime.of(2026, 6, 30, 9, 15),
			List.of("FASTEST"),
			new RouteSearchResult.OfficialFare(
				1_950,
				"KRW",
				"SUM_OF_OFFICIAL_RIDE_OD_FARES",
				List.of("seoul-metro-official-od-fares"),
				List.of("seoul-metro-official-od-fares-20260712")
			)
		);
	}

	private RouteSearchResult fallbackRouteSearch() {
		return new RouteSearchResult(
			"route-search-1",
			"station-sangnoksu",
			"상록수",
			"station-sadang",
			"사당",
			MobilityType.STROLLER,
			RouteSearchStatus.FOUND,
			"line-4",
			"수도권 4호선",
			18,
			List.of(new RouteStep(
				1,
				"ride",
				"수도권 4호선으로 이동",
				"열차로 이동합니다.",
				"line-4",
				"수도권 4호선",
				"station-sangnoksu",
				"station-sadang",
				7,
				1800,
				false,
				"VERIFIED",
				false,
				"FALLBACK",
				"ESTIMATED_CONSTANT",
				"LOW"
			)),
			List.of(new RouteWarning(RouteWarningCode.STALE_ACCESSIBILITY_DATA)),
			List.of(),
			LocalDateTime.of(2026, 6, 30, 9, 0)
		);
	}

	private RouteSearchResult foundTimetableRouteSearch() {
		return new RouteSearchResult(
			"route-search-1",
			"station-sangnoksu",
			"상록수",
			"station-sadang",
			"사당",
			MobilityType.STROLLER,
			RouteSearchStatus.FOUND,
			"line-4",
			"수도권 4호선",
			18,
			List.of(new RouteStep(
				1,
				"entry",
				"상록수역 진입",
				"시간표 경로의 승하차 접근성과 환승 동선을 확인합니다.",
				"line-4",
				"수도권 4호선",
				"station-sangnoksu",
				"station-sangnoksu",
				9,
				180,
				false,
				"UNKNOWN",
				true,
				"PLANNED",
				"TIMETABLE",
				"시간표",
				List.of(),
				null,
				null,
				null,
				null,
				160
			)),
			List.of(),
			List.of(),
			LocalDateTime.of(2026, 6, 30, 9, 0)
		);
	}

	private RouteSearchResult foundTimetableEgressRouteSearch() {
		return new RouteSearchResult(
			"route-search-1",
			"station-sangnoksu",
			"상록수",
			"station-sadang",
			"사당",
			MobilityType.STROLLER,
			RouteSearchStatus.FOUND,
			"line-4",
			"수도권 4호선",
			18,
			List.of(new RouteStep(
				1,
				"exit",
				"사당역 출구 이동",
				"시간표 경로의 승하차 접근성과 환승 동선을 확인합니다.",
				"line-4",
				"수도권 4호선",
				"station-sadang",
				"station-sadang",
				3,
				120,
				false,
				"UNKNOWN",
				true,
				"PLANNED",
				"TIMETABLE",
				"시간표",
				List.of(),
				null,
				null,
				null,
				null,
				160
			)),
			List.of(),
			List.of(),
			LocalDateTime.of(2026, 6, 30, 9, 0)
		);
	}

	private RouteSearchResult riskyRouteSearch() {
		return new RouteSearchResult(
			"route-search-risk",
			"station-risk-origin",
			"위험 출발역",
			"station-risk-destination",
			"위험 도착역",
			MobilityType.STROLLER,
			RouteSearchStatus.FOUND,
			"line-risk",
			"위험 노선",
			42,
			List.of(
				new RouteStep(
					1,
					"entry",
					"계단 포함 진입",
					"계단과 확인 필요 구간을 포함합니다.",
					"line-risk",
					"위험 노선",
					"station-risk-origin",
					"station-risk-origin",
					5,
					90,
					true,
					"STAIR_ONLY",
					true,
					"STATIC_BACKEND_V1",
					"STATIC_BACKEND_V1",
					"LOW_CONFIDENCE"
				),
				new RouteStep(
					2,
					"exit",
					"확인 필요 출구",
					"계단 없는 길인지 추가 확인이 필요합니다.",
					"line-risk",
					"위험 노선",
					"station-risk-destination",
					"station-risk-destination",
					3,
					60,
					false,
					"UNKNOWN",
					true,
					"STATIC_BACKEND_V1",
					"STATIC_BACKEND_V1",
					"LOW_CONFIDENCE"
				)
			),
			List.of(
				new RouteWarning(RouteWarningCode.LOW_DATA_CONFIDENCE),
				new RouteWarning(RouteWarningCode.LOW_DATA_CONFIDENCE),
				new RouteWarning(RouteWarningCode.STALE_ACCESSIBILITY_DATA)
			),
			List.of(),
			LocalDateTime.of(2026, 6, 30, 9, 0)
		);
	}

	// 여러 출발지를 한 테스트에서 스텁하면 뒤이은 when() 호출이 null 인자로 mock을 건드리므로
	// matcher가 null을 견뎌야 한다.
	private void stubStairAccessRoute(
		String originStationId,
		boolean stairEntry,
		boolean entryVerified,
		List<RouteWarning> warnings
	) {
		when(routeSearchUseCase.searchRouteAlternatives(argThat(command ->
			command != null && originStationId.equals(command.originStationId())
		), eq(1))).thenReturn(List.of(stairAccessRouteSearch(originStationId, stairEntry, entryVerified, warnings)));
	}

	private RequestBuilder stairAccessSearch(String originStationId) {
		return post("/api/v2/routes/search")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{
				  "originStationId": "%s",
				  "destinationStationId": "station-stairaccess-destination",
				  "departureTime": "2026-06-30T09:15:00+09:00",
				  "mobilityType": "WHEELCHAIR",
				  "constraintMode": "ALLOW_WITH_WARNINGS",
				  "useRealtime": false,
				  "maxTransfers": 1,
				  "alternativeCount": 1
				}
				""".formatted(originStationId));
	}

	// 접근성 차단 경로는 스텝 없이 STAIR_ONLY_ACCESS 경고만 남는다(RouteSearchService).
	private RouteSearchResult blockedStairAccessRouteSearch() {
		return new RouteSearchResult(
			"route-search-stair-access-blocked",
			"station-blocked-origin",
			"판정 출발역",
			"station-stairaccess-destination",
			"판정 도착역",
			MobilityType.WHEELCHAIR,
			RouteSearchStatus.BLOCKED,
			"line-stair-access",
			"판정 노선",
			0,
			List.of(),
			List.of(new RouteWarning(RouteWarningCode.STAIR_ONLY_ACCESS)),
			List.of("계단 없는 역 접근 경로를 확인할 수 없습니다."),
			LocalDateTime.of(2026, 6, 30, 9, 0)
		);
	}

	// 접근(전이) → 승차 → 하차(전이) 3단 경로. 승차 step은 실제 플래너와 같은 형태로
	// includesStairs=false · stairAccessState="UNKNOWN" · requiresAccessibilityCheck=false다.
	//
	// 신뢰도 경고를 붙일 때는 entryVerified=false와 짝지어야 플래너가 실제로 낼 수 있는 형태가
	// 된다. RAPTOR의 전이 검증은 `"VERIFIED".equals(status) && (warningCodes & (LOW|STALE)) == 0`이고
	// 경로 경고는 실제로 지난 전이들의 warningCodes를 OR한 것이라, LOW/STALE을 낸 전이는 반드시
	// 미검증 step이 된다.
	private RouteSearchResult stairAccessRouteSearch(
		String originStationId,
		boolean stairEntry,
		boolean entryVerified,
		List<RouteWarning> warnings
	) {
		return new RouteSearchResult(
			"route-search-stair-access",
			originStationId,
			"판정 출발역",
			"station-stairaccess-destination",
			"판정 도착역",
			MobilityType.WHEELCHAIR,
			RouteSearchStatus.FOUND,
			"line-stair-access",
			"판정 노선",
			12,
			List.of(
				stairAccessTransitionStep(1, "entry", originStationId, stairEntry, entryVerified),
				new RouteStep(
					2,
					"ride",
					"판정 노선 승차",
					"시간표 기준으로 이동",
					"line-stair-access",
					"판정 노선",
					originStationId,
					"station-stairaccess-destination",
					6,
					0,
					false,
					"UNKNOWN",
					false,
					"PLANNED",
					"TIMETABLE",
					"시간표",
					List.of(),
					null,
					null,
					null,
					null
				),
				stairAccessTransitionStep(3, "exit", "station-stairaccess-destination", false, true)
			),
			warnings,
			List.of(),
			LocalDateTime.of(2026, 6, 30, 9, 0)
		);
	}

	// RouteTimetableRaptorPlanner.timetableAccessStep()과 같은 파생을 그대로 쓴다.
	private RouteStep stairAccessTransitionStep(
		int sequence,
		String stepType,
		String stationId,
		boolean includesStairs,
		boolean verified
	) {
		return new RouteStep(
			sequence,
			stepType,
			"판정 노선 접근 동선 확인",
			"시간표 경로의 승하차 접근성과 환승 동선을 확인합니다.",
			"line-stair-access",
			"판정 노선",
			stationId,
			stationId,
			2,
			40,
			includesStairs,
			includesStairs ? "STAIR_ONLY" : verified ? "STEP_FREE" : "UNKNOWN",
			!verified,
			"PLANNED",
			"TIMETABLE",
			verified ? "검증됨" : "확인 필요",
			List.of(),
			null,
			null,
			null,
			null,
			60
		);
	}

	private RouteSearchResult realtimeRouteSearch() {
		return new RouteSearchResult(
			"route-search-realtime",
			"station-realtime-origin",
			"실시간 출발역",
			"station-sadang",
			"사당",
			MobilityType.STROLLER,
			RouteSearchStatus.FOUND,
			"line-4",
			"수도권 4호선",
			18,
			List.of(new RouteStep(
				1,
				"ride",
				"실시간 열차",
				"실시간 도착 후보를 반영합니다.",
				"line-4",
				"수도권 4호선",
				"station-realtime-origin",
				"station-sadang",
				3,
				1800,
				false,
				"VERIFIED",
				false,
				"REALTIME",
				"ESTIMATED_CONSTANT",
				"HIGH",
				List.of("MATCHED_REALTIME"),
				"seoul-topis:2026-06-30T00:14:30Z",
				"2026-06-30T00:14:20Z",
				"2026-06-30T00:14:30Z",
				"2026-06-30T00:15:00Z"
			)),
			List.of(),
			List.of(),
			LocalDateTime.of(2026, 6, 30, 9, 0)
		);
	}

	private RouteSearchResult boardingTransferRouteSearch() {
		return boardingTransferRouteSearch(MobilityType.STROLLER);
	}

	private RouteSearchResult boardingTransferRouteSearch(MobilityType mobilityType) {
		return new RouteSearchResult(
			"route-search-boarding-transfer",
			"station-boarding-origin",
			"탑승 출발역",
			"station-boarding-destination",
			"탑승 도착역",
			mobilityType,
			RouteSearchStatus.FOUND,
			"line-boarding",
			"탑승 노선",
			20,
			List.of(
				new RouteStep(
					1,
					"entry",
					"출발역 승강장 이동",
					"엘리베이터를 이용해 승강장으로 이동합니다.",
					"line-boarding-a",
					"탑승 A 노선",
					"station-boarding-origin",
					"station-boarding-origin",
					4,
					180,
					false,
					false
				),
				new RouteStep(
					2,
					"ride",
					"A 노선 탑승",
					"첫 열차 탑승 시간을 반영합니다.",
					"line-boarding-a",
					"탑승 A 노선",
					"station-boarding-origin",
					"station-transfer",
					3,
					900,
					false,
					false
				),
				new RouteStep(
					3,
					"transfer",
					"환승 승강장 이동",
					"환승 동선 시간을 반영합니다.",
					"line-boarding-b",
					"탑승 B 노선",
					"station-transfer",
					"station-transfer",
					2,
					120,
					false,
					false
				),
				new RouteStep(
					4,
					"ride",
					"B 노선 탑승",
					"환승 후 열차 탑승 시간을 반영합니다.",
					"line-boarding-b",
					"탑승 B 노선",
					"station-transfer",
					"station-boarding-destination",
					4,
					1200,
					false,
					false
				),
				new RouteStep(
					5,
					"exit",
					"도착역 출구 이동",
					"출구 동선을 반영합니다.",
					"line-boarding-b",
					"탑승 B 노선",
					"station-boarding-destination",
					"station-boarding-destination",
					1,
					80,
					false,
					false
				)
			),
			List.of(),
			List.of(),
			LocalDateTime.of(2026, 6, 30, 9, 0)
		);
	}

	private RouteSearchResult blockedRouteSearch() {
		return new RouteSearchResult(
			"route-search-blocked",
			"station-blocked-origin",
			"차단 출발역",
			"station-blocked-destination",
			"차단 도착역",
			MobilityType.WHEELCHAIR,
			RouteSearchStatus.BLOCKED,
			"line-blocked",
			"차단 노선",
			0,
			List.of(),
			List.of(),
			List.of("계단 없는 역 접근 경로를 확인할 수 없습니다."),
			LocalDateTime.of(2026, 6, 30, 9, 0)
		);
	}
}
