package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.ActiveJourneySnapshotPort.ActiveJourneySnapshot;
import com.easysubway.journey.application.ActiveJourneySnapshotPort.ActiveServingEvidence;
import com.easysubway.journey.application.ActiveJourneySnapshotPort.SnapshotBoundaryReceipt;
import com.easysubway.journey.application.ActiveJourneySnapshotPort.SnapshotMeasurementReceipt;
import com.easysubway.journey.application.JourneyCandidate;
import com.easysubway.journey.application.JourneyCandidate.FareStatus;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.JourneyRequestMeasurement;
import com.easysubway.journey.application.TestRides;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransferRule;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Journey V3 official O-D fare quotes")
class JourneyOfficialOdFareTest {

	private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
	private static final String ROUTE_BUNDLE_SHA = "a".repeat(64);
	private static final long GENERATION = 7;
	private static final Instant EFFECTIVE = Instant.parse("2026-06-30T23:50:00Z");
	private static final Instant VALID_UNTIL = Instant.parse("2026-07-01T02:00:00Z");

	@Test
	@DisplayName("(1) Official fare quote in table returns exact six amounts and source snapshot ID")
	void officialFareQuoteInTableReturnsExactSixAmounts() {
		var quote = quote("station-a", "station-b", "snapshot-official-1",
			1400, 1500, 800, 900, 500, 600);
		var runtime = RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA, GENERATION, singleRideTimetable(),
			Map.of(OfficialFareQuote.fareKey("station-a", "station-b"), quote)
		);
		var request = directRequest("station-a", "station-b");
		var candidate = new JourneyRaptorAdapter().plan(
			request, snapshot(runtime, GENERATION, ROUTE_BUNDLE_SHA), EFFECTIVE, null, measurement()
		).candidates().getFirst();

		assertThat(candidate.fare().status()).isEqualTo(FareStatus.AVAILABLE);
		assertThat(candidate.fare().adultCardWon()).isEqualTo(1400);
		assertThat(candidate.fare().adultCashWon()).isEqualTo(1500);
		assertThat(candidate.fare().youthCardWon()).isEqualTo(800);
		assertThat(candidate.fare().youthCashWon()).isEqualTo(900);
		assertThat(candidate.fare().childCardWon()).isEqualTo(500);
		assertThat(candidate.fare().childCashWon()).isEqualTo(600);
		assertThat(candidate.fare().sourceSnapshotIds()).containsExactly("snapshot-official-1");
	}

	@Test
	@DisplayName("(2) Missing O-D quote results in UNAVAILABLE status and null amounts")
	void missingOdQuoteResultsInUnavailableStatus() {
		var otherQuote = quote("station-x", "station-y", "snapshot-other",
			1400, 1500, 800, 900, 500, 600);
		var runtime = RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA, GENERATION, singleRideTimetable(),
			Map.of(OfficialFareQuote.fareKey("station-x", "station-y"), otherQuote)
		);
		var request = directRequest("station-a", "station-b");
		var candidate = new JourneyRaptorAdapter().plan(
			request, snapshot(runtime, GENERATION, ROUTE_BUNDLE_SHA), EFFECTIVE, null, measurement()
		).candidates().getFirst();

		assertThat(candidate.fare().status()).isEqualTo(FareStatus.UNAVAILABLE);
		assertThat(candidate.fare().adultCardWon()).isNull();
		assertThat(candidate.fare().adultCashWon()).isNull();
		assertThat(candidate.fare().youthCardWon()).isNull();
		assertThat(candidate.fare().youthCashWon()).isNull();
		assertThat(candidate.fare().childCardWon()).isNull();
		assertThat(candidate.fare().childCashWon()).isNull();
		assertThat(candidate.fare().sourceSnapshotIds()).isEmpty();
	}

	@Test
	@DisplayName("(3) Normal transfer uses single O-D fare, while out-of-station penalty transfer sums fare sections")
	void appliesSingleOdFareForNormalTransferAndSumsSectionsForOutOfStationPenaltyTransfer() {
		// Part A: Normal transfer within limit (farePenaltyApplies == false) -> single O-D station-a -> station-b
		var quoteFull = quote("station-a", "station-b", "snap-full",
			1400, 1500, 800, 900, 500, 600);
		var runtimeNormal = RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA, GENERATION,
			waypointTimetable("OUT_OF_STATION", 120, 34_200, true), // 1,200s elapsed < 1,800s limit
			Map.of(OfficialFareQuote.fareKey("station-a", "station-b"), quoteFull)
		);
		var waypointReq = waypointRequest("station-a", "station-b", "station-transfer");
		var candidateNormal = new JourneyRaptorAdapter().plan(
			waypointReq, snapshot(runtimeNormal, GENERATION, ROUTE_BUNDLE_SHA), EFFECTIVE, null, measurement()
		).candidates().getFirst();

		assertThat(candidateNormal.fare().status()).isEqualTo(FareStatus.AVAILABLE);
		assertThat(candidateNormal.fare().adultCardWon()).isEqualTo(1400);
		assertThat(candidateNormal.fare().adultCashWon()).isEqualTo(1500);
		assertThat(candidateNormal.fare().youthCardWon()).isEqualTo(800);
		assertThat(candidateNormal.fare().youthCashWon()).isEqualTo(900);
		assertThat(candidateNormal.fare().childCardWon()).isEqualTo(500);
		assertThat(candidateNormal.fare().childCashWon()).isEqualTo(600);
		assertThat(candidateNormal.fare().sourceSnapshotIds()).containsExactly("snap-full");

		// Part B: Out-of-station transfer exceeding limit (farePenaltyApplies == true) -> split into 2 sections:
		// Section 1: station-a -> station-transfer
		// Section 2: station-transfer -> station-b
		var quoteSec1 = quote("station-a", "station-transfer", "snap-sec1",
			1400, 1500, 800, 900, 500, 600);
		var quoteSec2 = quote("station-transfer", "station-b", "snap-sec2",
			1400, 1500, 800, 900, 500, 600);
		var runtimePenalty = RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA, GENERATION,
			waypointTimetable("OUT_OF_STATION", 120, 35_400, true), // 2,400s elapsed > 1,800s limit
			Map.of(
				OfficialFareQuote.fareKey("station-a", "station-transfer"), quoteSec1,
				OfficialFareQuote.fareKey("station-transfer", "station-b"), quoteSec2
			)
		);
		var candidatePenalty = new JourneyRaptorAdapter().plan(
			waypointReq, snapshot(runtimePenalty, GENERATION, ROUTE_BUNDLE_SHA), EFFECTIVE, null, measurement()
		).candidates().getFirst();

		assertThat(candidatePenalty.fare().status()).isEqualTo(FareStatus.AVAILABLE);
		assertThat(candidatePenalty.fare().adultCardWon()).isEqualTo(2800);
		assertThat(candidatePenalty.fare().adultCashWon()).isEqualTo(3000);
		assertThat(candidatePenalty.fare().youthCardWon()).isEqualTo(1600);
		assertThat(candidatePenalty.fare().youthCashWon()).isEqualTo(1800);
		assertThat(candidatePenalty.fare().childCardWon()).isEqualTo(1000);
		assertThat(candidatePenalty.fare().childCashWon()).isEqualTo(1200);
		assertThat(candidatePenalty.fare().sourceSnapshotIds()).containsExactly("snap-sec1", "snap-sec2");

		// Part C: Penalty transfer with one section missing from fare table -> entire fare becomes UNAVAILABLE
		var runtimePenaltyPartial = RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA, GENERATION,
			waypointTimetable("OUT_OF_STATION", 120, 35_400, true),
			Map.of(OfficialFareQuote.fareKey("station-a", "station-transfer"), quoteSec1)
		);
		var candidatePartial = new JourneyRaptorAdapter().plan(
			waypointReq, snapshot(runtimePenaltyPartial, GENERATION, ROUTE_BUNDLE_SHA), EFFECTIVE, null, measurement()
		).candidates().getFirst();

		assertThat(candidatePartial.fare().status()).isEqualTo(FareStatus.UNAVAILABLE);
		assertThat(candidatePartial.fare().adultCardWon()).isNull();
		assertThat(candidatePartial.fare().sourceSnapshotIds()).isEmpty();
	}

	@Test
	@DisplayName("(4) Bundle generation replacement uses new fare table")
	void usesNewFareTableWhenBundleGenerationReplaced() {
		var quoteGen7 = quote("station-a", "station-b", "snap-gen7",
			1400, 1500, 800, 900, 500, 600);
		var quoteGen8 = quote("station-a", "station-b", "snap-gen8",
			1550, 1650, 900, 1000, 600, 700);

		var runtimeGen7 = RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA, 7, singleRideTimetable(),
			Map.of(OfficialFareQuote.fareKey("station-a", "station-b"), quoteGen7)
		);
		String newSha = "b".repeat(64);
		var runtimeGen8 = RaptorRouteBundleRuntimeView.compile(
			newSha, 8, singleRideTimetable(),
			Map.of(OfficialFareQuote.fareKey("station-a", "station-b"), quoteGen8)
		);

		var request = directRequest("station-a", "station-b");
		var adapter = new JourneyRaptorAdapter();

		var candidate7 = adapter.plan(
			request, snapshot(runtimeGen7, 7, ROUTE_BUNDLE_SHA), EFFECTIVE, null, measurement()
		).candidates().getFirst();
		assertThat(candidate7.fare().status()).isEqualTo(FareStatus.AVAILABLE);
		assertThat(candidate7.fare().adultCardWon()).isEqualTo(1400);
		assertThat(candidate7.fare().sourceSnapshotIds()).containsExactly("snap-gen7");

		var candidate8 = adapter.plan(
			request, snapshot(runtimeGen8, 8, newSha), EFFECTIVE, null, measurement()
		).candidates().getFirst();
		assertThat(candidate8.fare().status()).isEqualTo(FareStatus.AVAILABLE);
		assertThat(candidate8.fare().adultCardWon()).isEqualTo(1550);
		assertThat(candidate8.fare().adultCashWon()).isEqualTo(1650);
		assertThat(candidate8.fare().youthCardWon()).isEqualTo(900);
		assertThat(candidate8.fare().youthCashWon()).isEqualTo(1000);
		assertThat(candidate8.fare().childCardWon()).isEqualTo(600);
		assertThat(candidate8.fare().childCashWon()).isEqualTo(700);
		assertThat(candidate8.fare().sourceSnapshotIds()).containsExactly("snap-gen8");
	}

	@Test
	@DisplayName("(5) Fare record validation rejects negative amounts or empty snapshot IDs when available")
	void fareRecordValidationRejectsNegativeAmountsOrEmptySnapshots() {
		org.assertj.core.api.Assertions.assertThatThrownBy(() ->
			JourneyCandidate.Fare.available(-1, 1500, 800, 900, 500, 600, List.of("snap-1"))
		).isInstanceOf(IllegalArgumentException.class)
		.hasMessageContaining("fare amounts must not be negative");

		org.assertj.core.api.Assertions.assertThatThrownBy(() ->
			JourneyCandidate.Fare.available(1400, 1500, 800, 900, 500, 600, List.of())
		).isInstanceOf(IllegalArgumentException.class)
		.hasMessageContaining("sourceSnapshotIds must not be empty");

		org.assertj.core.api.Assertions.assertThatThrownBy(() ->
			new JourneyCandidate.Fare(FareStatus.UNAVAILABLE, 1400, null, null, null, null, null, List.of())
		).isInstanceOf(IllegalArgumentException.class)
		.hasMessageContaining("fare amounts must be null when status is UNAVAILABLE");
	}

	@Test
	@DisplayName("Runtime view rejects a fare table whose key does not match the quote endpoints")
	void runtimeViewRejectsFareKeyThatDoesNotMatchQuoteEndpoints() {
		var quote = quote("station-a", "station-b", "snapshot-official-1", 1400, 1500, 800, 900, 500, 600);

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA, GENERATION, singleRideTimetable(),
			Map.of(OfficialFareQuote.fareKey("station-b", "station-a"), quote)
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("officialFareQuotes key mismatch: expected station-a^@station-b but got station-b^@station-a");
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA, GENERATION, singleRideTimetable(), (Map<String, OfficialFareQuote>) null
		)).isInstanceOf(NullPointerException.class).hasMessage("officialFareQuotes");
	}

	@Test
	@DisplayName("(6) OfficialFareQuote and fareKey reject blank, null, or equal origin/destination")
	void officialFareQuoteAndFareKeyValidation() {
		org.assertj.core.api.Assertions.assertThatThrownBy(() ->
			new OfficialFareQuote("station-a", "station-a", "src", "snap", "f".repeat(64), 1400, 1500, 800, 900, 500, 600)
		).isInstanceOf(IllegalArgumentException.class)
		.hasMessageContaining("differ");

		org.assertj.core.api.Assertions.assertThatThrownBy(() ->
			OfficialFareQuote.fareKey(null, "station-b")
		).isInstanceOf(IllegalArgumentException.class);

		org.assertj.core.api.Assertions.assertThatThrownBy(() ->
			OfficialFareQuote.fareKey("station-a", "  ")
		).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("(7) Complex multi-leg journey with two consecutive out-of-station penalty transfers sums three sections")
	void complexMultiLegJourneyWithMultiplePenaltyTransfersSumsAllSections() {
		List<JourneyCandidate.Leg> legs = List.of(
			new JourneyCandidate.Entry("station-a", 60),
			TestRides.candidateRide(
				"line-1", "trip-1", "station-b", "station-a", "station-b",
				EFFECTIVE, EFFECTIVE.plusSeconds(300), null, null
			),
			new JourneyCandidate.Transfer("station-b", "station-b", 120, "OUT_OF_STATION", true, 1400, 30),
			TestRides.candidateRide(
				"line-2", "trip-2", "station-c", "station-b", "station-c",
				EFFECTIVE.plusSeconds(420), EFFECTIVE.plusSeconds(720), null, null
			),
			new JourneyCandidate.Transfer("station-c", "station-c", 120, "OUT_OF_STATION", true, 1400, 30),
			TestRides.candidateRide(
				"line-3", "trip-3", "station-d", "station-c", "station-d",
				EFFECTIVE.plusSeconds(840), EFFECTIVE.plusSeconds(1140), null, null
			),
			new JourneyCandidate.Exit("station-d", 60)
		);

		var quote1 = quote("station-a", "station-b", "snap-1", 1400, 1500, 800, 900, 500, 600);
		var quote2 = quote("station-b", "station-c", "snap-2", 1500, 1600, 850, 950, 550, 650);
		var quote3 = quote("station-c", "station-d", "snap-1", 1600, 1700, 900, 1000, 600, 700);

		var quotes = Map.of(
			OfficialFareQuote.fareKey("station-a", "station-b"), quote1,
			OfficialFareQuote.fareKey("station-b", "station-c"), quote2,
			OfficialFareQuote.fareKey("station-c", "station-d"), quote3
		);

		var fare = JourneyRaptorAdapter.calculateFare(legs, quotes);

		assertThat(fare.status()).isEqualTo(FareStatus.AVAILABLE);
		assertThat(fare.adultCardWon()).isEqualTo(1400 + 1500 + 1600);
		assertThat(fare.adultCashWon()).isEqualTo(1500 + 1600 + 1700);
		assertThat(fare.youthCardWon()).isEqualTo(800 + 850 + 900);
		assertThat(fare.youthCashWon()).isEqualTo(900 + 950 + 1000);
		assertThat(fare.childCardWon()).isEqualTo(500 + 550 + 600);
		assertThat(fare.childCashWon()).isEqualTo(600 + 650 + 700);
		assertThat(fare.sourceSnapshotIds()).containsExactly("snap-1", "snap-2");
	}

	@Test
	@DisplayName("(8) Multi-leg journey with mixed transfers (normal transfer followed by penalty transfer) splits correctly")
	void multiLegJourneyWithMixedTransfersSplitsCorrectly() {
		List<JourneyCandidate.Leg> legs = List.of(
			new JourneyCandidate.Entry("station-a", 60),
			TestRides.candidateRide(
				"line-1", "trip-1", "station-b", "station-a", "station-b",
				EFFECTIVE, EFFECTIVE.plusSeconds(300), null, null
			),
			new JourneyCandidate.Transfer("station-b", "station-b", 60, null, null, null, null),
			TestRides.candidateRide(
				"line-2", "trip-2", "station-c", "station-b", "station-c",
				EFFECTIVE.plusSeconds(360), EFFECTIVE.plusSeconds(660), null, null
			),
			new JourneyCandidate.Transfer("station-c", "station-c", 120, "OUT_OF_STATION", true, 1400, 30),
			TestRides.candidateRide(
				"line-3", "trip-3", "station-d", "station-c", "station-d",
				EFFECTIVE.plusSeconds(780), EFFECTIVE.plusSeconds(1080), null, null
			),
			new JourneyCandidate.Exit("station-d", 60)
		);

		var quoteAc = quote("station-a", "station-c", "snap-ac", 1700, 1800, 950, 1050, 650, 750);
		var quoteCd = quote("station-c", "station-d", "snap-cd", 1400, 1500, 800, 900, 500, 600);

		var quotes = Map.of(
			OfficialFareQuote.fareKey("station-a", "station-c"), quoteAc,
			OfficialFareQuote.fareKey("station-c", "station-d"), quoteCd
		);

		var fare = JourneyRaptorAdapter.calculateFare(legs, quotes);

		assertThat(fare.status()).isEqualTo(FareStatus.AVAILABLE);
		assertThat(fare.adultCardWon()).isEqualTo(1700 + 1400);
		assertThat(fare.adultCashWon()).isEqualTo(1800 + 1500);
		assertThat(fare.youthCardWon()).isEqualTo(950 + 800);
		assertThat(fare.youthCashWon()).isEqualTo(1050 + 900);
		assertThat(fare.childCardWon()).isEqualTo(650 + 500);
		assertThat(fare.childCashWon()).isEqualTo(750 + 600);
		assertThat(fare.sourceSnapshotIds()).containsExactly("snap-ac", "snap-cd");
	}

	private static OfficialFareQuote quote(
		String origin, String dest, String snapshotId,
		int adultCard, int adultCash, int youthCard, int youthCash, int childCard, int childCash
	) {
		return new OfficialFareQuote(
			origin, dest, "official", snapshotId, "f".repeat(64),
			adultCard, adultCash, youthCard, youthCash, childCard, childCash
		);
	}

	private static JourneyRequest directRequest(String origin, String destination) {
		return new JourneyRequest(
			REQUEST_ID, origin, destination,
			new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE,
			0, 1, () -> false
		);
	}

	private static JourneyRequest waypointRequest(String origin, String destination, String via) {
		return new JourneyRequest(
			REQUEST_ID, origin, destination, via,
			new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE,
			2, 1, () -> false
		);
	}

	private static ActiveJourneySnapshot snapshot(
		RaptorRouteBundleRuntimeView runtime,
		long generation,
		String sha
	) {
		return new ActiveJourneySnapshot(
			"snapshot-1", "bundle-1", sha, "timetable-1", "accessibility-1",
			generation, runtime, VALID_UNTIL, true,
			ActiveServingEvidence.unobservable(),
			SnapshotBoundaryReceipt.observed(0, 0),
			SnapshotMeasurementReceipt.unobservable()
		);
	}

	private static JourneyRequestMeasurement measurement() {
		return new JourneyRequestMeasurement(REQUEST_ID);
	}

	private static RouteTimetable singleRideTimetable() {
		var calendar = new ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Asia/Seoul");
		var route = new TransitRoute("route", "line", "L", "Line", "station-b", "Asia/Seoul");
		var trip = new TransitTrip(
			"trip", "route", "daily", "춘천행", "down", "SUBWAY", "LOCAL", "1001", 0);
		var lateTrip = new TransitTrip(
			"trip-late", "route", "daily", "station-b", "down", "SUBWAY", "LOCAL", "1002", 0);
		var stopTimes = List.of(
			new TransitStopTime("trip", 1, "station-a", "line", 32_400, 32_400, 0, 0),
			new TransitStopTime("trip", 2, "station-b", "line", 33_000, 33_000, 0, 0),
			new TransitStopTime("trip-late", 1, "station-a", "line", 36_000, 36_000, 0, 0),
			new TransitStopTime("trip-late", 2, "station-b", "line", 36_600, 36_600, 0, 0));
		var edges = List.of(
			new PathwayEdge(
				"entry", "entrance", "platform-a", 120, 60, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"),
			new PathwayEdge(
				"exit", "platform-b", "outside", 60, 40, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		var evidence = List.of(
			new RouteEdgeEvidence(
				"entry-evidence", "station-a", "line", "entry", "ENTRY",
				"OFFICIAL_SOURCE", "VERIFIED", true, null),
			new RouteEdgeEvidence(
				"exit-evidence", "station-b", "line", "exit", "EXIT",
				"OFFICIAL_SOURCE", "VERIFIED", true, null));
		var access = new LoadRouteTimetablePort.RouteAccessData(
			List.of(
				new PathwayNode("entrance", "station-a", null, "ENTRANCE"),
				new PathwayNode("platform-a", "station-a", "line", "PLATFORM"),
				new PathwayNode("platform-b", "station-b", "line", "PLATFORM"),
				new PathwayNode("outside", "station-b", null, "EXIT")),
			edges, List.of(), evidence);
		return new RouteTimetable(
			List.of(calendar), List.of(), List.of(route), List.of(trip, lateTrip), stopTimes, List.of(), List.of(), null, access);
	}

	private static RouteTimetable waypointTimetable(
		String transferType, int transferDuration, int tripSecondDeparture, boolean hasTransferRule
	) {
		var calendar = new ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Asia/Seoul");
		var routes = List.of(
			new TransitRoute("route-first", "line-a", "A", "First", "station-transfer", "Asia/Seoul"),
			new TransitRoute("route-second", "line-b", "B", "Second", "station-b", "Asia/Seoul"));
		var trips = List.of(
			new TransitTrip(
				"trip-first", "route-first", "daily", "station-transfer", "down", "SUBWAY", "LOCAL", "2001", 0),
			new TransitTrip(
				"trip-second", "route-second", "daily", "station-b", "down", "SUBWAY", "LOCAL", "2002", 0));
		var stopTimes = List.of(
			new TransitStopTime("trip-first", 1, "station-a", "line-a", 32_400, 32_400, 0, 0),
			new TransitStopTime("trip-first", 2, "station-transfer", "line-a", 33_000, 33_000, 0, 0),
			new TransitStopTime("trip-second", 1, "station-transfer", "line-b", tripSecondDeparture, tripSecondDeparture, 0, 0),
			new TransitStopTime("trip-second", 2, "station-b", "line-b", tripSecondDeparture + 600, tripSecondDeparture + 600, 0, 0));
		var edges = List.of(
			new PathwayEdge(
				"entry", "entrance", "platform-a", 120, 100, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"),
			new PathwayEdge(
				"transfer", "platform-transfer-a", "platform-transfer-b", transferDuration, 100, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"),
			new PathwayEdge(
				"exit", "platform-b", "outside", 60, 100, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"),
			new PathwayEdge(
				"entry-transfer", "entrance-transfer", "platform-transfer-b", 120, 100, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"),
			new PathwayEdge(
				"exit-transfer", "platform-transfer-a", "outside-transfer", 60, 100, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		var access = new LoadRouteTimetablePort.RouteAccessData(
			List.of(
				new PathwayNode("entrance", "station-a", null, "ENTRANCE"),
				new PathwayNode("platform-a", "station-a", "line-a", "PLATFORM"),
				new PathwayNode("platform-transfer-a", "station-transfer", "line-a", "PLATFORM"),
				new PathwayNode("platform-transfer-b", "station-transfer", "line-b", "PLATFORM"),
				new PathwayNode("entrance-transfer", "station-transfer", null, "ENTRANCE"),
				new PathwayNode("outside-transfer", "station-transfer", null, "EXIT"),
				new PathwayNode("platform-b", "station-b", "line-b", "PLATFORM"),
				new PathwayNode("outside", "station-b", null, "EXIT")),
			edges,
			hasTransferRule ? List.of(new TransferRule(
				"transfer-rule", "station-transfer", "line-a", "station-transfer", "line-b", transferType,
				transferDuration, "transfer", "transfer", "VERIFIED")) : List.of(),
			List.of(
				new RouteEdgeEvidence("entry-evidence", "station-a", "line-a", "entry", "ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null),
				new RouteEdgeEvidence("transfer-evidence", "station-transfer", "line-b", "transfer", "TRANSFER", "OFFICIAL_SOURCE", "VERIFIED", true, null),
				new RouteEdgeEvidence("exit-evidence", "station-b", "line-b", "exit", "EXIT", "OFFICIAL_SOURCE", "VERIFIED", true, null),
				new RouteEdgeEvidence("entry-transfer-evidence", "station-transfer", "line-b", "entry-transfer", "ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null),
				new RouteEdgeEvidence("exit-transfer-evidence", "station-transfer", "line-a", "exit-transfer", "EXIT", "OFFICIAL_SOURCE", "VERIFIED", true, null)));
		return new RouteTimetable(
			List.of(calendar), List.of(), routes, trips, stopTimes, List.of(), List.of(), null, access);
	}
}
