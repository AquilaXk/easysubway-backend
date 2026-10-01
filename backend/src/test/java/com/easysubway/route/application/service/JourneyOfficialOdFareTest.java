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
	private static final OfficialFareQuote QUOTE_FULL = quote("station-a", "station-b", "snap-full",
		1400, 1500, 800, 900, 500, 600);
	private static final OfficialFareQuote QUOTE_SECTION_1 = quote("station-a", "station-transfer", "snap-sec1",
		1250, 1350, 720, 820, 450, 550);
	private static final OfficialFareQuote QUOTE_SECTION_2 = quote("station-transfer", "station-b", "snap-sec2",
		1350, 1450, 780, 880, 480, 580);
	private static final JourneyCandidate.Fare SECTION_SUM_FARE = JourneyCandidate.Fare.available(
		1250 + 1350, 1350 + 1450, 720 + 780, 820 + 880, 450 + 480, 550 + 580, List.of("snap-sec1", "snap-sec2"));

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
	@DisplayName("(3) Out-of-station transfer within the limit keeps one first-boarding to final-alighting O-D quote")
	void outOfStationTransferWithinLimitKeepsOneFirstBoardingToFinalAlightingQuote() {
		// 제한 시간 안의 역 밖 환승(farePenaltyApplies == false)은 구간 표가 함께 있어도 station-a -> station-b 한 번만 조회한다.
		var runtimeNormal = RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA, GENERATION,
			waypointTimetable("OUT_OF_STATION", 120, 34_200, true), // 1,200s elapsed < 1,800s limit
			Map.of(
				OfficialFareQuote.fareKey("station-a", "station-b"), QUOTE_FULL,
				OfficialFareQuote.fareKey("station-a", "station-transfer"), QUOTE_SECTION_1,
				OfficialFareQuote.fareKey("station-transfer", "station-b"), QUOTE_SECTION_2
			)
		);
		var candidateNormal = new JourneyRaptorAdapter().plan(
			waypointRequest("station-a", "station-b", "station-transfer"),
			snapshot(runtimeNormal, GENERATION, ROUTE_BUNDLE_SHA), EFFECTIVE, null, measurement()
		).candidates().getFirst();

		assertThat(transfer(candidateNormal).farePenaltyApplies()).isFalse();
		assertExactFare(candidateNormal.fare(), QUOTE_FULL);
	}

	@Test
	@DisplayName("(3a) Re-boarding after the out-of-station limit sums the official quote of each boarding section")
	void reboardingAfterOutOfStationLimitSumsEachBoardingSectionQuote() {
		// 제한 시간을 넘긴 역 밖 환승(farePenaltyApplies == true)은 두 번 승차한 것이다.
		// station-a -> station-transfer, station-transfer -> station-b 공식 운임을 더하며 전 구간 표(station-a -> station-b)는 쓰지 않는다.
		var runtimePenalty = RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA, GENERATION,
			waypointTimetable("OUT_OF_STATION", 120, 35_400, true), // 2,400s elapsed > 1,800s limit
			Map.of(
				OfficialFareQuote.fareKey("station-a", "station-b"), QUOTE_FULL,
				OfficialFareQuote.fareKey("station-a", "station-transfer"), QUOTE_SECTION_1,
				OfficialFareQuote.fareKey("station-transfer", "station-b"), QUOTE_SECTION_2
			)
		);
		var candidatePenalty = new JourneyRaptorAdapter().plan(
			waypointRequest("station-a", "station-b", "station-transfer"),
			snapshot(runtimePenalty, GENERATION, ROUTE_BUNDLE_SHA), EFFECTIVE, null, measurement()
		).candidates().getFirst();

		assertThat(transfer(candidatePenalty).farePenaltyApplies()).isTrue();
		assertThat(transfer(candidatePenalty).transferLimitMinutes()).isEqualTo(30);
		assertThat(candidatePenalty.fare()).isEqualTo(SECTION_SUM_FARE);
	}

	@Test
	@DisplayName("(3b) Re-boarding journey is UNAVAILABLE when any boarding section is missing from the official table")
	void reboardingJourneyIsUnavailableWhenAnySectionIsMissing() {
		// 전 구간 표(station-a -> station-b)가 있어도 재승차 구간 하나가 표에 없으면 추정 없이 UNAVAILABLE이다.
		for (var present : List.of(QUOTE_SECTION_1, QUOTE_SECTION_2)) {
			var runtime = RaptorRouteBundleRuntimeView.compile(
				ROUTE_BUNDLE_SHA, GENERATION,
				waypointTimetable("OUT_OF_STATION", 120, 35_400, true),
				Map.of(
					OfficialFareQuote.fareKey("station-a", "station-b"), QUOTE_FULL,
					OfficialFareQuote.fareKey(present.originStationId(), present.destinationStationId()), present
				)
			);
			var candidate = new JourneyRaptorAdapter().plan(
				waypointRequest("station-a", "station-b", "station-transfer"),
				snapshot(runtime, GENERATION, ROUTE_BUNDLE_SHA), EFFECTIVE, null, measurement()
			).candidates().getFirst();

			assertThat(transfer(candidate).farePenaltyApplies()).isTrue();
			assertUnavailable(candidate.fare());
		}
	}

	@Test
	@DisplayName("(3c) Point search, departure-window profile, and arrive-by profile quote the same re-boarding section sum")
	void pointAndProfilePathsQuoteTheSameReboardingSectionSum() {
		var runtime = RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA, GENERATION,
			waypointTimetable("OUT_OF_STATION", 120, 35_400, true),
			Map.of(
				OfficialFareQuote.fareKey("station-a", "station-b"), QUOTE_FULL,
				OfficialFareQuote.fareKey("station-a", "station-transfer"), QUOTE_SECTION_1,
				OfficialFareQuote.fareKey("station-transfer", "station-b"), QUOTE_SECTION_2
			)
		);
		var pointRequest = new JourneyRequest(
			REQUEST_ID, "station-a", "station-b",
			new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
			1, 1, () -> false);
		var point = new JourneyRaptorAdapter().plan(
			pointRequest, snapshot(runtime, GENERATION, ROUTE_BUNDLE_SHA), EFFECTIVE, null, measurement()
		).candidates().getFirst();

		assertThat(transfer(point).farePenaltyApplies()).isTrue();
		assertThat(point.fare()).isEqualTo(SECTION_SUM_FARE);

		var departureWindow = profileItineraries(runtime, 1);
		assertThat(departureWindow).isNotEmpty().allSatisfy(itinerary ->
			assertThat(itinerary.fare()).isEqualTo(SECTION_SUM_FARE));
		var arriveBy = arriveByItineraries(runtime, EFFECTIVE.plusSeconds(7_200));
		assertThat(arriveBy).isNotEmpty().allSatisfy(itinerary ->
			assertThat(itinerary.fare()).isEqualTo(SECTION_SUM_FARE));
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
	@DisplayName("(7) Multi-leg itinerary is split only at re-boarding transfers and each boarding section is quoted once")
	void multiLegItineraryIsSplitOnlyAtReboardingTransfers() {
		// a -(일반 환승 b)- c 는 한 번의 승차이고, c와 d에서 재승차하므로 승차 구간은 a->c, c->d, d->e 세 개다.
		var itinerary = new RouteTimetableRaptorPlanner.JourneyItinerary(
			LocalDate.of(2026, 7, 1), EFFECTIVE, EFFECTIVE.plusSeconds(1_200), null, null,
			new JourneyProfileRaptorPort.ItineraryMetrics(
				3, 480, 400, 0, new JourneyProfileRaptorPort.MinimumTransferSeconds(60)),
			List.of(
				access(RouteTimetableRaptorPlanner.JourneyAccessKind.ENTRY, "station-a", "station-a", null),
				TestProjectionRides.projectionRide("line-1", "trip-1", "station-b", "station-a", "station-b",
					EFFECTIVE, EFFECTIVE.plusSeconds(300), null, null),
				access(RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER, "station-b", "station-b", false),
				TestProjectionRides.projectionRide("line-2", "trip-2", "station-c", "station-b", "station-c",
					EFFECTIVE.plusSeconds(360), EFFECTIVE.plusSeconds(600), null, null),
				access(RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER, "station-c", "station-c", true),
				TestProjectionRides.projectionRide("line-3", "trip-3", "station-d", "station-c", "station-d",
					EFFECTIVE.plusSeconds(720), EFFECTIVE.plusSeconds(900), null, null),
				access(RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER, "station-d", "station-d", true),
				TestProjectionRides.projectionRide("line-4", "trip-4", "station-e", "station-d", "station-e",
					EFFECTIVE.plusSeconds(1_000), EFFECTIVE.plusSeconds(1_140), null, null),
				access(RouteTimetableRaptorPlanner.JourneyAccessKind.EXIT, "station-e", "station-e", null)));
		var quoteAc = quote("station-a", "station-c", "snap-ac", 1700, 1800, 950, 1050, 650, 750);
		var quoteCd = quote("station-c", "station-d", "snap-cd", 1400, 1500, 800, 900, 500, 600);
		var quoteDe = quote("station-d", "station-e", "snap-cd", 1450, 1550, 820, 920, 0, 620);
		var sectionQuotes = Map.of(
			OfficialFareQuote.fareKey("station-a", "station-c"), quoteAc,
			OfficialFareQuote.fareKey("station-c", "station-d"), quoteCd,
			OfficialFareQuote.fareKey("station-d", "station-e"), quoteDe);
		var withWholeJourneyQuote = new java.util.HashMap<>(sectionQuotes);
		withWholeJourneyQuote.put(OfficialFareQuote.fareKey("station-a", "station-e"),
			quote("station-a", "station-e", "snap-ae", 2050, 2150, 1300, 1400, 800, 900));
		// 같은 스냅샷 ID는 한 번만 남기고 승차 순서대로 둔다.
		var expected = JourneyCandidate.Fare.available(
			1700 + 1400 + 1450, 1800 + 1500 + 1550, 950 + 800 + 820, 1050 + 900 + 920, 650 + 500, 750 + 600 + 620,
			List.of("snap-ac", "snap-cd"));

		assertThat(JourneyRaptorAdapter.calculateFare(itinerary, sectionQuotes)).isEqualTo(expected);
		assertThat(JourneyRaptorAdapter.calculateFare(itinerary, withWholeJourneyQuote)).isEqualTo(expected);
		for (var missing : sectionQuotes.keySet()) {
			var incomplete = new java.util.HashMap<>(withWholeJourneyQuote);
			incomplete.remove(missing);
			assertUnavailable(JourneyRaptorAdapter.calculateFare(itinerary, incomplete));
		}
		assertUnavailable(JourneyRaptorAdapter.calculateFare(itinerary, Map.of()));
	}

	@Test
	@DisplayName("(8) Profile planning carries the same official O-D fare as point search, and UNAVAILABLE when the O-D is absent")
	void profilePlanningCarriesTheSameOfficialFareAsPointSearch() {
		var quote = quote("station-a", "station-b", "snapshot-official-1", 1400, 1500, 800, 900, 500, 600);
		var quoted = RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA, GENERATION, singleRideTimetable(),
			Map.of(OfficialFareQuote.fareKey("station-a", "station-b"), quote));
		var unquoted = RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA, GENERATION, singleRideTimetable(),
			Map.of(OfficialFareQuote.fareKey("station-x", "station-y"),
				quote("station-x", "station-y", "snapshot-other", 1400, 1500, 800, 900, 500, 600)));

		var quotedItineraries = profileItineraries(quoted);
		var unquotedItineraries = profileItineraries(unquoted);

		assertThat(quotedItineraries).isNotEmpty().allSatisfy(itinerary -> assertExactFare(itinerary.fare(), quote));
		assertThat(unquotedItineraries).isNotEmpty().allSatisfy(itinerary -> assertUnavailable(itinerary.fare()));
		var pointFare = new JourneyRaptorAdapter().plan(
			directRequest("station-a", "station-b"), snapshot(quoted, GENERATION, ROUTE_BUNDLE_SHA), EFFECTIVE, null,
			measurement()).candidates().getFirst().fare();
		assertThat(quotedItineraries.getFirst().fare()).isEqualTo(pointFare);
	}

	@Test
	@DisplayName("(9) Itinerary without a ride is rejected instead of being quoted")
	void itineraryWithoutRideIsRejectedInsteadOfQuoted() {
		var itinerary = new RouteTimetableRaptorPlanner.JourneyItinerary(
			LocalDate.of(2026, 7, 1), EFFECTIVE, EFFECTIVE.plusSeconds(60), null, null,
			new JourneyProfileRaptorPort.ItineraryMetrics(0, 60, 100, 0, new JourneyProfileRaptorPort.NoTransfer()),
			List.of(access(RouteTimetableRaptorPlanner.JourneyAccessKind.ENTRY, "station-a", "station-a", null)));

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> JourneyRaptorAdapter.calculateFare(itinerary,
				Map.of(OfficialFareQuote.fareKey("station-a", "station-b"),
					quote("station-a", "station-b", "snap", 1400, 1500, 800, 900, 500, 600))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Journey itinerary must contain a ride to quote a fare");
	}

	@Test
	@DisplayName("(10) Re-boarding transfer before any ride is rejected instead of being quoted")
	void reboardingTransferBeforeAnyRideIsRejected() {
		var itinerary = new RouteTimetableRaptorPlanner.JourneyItinerary(
			LocalDate.of(2026, 7, 1), EFFECTIVE, EFFECTIVE.plusSeconds(600), null, null,
			new JourneyProfileRaptorPort.ItineraryMetrics(1, 600, 200, 0, new JourneyProfileRaptorPort.MinimumTransferSeconds(60)),
			List.of(
				access(RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER, "station-a", "station-a", true),
				TestProjectionRides.projectionRide("line-1", "trip-1", "station-b", "station-a", "station-b",
					EFFECTIVE, EFFECTIVE.plusSeconds(300), null, null)));

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> JourneyRaptorAdapter.calculateFare(itinerary,
				Map.of(OfficialFareQuote.fareKey("station-a", "station-b"), QUOTE_FULL)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Journey re-boarding transfer must follow a ride");
	}

	private static List<JourneyProfileRaptorPort.Itinerary> profileItineraries(RaptorRouteBundleRuntimeView runtime) {
		return profileItineraries(runtime, 0);
	}

	private static List<JourneyProfileRaptorPort.Itinerary> profileItineraries(
		RaptorRouteBundleRuntimeView runtime, int maxTransfers
	) {
		var query = new JourneyRaptorQuery(
			REQUEST_ID, "station-a", "station-b",
			new JourneyRaptorQuery.DepartBetween(EFFECTIVE, EFFECTIVE.plusSeconds(3_600)),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, maxTransfers, 1, () -> false);
		var result = new JourneyProfileRaptorAdapter().planRuntime(
			query, runtime, null, new JourneyProfileResourcePolicy.ProfilePlanningLimits(100_000L, 32, 32, 32));
		assertThat(result).isInstanceOf(JourneyProfileRaptorPort.PlanningResult.Planned.class);
		var plan = (JourneyProfileRaptorPort.DepartureWindowPlan)
			((JourneyProfileRaptorPort.PlanningResult.Planned) result).temporalPlan();
		return plan.points().stream().flatMap(point -> point.itineraries().stream()).toList();
	}

	private static List<JourneyProfileRaptorPort.Itinerary> arriveByItineraries(
		RaptorRouteBundleRuntimeView runtime, Instant deadline
	) {
		var query = new JourneyRaptorQuery(
			REQUEST_ID, "station-a", "station-b",
			new JourneyRaptorQuery.ArriveBy(EFFECTIVE, deadline),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 1, 1, () -> false);
		var result = new JourneyProfileRaptorAdapter().planRuntime(
			query, runtime, null, new JourneyProfileResourcePolicy.ProfilePlanningLimits(100_000L, 32, 32, 32));
		assertThat(result).isInstanceOf(JourneyProfileRaptorPort.PlanningResult.Planned.class);
		var plan = (JourneyProfileRaptorPort.ArriveByPlan)
			((JourneyProfileRaptorPort.PlanningResult.Planned) result).temporalPlan();
		assertThat(plan.result()).isInstanceOf(JourneyProfileRaptorPort.ReversePlan.Found.class);
		return ((JourneyProfileRaptorPort.ReversePlan.Found) plan.result()).itineraries();
	}

	private static JourneyCandidate.Transfer transfer(JourneyCandidate candidate) {
		return candidate.legs().stream()
			.filter(JourneyCandidate.Transfer.class::isInstance).map(JourneyCandidate.Transfer.class::cast)
			.findFirst().orElseThrow();
	}

	private static RouteTimetableRaptorPlanner.JourneyAccessProjection access(
		RouteTimetableRaptorPlanner.JourneyAccessKind kind, String from, String to,
		Boolean farePenaltyApplies
	) {
		return new RouteTimetableRaptorPlanner.JourneyAccessProjection(
			kind, from, to, 60, 100, false, true, "VERIFIED",
			kind == RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER ? "OUT_OF_STATION" : null,
			farePenaltyApplies,
			kind == RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER ? 30 : null);
	}

	private static void assertExactFare(JourneyCandidate.Fare fare, OfficialFareQuote quote) {
		assertThat(fare.status()).isEqualTo(FareStatus.AVAILABLE);
		assertThat(fare.adultCardWon()).isEqualTo(quote.gnrlCardFare());
		assertThat(fare.adultCashWon()).isEqualTo(quote.gnrlCashFare());
		assertThat(fare.youthCardWon()).isEqualTo(quote.yungCardFare());
		assertThat(fare.youthCashWon()).isEqualTo(quote.yungCashFare());
		assertThat(fare.childCardWon()).isEqualTo(quote.childCardFare());
		assertThat(fare.childCashWon()).isEqualTo(quote.childCashFare());
		assertThat(fare.sourceSnapshotIds()).containsExactly(quote.snapshotId());
	}

	private static void assertUnavailable(JourneyCandidate.Fare fare) {
		assertThat(fare.status()).isEqualTo(FareStatus.UNAVAILABLE);
		assertThat(fare.adultCardWon()).isNull();
		assertThat(fare.adultCashWon()).isNull();
		assertThat(fare.youthCardWon()).isNull();
		assertThat(fare.youthCashWon()).isNull();
		assertThat(fare.childCardWon()).isNull();
		assertThat(fare.childCashWon()).isNull();
		assertThat(fare.sourceSnapshotIds()).isEmpty();
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
