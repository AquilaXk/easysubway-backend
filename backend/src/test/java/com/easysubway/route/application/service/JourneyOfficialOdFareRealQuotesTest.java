package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.ActiveJourneySnapshotPort.ActiveJourneySnapshot;
import com.easysubway.journey.application.ActiveJourneySnapshotPort.ActiveServingEvidence;
import com.easysubway.journey.application.ActiveJourneySnapshotPort.SnapshotBoundaryReceipt;
import com.easysubway.journey.application.ActiveJourneySnapshotPort.SnapshotMeasurementReceipt;
import com.easysubway.journey.application.JourneyCandidate;
import com.easysubway.journey.application.JourneyCandidate.FareStatus;
import com.easysubway.journey.application.JourneyProfileRaptorPort;
import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.JourneyRequestMeasurement;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실제 공식 O-D 운임 행(data 저장소 {@code tools/datapack/official-od-fare-quotes.json}, 커밋 ba1353dd)을
 * 그대로 적재한 런타임으로 point·profile 여정 계획을 실행한다. 시간표는 해당 O-D 한 구간만 있는 테스트 고정값이며,
 * 운임 조회는 첫 승차역과 최종 하차역만 쓰므로 운임 결과는 실제 표의 행만으로 결정된다.
 */
@DisplayName("Journey V3 official O-D fare against the real bounded quote rows")
class JourneyOfficialOdFareRealQuotesTest {

	private static final String RESOURCE = "/route/fare/official-od-fare-quotes.json";
	private static final String RESOURCE_SHA256 = "2cb76dab762ee36ac3689074953d4061af807d4a3ea7c8452b75c6aa4a5cd4c4";
	private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
	private static final String ROUTE_BUNDLE_SHA = "a".repeat(64);
	private static final long GENERATION = 7;
	private static final Instant EFFECTIVE = Instant.parse("2026-06-30T23:50:00Z");
	private static final Instant VALID_UNTIL = Instant.parse("2026-07-01T02:00:00Z");
	private static final ObjectMapper JSON = new ObjectMapper();

	@Test
	@DisplayName("Fixture is the byte-exact six-row data repository artifact")
	void fixtureIsTheByteExactSixRowDataRepositoryArtifact() throws Exception {
		byte[] bytes = resourceBytes();

		assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
			.isEqualTo(RESOURCE_SHA256);
		JsonNode root = JSON.readTree(bytes);
		assertThat(root.path("artifactKind").asText()).isEqualTo("official-od-fare-bounded-quotes");
		assertThat(root.path("quotes")).hasSize(6);
		assertThat(realQuotes()).hasSize(6);
	}

	@Test
	@DisplayName("station-sangnoksu -> station-sadang returns the official Seoul Metro quote on point and profile paths")
	void pinsSangnoksuToSadangOfficialQuoteOnPointAndProfilePaths() throws Exception {
		var runtime = runtime("station-sangnoksu", "station-sadang", realQuotes());

		var pointFare = pointFare(runtime, "station-sangnoksu", "station-sadang");
		var profileFares = profileFares(runtime, "station-sangnoksu", "station-sadang");

		assertThat(pointFare).isEqualTo(new JourneyCandidate.Fare(
			FareStatus.AVAILABLE, 1950, 2050, 1220, 2050, 750, 750,
			List.of("seoul-metro-official-od-fares-20260712")));
		assertThat(profileFares).isNotEmpty().containsOnly(pointFare);
	}

	@Test
	@DisplayName("Every real row is served exactly and O-D pairs outside the six rows stay UNAVAILABLE")
	void servesEveryRealRowExactlyAndLeavesUnlistedPairsUnavailable() throws Exception {
		Map<String, OfficialFareQuote> quotes = realQuotes();
		List<String> report = new ArrayList<>();

		for (OfficialFareQuote quote : quotes.values()) {
			var runtime = runtime(quote.originStationId(), quote.destinationStationId(), quotes);
			var pointFare = pointFare(runtime, quote.originStationId(), quote.destinationStationId());
			var profileFares = profileFares(runtime, quote.originStationId(), quote.destinationStationId());

			assertThat(pointFare).isEqualTo(new JourneyCandidate.Fare(
				FareStatus.AVAILABLE, quote.gnrlCardFare(), quote.gnrlCashFare(), quote.yungCardFare(),
				quote.yungCashFare(), quote.childCardFare(), quote.childCashFare(), List.of(quote.snapshotId())));
			assertThat(profileFares).isNotEmpty().containsOnly(pointFare);
			report.add(row(quote.originStationId(), quote.destinationStationId(), pointFare, profileFares.getFirst()));
		}

		for (List<String> unlisted : List.of(
			List.of("station-sangnoksu", "station-a2d54a5d63d2"),
			List.of("station-dd45c69d3e40", "station-fcb7a21e5606"))) {
			var runtime = runtime(unlisted.get(0), unlisted.get(1), quotes);
			var pointFare = pointFare(runtime, unlisted.get(0), unlisted.get(1));
			var profileFares = profileFares(runtime, unlisted.get(0), unlisted.get(1));

			assertThat(pointFare).isEqualTo(JourneyCandidate.Fare.unavailable());
			assertThat(profileFares).isNotEmpty().containsOnly(JourneyCandidate.Fare.unavailable());
			report.add(row(unlisted.get(0), unlisted.get(1), pointFare, profileFares.getFirst()));
		}

		report.forEach(line -> System.out.println("OD-FARE-TABLE " + line));
		assertThat(report).hasSize(8);
	}

	private static String row(
		String origin, String destination, JourneyCandidate.Fare point, JourneyCandidate.Fare profile
	) {
		return "| " + origin + " -> " + destination + " | " + describe(point) + " | " + describe(profile) + " |";
	}

	private static String describe(JourneyCandidate.Fare fare) {
		if (fare.status() == FareStatus.UNAVAILABLE) {
			return "UNAVAILABLE (amounts omitted, sourceSnapshotIds=" + fare.sourceSnapshotIds() + ")";
		}
		return "AVAILABLE adult " + fare.adultCardWon() + "/" + fare.adultCashWon()
			+ ", youth " + fare.youthCardWon() + "/" + fare.youthCashWon()
			+ ", child " + fare.childCardWon() + "/" + fare.childCashWon()
			+ ", sourceSnapshotIds=" + fare.sourceSnapshotIds();
	}

	private static JourneyCandidate.Fare pointFare(RaptorRouteBundleRuntimeView runtime, String origin, String destination) {
		var request = new JourneyRequest(
			REQUEST_ID, origin, destination,
			new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE,
			0, 1, () -> false);
		var snapshot = new ActiveJourneySnapshot(
			"snapshot-1", "bundle-1", ROUTE_BUNDLE_SHA, "timetable-1", "accessibility-1",
			GENERATION, runtime, VALID_UNTIL, true,
			ActiveServingEvidence.unobservable(),
			SnapshotBoundaryReceipt.observed(0, 0),
			SnapshotMeasurementReceipt.unobservable());
		return new JourneyRaptorAdapter().plan(
			request, snapshot, EFFECTIVE, null, new JourneyRequestMeasurement(REQUEST_ID)
		).candidates().getFirst().fare();
	}

	private static List<JourneyCandidate.Fare> profileFares(
		RaptorRouteBundleRuntimeView runtime, String origin, String destination
	) {
		var query = new JourneyRaptorQuery(
			REQUEST_ID, origin, destination,
			new JourneyRaptorQuery.DepartBetween(EFFECTIVE, EFFECTIVE.plusSeconds(3_600)),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false);
		var result = new JourneyProfileRaptorAdapter().planRuntime(
			query, runtime, null, new JourneyProfileResourcePolicy.ProfilePlanningLimits(100_000L, 32, 32, 32));
		assertThat(result).isInstanceOf(JourneyProfileRaptorPort.PlanningResult.Planned.class);
		var plan = (JourneyProfileRaptorPort.DepartureWindowPlan)
			((JourneyProfileRaptorPort.PlanningResult.Planned) result).temporalPlan();
		return plan.points().stream()
			.flatMap(point -> point.itineraries().stream())
			.map(JourneyProfileRaptorPort.Itinerary::fare)
			.toList();
	}

	private static RaptorRouteBundleRuntimeView runtime(
		String origin, String destination, Map<String, OfficialFareQuote> quotes
	) {
		return RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA, GENERATION, singleRideTimetable(origin, destination), quotes);
	}

	private static Map<String, OfficialFareQuote> realQuotes() throws IOException {
		var quotes = new LinkedHashMap<String, OfficialFareQuote>();
		for (JsonNode row : JSON.readTree(resourceBytes()).path("quotes")) {
			var quote = new OfficialFareQuote(
				row.path("originStationId").textValue(),
				row.path("destinationStationId").textValue(),
				row.path("sourceId").textValue(),
				row.path("snapshotId").textValue(),
				row.path("mappingLedgerHash").textValue(),
				row.path("gnrlCardFare").intValue(),
				row.path("gnrlCashFare").intValue(),
				row.path("yungCardFare").intValue(),
				row.path("yungCashFare").intValue(),
				row.path("childCardFare").intValue(),
				row.path("childCashFare").intValue());
			String key = OfficialFareQuote.fareKey(quote.originStationId(), quote.destinationStationId());
			if (quotes.put(key, quote) != null) throw new IllegalStateException("duplicate real quote " + key);
		}
		return quotes;
	}

	private static byte[] resourceBytes() throws IOException {
		try (InputStream input = JourneyOfficialOdFareRealQuotesTest.class.getResourceAsStream(RESOURCE)) {
			return Objects.requireNonNull(input, RESOURCE).readAllBytes();
		}
	}

	private static RouteTimetable singleRideTimetable(String origin, String destination) {
		var calendar = new ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Asia/Seoul");
		var route = new TransitRoute("route", "line", "L", "Line", destination, "Asia/Seoul");
		var trip = new TransitTrip(
			"trip", "route", "daily", destination, "down", "SUBWAY", "LOCAL", "1001", 0);
		var stopTimes = List.of(
			new TransitStopTime("trip", 1, origin, "line", 32_400, 32_400, 0, 0),
			new TransitStopTime("trip", 2, destination, "line", 33_000, 33_000, 0, 0));
		var edges = List.of(
			new PathwayEdge(
				"entry", "entrance", "platform-origin", 120, 60, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"),
			new PathwayEdge(
				"exit", "platform-destination", "outside", 60, 40, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		var evidence = List.of(
			new RouteEdgeEvidence(
				"entry-evidence", origin, "line", "entry", "ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null),
			new RouteEdgeEvidence(
				"exit-evidence", destination, "line", "exit", "EXIT", "OFFICIAL_SOURCE", "VERIFIED", true, null));
		var access = new LoadRouteTimetablePort.RouteAccessData(
			List.of(
				new PathwayNode("entrance", origin, null, "ENTRANCE"),
				new PathwayNode("platform-origin", origin, "line", "PLATFORM"),
				new PathwayNode("platform-destination", destination, "line", "PLATFORM"),
				new PathwayNode("outside", destination, null, "EXIT")),
			edges, List.of(), evidence);
		return new RouteTimetable(
			List.of(calendar), List.of(), List.of(route), List.of(trip), stopTimes, List.of(), List.of(), null, access);
	}
}
