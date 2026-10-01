package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.ActiveJourneySnapshotPort;
import com.easysubway.journey.application.FacilityAvailabilityView;
import com.easysubway.journey.application.FacilityStatusUnavailableException;
import com.easysubway.journey.application.JourneyCandidate;
import com.easysubway.journey.application.JourneyProfileRaptorPort;
import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.JourneyRequestMeasurement;
import com.easysubway.journey.application.ServiceDayResolver;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #454: 경로는 출발역 승강장(역-노선)에서 시작해 도착역 승강장에서 끝난다(QA 2026-10-02).
 * 번들에 진입·하차(ENTRY/EXIT) 간선이 없어도 point·출발 시간대 profile·도착 기준 탐색이 경로를 내고,
 * 응답에는 진입·하차 구간과 그 기본값 시간이 없다. 검증되지 않은 환승만 있으면 경로가 없다.
 */
class PlatformBoundaryJourneyTest {

	private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
	private static final String ROUTE_BUNDLE_SHA = "a".repeat(64);
	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 1);
	private static final int FIRST_DEPARTURE = 32_400;
	private static final int FIRST_ARRIVAL = 33_000;
	private static final int SECOND_DEPARTURE = 33_600;
	private static final int SECOND_ARRIVAL = 34_200;

	@Test
	@DisplayName("point: ENTRY/EXIT 간선 없이 승강장→승차→검증 환승→승차→승강장 경로를 낸다")
	void pointSearchStartsAndEndsAtPlatformsWithoutEntryOrExitEdges() {
		List<JourneyCandidate> candidates = point(platformOnlyTimetable(verifiedTransfer(120, 100)),
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.WalkingPace.STANDARD);

		assertThat(candidates).singleElement().satisfies(candidate -> {
			assertThat(candidate.legs()).extracting(JourneyCandidate.Leg::type).containsExactly(
				JourneyCandidate.LegType.RIDE, JourneyCandidate.LegType.TRANSFER, JourneyCandidate.LegType.RIDE);
			assertThat(candidate.legs().getFirst()).isInstanceOfSatisfying(JourneyCandidate.Ride.class,
				ride -> assertThat(ride.fromStationId()).isEqualTo("station-a"));
			assertThat(candidate.legs().getLast()).isInstanceOfSatisfying(JourneyCandidate.Ride.class,
				ride -> assertThat(ride.toStationId()).isEqualTo("station-b"));
			// 도착은 마지막 승차의 도착 시각 그대로다(하차 기본값 시간을 더하지 않는다).
			assertThat(candidate.plannedArrivalTime()).isEqualTo(instantAt(SECOND_ARRIVAL));
			assertThat(candidate.transferCount()).isEqualTo(1);
			// 보행 거리는 검증 환승 거리만이다(진입·하차 거리 없음).
			assertThat(candidate.walkingDistanceMeters()).isEqualTo(100);
		});
	}

	@Test
	@DisplayName("출발 시간대 profile: ENTRY/EXIT 없이 승강장 기준 여정을 내고 진입·하차 구간이 없다")
	void departureWindowProfileStartsAndEndsAtPlatforms() {
		var plan = profile(new JourneyRaptorQuery.DepartBetween(instantAt(30_000), instantAt(FIRST_DEPARTURE)),
			platformOnlyTimetable(verifiedTransfer(120, 100)));

		assertThat(plan.temporalPlan()).isInstanceOfSatisfying(JourneyProfileRaptorPort.DepartureWindowPlan.class,
			window -> {
				assertThat(window.points()).isNotEmpty();
				assertThat(window.points()).allSatisfy(point -> assertThat(point.itineraries()).allSatisfy(itinerary -> {
					assertPlatformBoundaryLegs(itinerary.legs());
					assertThat(itinerary.plannedArrivalAtDestination()).isEqualTo(instantAt(SECOND_ARRIVAL));
					assertThat(itinerary.metrics().accessDistanceMeters()).isEqualTo(100);
				}));
				assertThat(window.points().stream().mapToInt(point -> point.itineraries().size()).sum()).isPositive();
			});
	}

	@Test
	@DisplayName("도착 기준(arrive-by): 마감이 마지막 승차 도착과 같아도 승강장 기준 경로를 낸다")
	void arriveByEndsAtTheDestinationPlatformWithoutExitTime() {
		var plan = profile(new JourneyRaptorQuery.ArriveBy(instantAt(30_000), instantAt(SECOND_ARRIVAL)),
			platformOnlyTimetable(verifiedTransfer(120, 100)));

		assertThat(plan.temporalPlan()).isInstanceOfSatisfying(JourneyProfileRaptorPort.ArriveByPlan.class,
			arriveBy -> assertThat(arriveBy.result()).isInstanceOfSatisfying(
				JourneyProfileRaptorPort.ReversePlan.Found.class,
				found -> assertThat(found.itineraries()).singleElement().satisfies(itinerary -> {
					assertPlatformBoundaryLegs(itinerary.legs());
					assertThat(itinerary.plannedArrivalAtDestination()).isEqualTo(instantAt(SECOND_ARRIVAL));
					// 출발 준비 시각 = 첫 승차 출발 - 승차 여유(진입 기본값 시간 없음).
					assertThat(itinerary.plannedReadyAt()).isEqualTo(instantAt(FIRST_DEPARTURE - 60));
				})));
	}

	@Test
	@DisplayName("번들에 남은 ENTRY/EXIT 간선은 무시한다(하위 호환): 응답·시간에 반영하지 않는다")
	void ignoresLegacyEntryAndExitEdgesStillPresentInTheBundle() {
		var timetable = withLegacyEntryAndExit(platformOnlyTimetable(verifiedTransfer(120, 100)));

		assertThat(point(timetable, JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.WalkingPace.STANDARD))
			.singleElement().satisfies(candidate -> {
				assertThat(candidate.legs()).extracting(JourneyCandidate.Leg::type).doesNotContain(
					JourneyCandidate.LegType.ENTRY, JourneyCandidate.LegType.EXIT);
				assertThat(candidate.plannedArrivalTime()).isEqualTo(instantAt(SECOND_ARRIVAL));
				assertThat(candidate.walkingDistanceMeters()).isEqualTo(100);
			});
		var arriveBy = profile(new JourneyRaptorQuery.ArriveBy(instantAt(30_000), instantAt(SECOND_ARRIVAL)), timetable);
		assertThat(arriveBy.temporalPlan()).isInstanceOfSatisfying(JourneyProfileRaptorPort.ArriveByPlan.class,
			value -> assertThat(value.result()).isInstanceOfSatisfying(JourneyProfileRaptorPort.ReversePlan.Found.class,
				found -> assertThat(found.itineraries()).allSatisfy(itinerary -> assertPlatformBoundaryLegs(itinerary.legs()))));
	}

	@Test
	@DisplayName("남은 ENTRY/EXIT 간선 id를 차단하는 시설 뷰는 번들 밖 id로 보아 뷰 전체를 쓰지 않는다(현재 동작 고정)")
	void facilityViewBlockingLegacyEntryOrExitIdsIsTreatedAsOutsideTheBundle() {
		// #454 이후 ENTRY/EXIT 간선은 전환으로 컴파일되지 않아 시설 오버레이가 해석할 전환 id가 없다.
		// 운영 기본값 FacilityAvailabilityPort.unavailable()에서는 영향이 없고, #431을 환승 간선 기준으로
		// 재범위화할 때 이 동작을 바꾼다. 그 전까지 현재 동작을 고정한다.
		var timetable = withLegacyEntryAndExit(platformOnlyTimetable(verifiedTransfer(120, 100)));
		var clock = Clock.fixed(instantAt(30_000), ServiceDayResolver.ZONE);
		var view = FacilityAvailabilityView.blocked(instantAt(30_000), Set.of("entry", "exit"));

		// 무단차 필수 + required=true: 뷰를 쓸 수 없어 명시적 오류가 된다.
		assertThatThrownBy(() -> point(new JourneyRaptorAdapter(() -> view, true, clock), timetable,
			JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE))
			.isInstanceOf(FacilityStatusUnavailableException.class)
			.hasMessageContaining("blocked pathway edge is not in the captured route bundle");
		// 그 밖의 요청: 차단 0건으로 경로를 그대로 낸다(ENTRY/EXIT 차단이 경로에 반영되지 않는다).
		assertThat(point(new JourneyRaptorAdapter(() -> view, false, clock), timetable,
			JourneyRequest.ConstraintMode.NONE)).singleElement()
			.satisfies(candidate -> assertThat(candidate.plannedArrivalTime()).isEqualTo(instantAt(SECOND_ARRIVAL)));
		// 기본 어댑터(FacilityAvailabilityPort.unavailable())는 차단 없이 같은 경로를 낸다.
		assertThat(point(new JourneyRaptorAdapter(), timetable, JourneyRequest.ConstraintMode.NONE)).singleElement()
			.satisfies(candidate -> assertThat(candidate.plannedArrivalTime()).isEqualTo(instantAt(SECOND_ARRIVAL)));
	}

	@Test
	@DisplayName("검증되지 않은 환승으로만 닿는 도착역은 point·profile·arrive-by 모두 경로가 없다(대체값 없음)")
	void destinationReachableOnlyThroughAnUnverifiedTransferHasNoJourney() {
		var timetable = platformOnlyTimetable(unverifiedTransfer());

		assertThat(point(timetable, JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.WalkingPace.STANDARD))
			.isEmpty();
		var departure = profile(new JourneyRaptorQuery.DepartBetween(instantAt(30_000), instantAt(FIRST_DEPARTURE)),
			timetable);
		assertThat(departure.temporalPlan()).isInstanceOfSatisfying(JourneyProfileRaptorPort.DepartureWindowPlan.class,
			window -> assertThat(window.points()).allSatisfy(point -> assertThat(point.itineraries()).isEmpty()));
		var arriveBy = profile(new JourneyRaptorQuery.ArriveBy(instantAt(30_000), instantAt(SECOND_ARRIVAL)), timetable);
		assertThat(arriveBy.temporalPlan()).isInstanceOfSatisfying(JourneyProfileRaptorPort.ArriveByPlan.class,
			value -> assertThat(value.result()).isInstanceOf(JourneyProfileRaptorPort.ReversePlan.NotFound.class));
	}

	@Test
	@DisplayName("직행(환승 없음)은 ENTRY/EXIT 간선이 전혀 없는 번들에서도 경로가 나온다")
	void directRideNeedsNoAccessEdgesAtAll() {
		var direct = directTimetable();
		assertThat(point(direct, JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.WalkingPace.STANDARD))
			.singleElement().satisfies(candidate -> {
				assertThat(candidate.legs()).extracting(JourneyCandidate.Leg::type)
					.containsExactly(JourneyCandidate.LegType.RIDE);
				assertThat(candidate.walkingDistanceMeters()).isZero();
				assertThat(candidate.plannedArrivalTime()).isEqualTo(instantAt(FIRST_ARRIVAL));
			});
	}

	// --- 시간 전용 공식 환승(data#876, 15098252 실측 소요시간, 거리 없음) ---

	@Test
	@DisplayName("시간 전용 검증 환승: 보통·빠른 걸음은 공식 실측 시간을 그대로 쓴다(실측 시간이 하한)")
	void timeOnlyVerifiedTransferKeepsTheMeasuredTimeAsFloorForStandardAndFastPaces() {
		assertThat(transferSeconds(point(platformOnlyTimetable(verifiedTransfer(300, 0)),
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.WalkingPace.STANDARD))).isEqualTo(300);
		assertThat(transferSeconds(point(platformOnlyTimetable(verifiedTransfer(300, 0)),
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.WalkingPace.FAST))).isEqualTo(300);
	}

	@Test
	@DisplayName("시간 전용 검증 환승: 느린 걸음은 1.2 m/s ÷ 걸음 속도 배율로 늘린다")
	void timeOnlyVerifiedTransferScalesUpForSlowerPaces() {
		// SLOW = 3,500 m/h. 300 × 4,320 / 3,500 = 370.29 → 371초(올림).
		assertThat(transferSeconds(point(platformOnlyTimetable(verifiedTransfer(300, 0)),
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.WalkingPace.SLOW))).isEqualTo(371);
	}

	@Test
	@DisplayName("시간 전용 검증 환승: 무단차 프로필은 거리 환승과 같은 시설 대기 60초를 더한다")
	void timeOnlyVerifiedTransferAddsTheSameStepFreeFacilityWaitAsDistanceTransfers() {
		assertThat(transferSeconds(point(platformOnlyTimetable(verifiedTransfer(300, 0)),
			JourneyRequest.MobilityProfile.STEP_FREE, JourneyRequest.WalkingPace.STANDARD))).isEqualTo(360);
	}

	@Test
	@DisplayName("거리가 있는 검증 환승은 기존대로 거리 ÷ 걸음 속도다(시간 전용 규칙과 무관)")
	void distanceTransferKeepsTheExistingDistanceRule() {
		// 100 m × 3,600 / 4,500 m/h = 80초.
		assertThat(transferSeconds(point(platformOnlyTimetable(verifiedTransfer(300, 100)),
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.WalkingPace.STANDARD))).isEqualTo(80);
	}

	@Test
	@DisplayName("시간 전용 검증 환승은 arrive-by·출발 시간대 profile에서도 같은 시간으로 쓴다")
	void timeOnlyVerifiedTransferIsUsedByProfileAndArriveBy() {
		assertProfileAndArriveByTransferSeconds(
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.WalkingPace.STANDARD, 300);
	}

	@Test
	@DisplayName("시간 전용 검증 환승: arrive-by·출발 시간대 profile도 느린 걸음은 371초로 늘린다")
	void timeOnlyVerifiedTransferScalesUpForSlowerPacesInProfileAndArriveBy() {
		// SLOW = 3,500 m/h. 300 × 4,320 / 3,500 = 370.29 → 371초(올림). point와 같은 값이어야 한다.
		assertProfileAndArriveByTransferSeconds(
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.WalkingPace.SLOW, 371);
	}

	@Test
	@DisplayName("시간 전용 검증 환승: arrive-by·출발 시간대 profile도 무단차 프로필은 시설 대기 60초를 더한다")
	void timeOnlyVerifiedTransferAddsTheStepFreeFacilityWaitInProfileAndArriveBy() {
		assertProfileAndArriveByTransferSeconds(
			JourneyRequest.MobilityProfile.STEP_FREE, JourneyRequest.WalkingPace.STANDARD, 360);
	}

	private static void assertProfileAndArriveByTransferSeconds(
		JourneyRequest.MobilityProfile mobilityProfile,
		JourneyRequest.WalkingPace walkingPace,
		int expectedSeconds
	) {
		var timetable = platformOnlyTimetable(verifiedTransfer(300, 0));
		var arriveBy = profile(new JourneyRaptorQuery.ArriveBy(instantAt(30_000), instantAt(SECOND_ARRIVAL)), timetable,
			mobilityProfile, walkingPace);
		assertThat(arriveBy.temporalPlan()).isInstanceOfSatisfying(JourneyProfileRaptorPort.ArriveByPlan.class,
			value -> assertThat(value.result()).isInstanceOfSatisfying(JourneyProfileRaptorPort.ReversePlan.Found.class,
				found -> assertThat(found.itineraries()).singleElement()
					.satisfies(itinerary -> assertThat(profileTransferSeconds(itinerary)).isEqualTo(expectedSeconds))));
		var departure = profile(new JourneyRaptorQuery.DepartBetween(instantAt(30_000), instantAt(FIRST_DEPARTURE)),
			timetable, mobilityProfile, walkingPace);
		assertThat(departure.temporalPlan()).isInstanceOfSatisfying(JourneyProfileRaptorPort.DepartureWindowPlan.class,
			window -> {
				var itineraries = window.points().stream().flatMap(point -> point.itineraries().stream()).toList();
				assertThat(itineraries).isNotEmpty()
					.allSatisfy(itinerary -> assertThat(profileTransferSeconds(itinerary)).isEqualTo(expectedSeconds));
			});
	}

	@Test
	@DisplayName("검증 환승이라도 거리·시간이 모두 0이면 근거가 없어 경로가 없다")
	void verifiedTransferWithoutDistanceOrDurationIsNotEligible() {
		assertThat(point(platformOnlyTimetable(verifiedTransfer(0, 0)),
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.WalkingPace.STANDARD)).isEmpty();
	}

	private static void assertPlatformBoundaryLegs(List<JourneyProfileRaptorPort.Leg> legs) {
		assertThat(legs).hasSize(3);
		assertThat(legs.get(0)).isInstanceOfSatisfying(JourneyProfileRaptorPort.RideLeg.class,
			ride -> assertThat(ride.fromStationId()).isEqualTo("station-a"));
		assertThat(legs.get(1)).isInstanceOfSatisfying(JourneyProfileRaptorPort.AccessLeg.class,
			access -> assertThat(access.kind()).isEqualTo(JourneyProfileRaptorPort.AccessKind.TRANSFER));
		assertThat(legs.get(2)).isInstanceOfSatisfying(JourneyProfileRaptorPort.RideLeg.class,
			ride -> assertThat(ride.toStationId()).isEqualTo("station-b"));
	}

	private static long transferSeconds(List<JourneyCandidate> candidates) {
		assertThat(candidates).hasSize(1);
		return candidates.getFirst().legs().stream()
			.filter(JourneyCandidate.Transfer.class::isInstance)
			.map(JourneyCandidate.Transfer.class::cast)
			.findFirst().orElseThrow().durationSeconds();
	}

	private static int profileTransferSeconds(JourneyProfileRaptorPort.Itinerary itinerary) {
		return itinerary.legs().stream()
			.filter(JourneyProfileRaptorPort.AccessLeg.class::isInstance)
			.map(JourneyProfileRaptorPort.AccessLeg.class::cast)
			.findFirst().orElseThrow().durationSeconds();
	}

	private static List<JourneyCandidate> point(
		RouteTimetable timetable,
		JourneyRequest.MobilityProfile mobilityProfile,
		JourneyRequest.WalkingPace walkingPace
	) {
		return point(new JourneyRaptorAdapter(), timetable, mobilityProfile, walkingPace,
			JourneyRequest.ConstraintMode.NONE);
	}

	private static List<JourneyCandidate> point(
		JourneyRaptorAdapter adapter,
		RouteTimetable timetable,
		JourneyRequest.ConstraintMode constraintMode
	) {
		return point(adapter, timetable, JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.WalkingPace.STANDARD,
			constraintMode);
	}

	private static List<JourneyCandidate> point(
		JourneyRaptorAdapter adapter,
		RouteTimetable timetable,
		JourneyRequest.MobilityProfile mobilityProfile,
		JourneyRequest.WalkingPace walkingPace,
		JourneyRequest.ConstraintMode constraintMode
	) {
		var request = new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", new JourneyRequest.Departure.Scheduled(instantAt(30_000)),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, walkingPace, mobilityProfile,
			constraintMode, 1, 1, () -> false);
		return adapter.plan(request, snapshot(timetable), instantAt(30_000), null,
			new JourneyRequestMeasurement(REQUEST_ID)).candidates();
	}

	private static JourneyProfileRaptorPort.PlanningResult.Planned profile(
		JourneyRaptorQuery.TemporalQuery temporalQuery,
		RouteTimetable timetable
	) {
		return profile(temporalQuery, timetable,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.WalkingPace.STANDARD);
	}

	private static JourneyProfileRaptorPort.PlanningResult.Planned profile(
		JourneyRaptorQuery.TemporalQuery temporalQuery,
		RouteTimetable timetable,
		JourneyRequest.MobilityProfile mobilityProfile,
		JourneyRequest.WalkingPace walkingPace
	) {
		var query = new JourneyRaptorQuery(
			REQUEST_ID, "station-a", "station-b", temporalQuery, JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			walkingPace, mobilityProfile,
			JourneyRequest.ConstraintMode.NONE, 1, 1, () -> false);
		return (JourneyProfileRaptorPort.PlanningResult.Planned) new JourneyProfileRaptorAdapter().plan(
			query, snapshot(timetable), null, policy().profilePlanningLimits());
	}

	private static ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot(RouteTimetable timetable) {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, 1, timetable);
		return new ActiveJourneySnapshotPort.ActiveJourneySnapshot(
			"snapshot", "bundle", ROUTE_BUNDLE_SHA, "timetable", "accessibility", 1, runtime,
			Instant.parse("2026-07-03T00:00:00Z"), true,
			ActiveJourneySnapshotPort.ActiveServingEvidence.unobservable(),
			ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.observed(0, 0));
	}

	private static JourneyProfileResourcePolicy policy() {
		return new JourneyProfileResourcePolicy(
			new JourneyProfileResourcePolicy.Identity("test-profile", "1.0.0", "b".repeat(64)),
			Duration.ofHours(2), 2, 100_000L, 32, 32, 32,
			Duration.ofMinutes(5), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
			1, 1, 1, 1, 4);
	}

	private static Instant instantAt(int seconds) {
		return SERVICE_DATE.atStartOfDay().plusSeconds(seconds).atOffset(ZoneOffset.ofHours(9)).toInstant();
	}

	private record TransferFixture(List<PathwayEdge> edges, List<RouteEdgeEvidence> evidence, int ruleSeconds) {
	}

	private static TransferFixture verifiedTransfer(int durationSeconds, int distanceMeters) {
		return new TransferFixture(
			List.of(new PathwayEdge("transfer", "platform-transfer-a", "platform-transfer-b",
				durationSeconds, distanceMeters, false, false, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED")),
			List.of(new RouteEdgeEvidence("transfer-evidence", "station-transfer", "line-b", "transfer", "TRANSFER",
				"OFFICIAL_SOURCE", "VERIFIED", true, null)),
			durationSeconds);
	}

	private static TransferFixture unverifiedTransfer() {
		return new TransferFixture(
			List.of(new PathwayEdge("transfer", "platform-transfer-a", "platform-transfer-b",
				120, 100, false, false, 100, "AVAILABLE", "UNKNOWN", "UNKNOWN")),
			List.of(new RouteEdgeEvidence("transfer-evidence", "station-transfer", "line-b", "transfer", "TRANSFER",
				"UNKNOWN", "UNKNOWN", true, null)),
			120);
	}

	private static RouteTimetable platformOnlyTimetable(TransferFixture transfer) {
		var routes = List.of(
			new TransitRoute("route-first", "line-a", "A", "First", "station-transfer", "Asia/Seoul"),
			new TransitRoute("route-second", "line-b", "B", "Second", "station-b", "Asia/Seoul"));
		var trips = List.of(
			new TransitTrip("trip-first", "route-first", "daily", "station-transfer", "down", "SUBWAY", "LOCAL", "2001", 0),
			new TransitTrip("trip-second", "route-second", "daily", "station-b", "down", "SUBWAY", "LOCAL", "2002", 0));
		var stopTimes = List.of(
			new TransitStopTime("trip-first", 1, "station-a", "line-a", FIRST_DEPARTURE, FIRST_DEPARTURE, 0, 0),
			new TransitStopTime("trip-first", 2, "station-transfer", "line-a", FIRST_ARRIVAL, FIRST_ARRIVAL, 0, 0),
			new TransitStopTime("trip-second", 1, "station-transfer", "line-b", SECOND_DEPARTURE, SECOND_DEPARTURE, 0, 0),
			new TransitStopTime("trip-second", 2, "station-b", "line-b", SECOND_ARRIVAL, SECOND_ARRIVAL, 0, 0));
		var access = new LoadRouteTimetablePort.RouteAccessData(
			List.of(
				new PathwayNode("platform-transfer-a", "station-transfer", "line-a", "PLATFORM"),
				new PathwayNode("platform-transfer-b", "station-transfer", "line-b", "PLATFORM")),
			transfer.edges(),
			List.of(new TransferRule("transfer-rule", "station-transfer", "line-a", "station-transfer", "line-b",
				"IN_STATION", transfer.ruleSeconds(), "transfer", "transfer", transfer.evidence().getFirst().verificationStatus())),
			transfer.evidence());
		return new RouteTimetable(List.of(calendar()), List.of(), routes, trips, stopTimes, List.of(), List.of(), null, access);
	}

	private static RouteTimetable directTimetable() {
		var route = new TransitRoute("route-first", "line-a", "A", "First", "station-b", "Asia/Seoul");
		var trip = new TransitTrip("trip-direct", "route-first", "daily", "station-b", "down", "SUBWAY", "LOCAL", "3001", 0);
		var stopTimes = List.of(
			new TransitStopTime("trip-direct", 1, "station-a", "line-a", FIRST_DEPARTURE, FIRST_DEPARTURE, 0, 0),
			new TransitStopTime("trip-direct", 2, "station-b", "line-a", FIRST_ARRIVAL, FIRST_ARRIVAL, 0, 0));
		return new RouteTimetable(List.of(calendar()), List.of(), List.of(route), List.of(trip), stopTimes, List.of(),
			List.of(), null, LoadRouteTimetablePort.RouteAccessData.empty());
	}

	private static RouteTimetable withLegacyEntryAndExit(RouteTimetable timetable) {
		var access = timetable.routeAccessData();
		var nodes = new ArrayList<>(access.pathwayNodes());
		nodes.add(new PathwayNode("entrance", "station-a", null, "ENTRANCE"));
		nodes.add(new PathwayNode("platform-a", "station-a", "line-a", "PLATFORM"));
		nodes.add(new PathwayNode("platform-b", "station-b", "line-b", "PLATFORM"));
		nodes.add(new PathwayNode("outside", "station-b", null, "EXIT"));
		var edges = new ArrayList<>(access.pathwayEdges());
		edges.add(new PathwayEdge("entry", "entrance", "platform-a", 240, 180, false, false, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		edges.add(new PathwayEdge("exit", "platform-b", "outside", 180, 120, false, false, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		var evidence = new ArrayList<>(access.routeEdgeEvidence());
		evidence.add(new RouteEdgeEvidence("entry-evidence", "station-a", "line-a", "entry", "ENTRY",
			"OFFICIAL_SOURCE", "VERIFIED", true, null));
		evidence.add(new RouteEdgeEvidence("exit-evidence", "station-b", "line-b", "exit", "EXIT",
			"OFFICIAL_SOURCE", "VERIFIED", true, null));
		return new RouteTimetable(timetable.serviceCalendars(), timetable.serviceCalendarDates(),
			timetable.transitRoutes(), timetable.transitTrips(), timetable.transitStopTimes(),
			timetable.transitFrequencies(), List.of(), null,
			new LoadRouteTimetablePort.RouteAccessData(nodes, edges, access.transferRules(), evidence));
	}

	private static ServiceCalendar calendar() {
		return new ServiceCalendar("daily", true, true, true, true, true, true, true,
			LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Asia/Seoul");
	}
}
