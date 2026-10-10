package com.easysubway.route.application.service;

import com.easysubway.journey.application.TestRides;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.ActiveJourneySnapshotPort;
import com.easysubway.journey.application.JourneyCandidate;
import com.easysubway.journey.application.JourneyExecutionResult;
import com.easysubway.journey.application.JourneyRaptorPort;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyRaptorRealtimeView;
import com.easysubway.journey.application.JourneyRaptorRuntimeView;
import com.easysubway.journey.application.JourneyRealtimePort;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.JourneyRequestMeasurement;
import com.easysubway.journey.application.ServiceDayResolver;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransferRule;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import com.easysubway.journey.application.FacilityAvailabilityPort;
import com.easysubway.journey.application.FacilityAvailabilityView;
import com.easysubway.journey.application.FacilityStatusUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JourneyRaptorAdapterTest {

	private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
	private static final String ROUTE_BUNDLE_SHA = "a".repeat(64);
	private static final long GENERATION = 7;
	private static final Instant EFFECTIVE = Instant.parse("2026-06-30T23:50:00Z");
	private static final Instant VALID_UNTIL = Instant.parse("2026-07-01T02:00:00Z");

	@Test
	void timetableQueryOmitsCandidateWhenPathwayEdgeIsBlockedByFacility() {
		// #454: 차단 대상은 경로에 쓰이는 환승 간선이다(진입·하차 간선은 경로에 쓰지 않는다).
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, transferTimetable());
		var blockedFacilityView = FacilityAvailabilityView.blocked(EFFECTIVE, Set.of("transfer"));
		var adapter = new JourneyRaptorAdapter(() -> blockedFacilityView, false, FACILITY_CLOCK);

		var result = adapter.plan(
			transferRequest(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime),
			EFFECTIVE,
			null,
			measurement()
		);

		assertThat(result.candidates()).isEmpty();
	}

	@Test
	void timetableQueryDoesNotReflectTrainDelaysEvenWithFacilityAvailability() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		var freshFacilityView = FacilityAvailabilityView.empty(EFFECTIVE);
		var adapter = new JourneyRaptorAdapter(() -> freshFacilityView, false, FACILITY_CLOCK);

		var result = adapter.plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime),
			EFFECTIVE,
			null,
			measurement()
		);

		assertThat(result.candidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.realtimeDepartureTime()).isNull();
			assertThat(candidate.realtimeArrivalTime()).isNull();
			assertThat(candidate.plannedDepartureTime()).isEqualTo(EFFECTIVE);
			assertThat(candidate.plannedArrivalTime()).isEqualTo(Instant.parse("2026-07-01T00:10:00Z"));
			assertThat(candidate.timeSource()).isEqualTo(JourneyCandidate.TimeSource.TIMETABLE);
		});
	}

	@Test
	void realtimeQueryReflectsBothTrainDelaysAndFacilityBlockades() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		var realtimeRuntime = RaptorRealtimeRuntimeView.compile(
			"realtime-1", runtime, updates("trip", 180, 180, false, "realtime-1"));
		var realtimeObservation = new JourneyRealtimePort.RealtimeObservation(
			"realtime-1", ROUTE_BUNDLE_SHA, realtimeRuntime, VALID_UNTIL, true);

		// 1) With the only transfer blocked by facility, the path cannot be traversed
		var transferRuntime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, transferTimetable());
		var transferRealtime = RaptorRealtimeRuntimeView.compile(
			"realtime-1", transferRuntime, transferTimetableUpdates(180));
		var blockedFacilityView = FacilityAvailabilityView.blocked(EFFECTIVE, Set.of("transfer"));
		var blockedAdapter = new JourneyRaptorAdapter(() -> blockedFacilityView, false, FACILITY_CLOCK);
		var blockedResult = blockedAdapter.plan(
			transferRequest(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.REALTIME_REQUIRED),
			snapshot(transferRuntime),
			EFFECTIVE,
			new JourneyRealtimePort.RealtimeObservation("realtime-1", ROUTE_BUNDLE_SHA, transferRealtime, VALID_UNTIL, true),
			measurement()
		);
		assertThat(blockedResult.candidates()).isEmpty();

		// 2) With unblocked facility view, delay is reflected
		var unblockedAdapter = new JourneyRaptorAdapter(() -> FacilityAvailabilityView.empty(EFFECTIVE), false, FACILITY_CLOCK);
		var normalResult = unblockedAdapter.plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.REALTIME_REQUIRED),
			snapshot(runtime),
			EFFECTIVE,
			realtimeObservation,
			measurement()
		);
		assertThat(normalResult.candidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.realtimeArrivalTime()).isEqualTo(Instant.parse("2026-07-01T00:13:00Z"));
		});
	}

	@Test
	void requiredTrueWithStepFreeAndStaleFacilityStatusThrowsFacilityStatusUnavailable() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		var staleFacilityView = FacilityAvailabilityView.blocked(
			EFFECTIVE.minus(Duration.ofMinutes(6)), Set.of());
		var clock = Clock.fixed(EFFECTIVE, ServiceDayResolver.ZONE);
		var adapter = new JourneyRaptorAdapter(() -> staleFacilityView, true, clock);

		assertThatThrownBy(() -> adapter.plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime),
			EFFECTIVE,
			null,
			measurement()
		)).isInstanceOf(FacilityStatusUnavailableException.class)
			.hasMessageContaining("FACILITY_STATUS_UNAVAILABLE");
	}

	@Test
	void requiredTrueWithStandardRequestAndStaleFacilityStatusReturnsNormalRoute() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		var staleFacilityView = FacilityAvailabilityView.blocked(
			EFFECTIVE.minus(Duration.ofMinutes(6)), Set.of());
		var clock = Clock.fixed(EFFECTIVE, ServiceDayResolver.ZONE);
		var adapter = new JourneyRaptorAdapter(() -> staleFacilityView, true, clock);

		var result = adapter.plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime),
			EFFECTIVE,
			null,
			measurement()
		);

		assertThat(result.candidates()).isNotEmpty();
	}

	@Test
	void requiredFalseWithStepFreeAndStaleFacilityStatusPreservesExistingBehavior() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		var staleFacilityView = FacilityAvailabilityView.blocked(
			EFFECTIVE.minus(Duration.ofMinutes(6)), Set.of());
		var clock = Clock.fixed(EFFECTIVE, ServiceDayResolver.ZONE);
		var adapter = new JourneyRaptorAdapter(() -> staleFacilityView, false, clock);

		var result = adapter.plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime),
			EFFECTIVE,
			null,
			measurement()
		);

		assertThat(result.candidates()).isNotEmpty();
	}

	@Test
	void requiredTrueWithStepFreeAndFreshFacilityStatusReturnsStepFreeRoute() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		var freshFacilityView = FacilityAvailabilityView.empty(EFFECTIVE.minus(Duration.ofMinutes(1)));
		var clock = Clock.fixed(EFFECTIVE, ServiceDayResolver.ZONE);
		var adapter = new JourneyRaptorAdapter(() -> freshFacilityView, true, clock);

		var result = adapter.plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime),
			EFFECTIVE,
			null,
			measurement()
		);

		assertThat(result.candidates()).isNotEmpty();
	}

	@Test
	void requiredTrueWithStepFreeAndUnavailableFacilityStatusThrowsException() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		var clock = Clock.fixed(EFFECTIVE, ServiceDayResolver.ZONE);
		var adapter = new JourneyRaptorAdapter(() -> FacilityAvailabilityView.unavailable(), true, clock);

		assertThatThrownBy(() -> adapter.plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime),
			EFFECTIVE,
			null,
			measurement()
		)).isInstanceOf(FacilityStatusUnavailableException.class);
	}

	@Test
	void requiredTrueWithStepFreeAndNullObservedAtThrowsException() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		var clock = Clock.fixed(EFFECTIVE, ServiceDayResolver.ZONE);
		var viewWithoutTime = new com.easysubway.journey.application.SimpleFacilityAvailabilityView(
			true, null, Set.of());
		var adapter = new JourneyRaptorAdapter(() -> viewWithoutTime, true, clock);

		assertThatThrownBy(() -> adapter.plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime),
			EFFECTIVE,
			null,
			measurement()
		)).isInstanceOf(FacilityStatusUnavailableException.class);
	}

	@Test
	void handlesNullPortViewGracefully() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		var adapter = new JourneyRaptorAdapter(() -> null, false, FACILITY_CLOCK);
		var result = adapter.plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime),
			EFFECTIVE,
			null,
			measurement()
		);
		assertThat(result.candidates()).isNotEmpty();
	}

	@Test
	void handlesUnavailablePortViewGracefully() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		var adapter = new JourneyRaptorAdapter(() -> FacilityAvailabilityView.unavailable(), false, FACILITY_CLOCK);
		var result = adapter.plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime),
			EFFECTIVE,
			null,
			measurement()
		);
		assertThat(result.candidates()).isNotEmpty();
	}

	@Test
	void workspacePoolConstructorDefaultsToNoProviderAndNotRequired() {
		// 기본 생성자 배선은 "공급자 없음 + required=false"와 같아야 한다: 무단차 요청도 오류 없이 같은 후보를 낸다.
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		var request = request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE,
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED);
		var defaultWired = new JourneyRaptorAdapter(RouteTimetableRaptorPlanner.ScanWorkspacePool.shared())
			.plan(request, snapshot(runtime), EFFECTIVE, null, measurement());
		var explicitNoProvider = new JourneyRaptorAdapter(
			RouteTimetableRaptorPlanner.ScanWorkspacePool.shared(), FacilityAvailabilityPort.unavailable(), false, FACILITY_CLOCK)
			.plan(request, snapshot(runtime), EFFECTIVE, null, measurement());
		assertThat(defaultWired.candidates()).isNotEmpty();
		assertThat(defaultWired.candidates()).isEqualTo(explicitNoProvider.candidates());
	}

	@Test
	void standardRequestReturnsStepFreePathwayAlternativeAsStairFreeCandidate() {
		// #469 R1: 같은 환승역에 짧은 계단 동선과 긴 계단 없는 동선이 있으면 표준 요청도 계단 없는 여정을 함께 낸다.
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION,
			RouteTimetableRaptorPlannerAccessibleAlternativesTest.timetable(false, true));
		var request = accessibleAlternativesRequest(JourneyRequest.MobilityProfile.STANDARD, 1, 3);

		var result = new JourneyRaptorAdapter().plan(request, snapshot(runtime),
			RouteTimetableRaptorPlannerAccessibleAlternativesTest.READY_AT, null, measurement());

		assertThat(result.candidates())
			.extracting(JourneyCandidate::transferCount, candidate -> candidate.accessibility().stairFree())
			.containsExactly(org.assertj.core.groups.Tuple.tuple(1, false), org.assertj.core.groups.Tuple.tuple(1, true));
	}

	@Test
	void directPlanNeverReturnsMoreCandidatesThanAlternativeCount() {
		// #469 R2: 무단차 선호·환승 3·대안 3에서 파레토 여정이 4개여도 후보는 3개 이하다.
		// JourneyExecutionResult.Success는 alternativeCount를 넘는 후보를 거절하므로(검색 실패) 어댑터가 지켜야 한다.
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION,
			RouteTimetableRaptorPlannerAccessibleAlternativesTest.timetable(true, true));
		var request = accessibleAlternativesRequest(JourneyRequest.MobilityProfile.STEP_FREE, 3, 3);

		var result = new JourneyRaptorAdapter().plan(request, snapshot(runtime),
			RouteTimetableRaptorPlannerAccessibleAlternativesTest.READY_AT, null, measurement());

		assertThat(result.candidates()).hasSizeLessThanOrEqualTo(request.alternativeCount());
		assertThat(result.candidates())
			.extracting(JourneyCandidate::transferCount, candidate -> candidate.accessibility().stairFree())
			.containsExactly(org.assertj.core.groups.Tuple.tuple(2, false), org.assertj.core.groups.Tuple.tuple(1, true),
				org.assertj.core.groups.Tuple.tuple(0, true));
	}

	@Test
	void reportsCategoriesStairFreeIncludedAndUnobservedFacilityStatusByDefault() {
		// #469: 운영 기본 배선(시설 가동 정보 없음)에서는 facilityStatus가 UNOBSERVED다.
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION,
			RouteTimetableRaptorPlannerAccessibleAlternativesTest.timetable(true, true));
		var request = accessibleAlternativesRequest(JourneyRequest.MobilityProfile.STANDARD, 2, 3);

		var result = new JourneyRaptorAdapter().plan(request, snapshot(runtime),
			RouteTimetableRaptorPlannerAccessibleAlternativesTest.READY_AT, null, measurement());

		assertThat(result.candidates()).extracting(JourneyCandidate::alternativeCategories).containsExactly(
			List.of(com.easysubway.journey.application.JourneyAlternatives.Category.FASTEST),
			List.of(com.easysubway.journey.application.JourneyAlternatives.Category.STAIR_FREE),
			List.of(com.easysubway.journey.application.JourneyAlternatives.Category.FEWEST_TRANSFERS));
		assertThat(result.stairFreeAlternative()).isEqualTo(new com.easysubway.journey.application.JourneyAlternatives
			.StairFreeAlternative(com.easysubway.journey.application.JourneyAlternatives.StairFreeStatus.INCLUDED,
				com.easysubway.journey.application.JourneyAlternatives.FacilityStatus.UNOBSERVED));
	}

	@Test
	void outOfServiceStepFreePathwayIsNotFoundWithAppliedFacilityStatus() {
		// #469: 신선한 가동 정보가 계단 없는 동선을 막으면 확인된 사실이므로 UNDETERMINED가 아니라 NOT_FOUND다.
		Instant readyAt = RouteTimetableRaptorPlannerAccessibleAlternativesTest.READY_AT;
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION,
			RouteTimetableRaptorPlannerAccessibleAlternativesTest.timetable(false, true));
		var adapter = new JourneyRaptorAdapter(() -> FacilityAvailabilityView.blocked(readyAt, Set.of("e-h-step-free")),
			false, Clock.fixed(readyAt, ServiceDayResolver.ZONE));

		var result = adapter.plan(accessibleAlternativesRequest(JourneyRequest.MobilityProfile.STANDARD, 1, 3),
			snapshot(runtime), readyAt, null, measurement());

		assertThat(result.candidates()).extracting(candidate -> candidate.accessibility().stairFree()).containsExactly(false);
		assertThat(result.stairFreeAlternative()).isEqualTo(new com.easysubway.journey.application.JourneyAlternatives
			.StairFreeAlternative(com.easysubway.journey.application.JourneyAlternatives.StairFreeStatus.NOT_FOUND,
				com.easysubway.journey.application.JourneyAlternatives.FacilityStatus.APPLIED));
	}

	@Test
	void unverifiedStepFreePathwayIsReportedAsUndetermined() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION,
			RouteTimetableRaptorPlannerAccessibleAlternativesTest.timetable(false,
				RouteTimetableRaptorPlannerAccessibleAlternativesTest.StepFreePathway.UNVERIFIED));

		var result = new JourneyRaptorAdapter().plan(accessibleAlternativesRequest(JourneyRequest.MobilityProfile.STANDARD, 1, 3),
			snapshot(runtime), RouteTimetableRaptorPlannerAccessibleAlternativesTest.READY_AT, null, measurement());

		assertThat(result.candidates()).extracting(candidate -> candidate.accessibility().stairFree()).containsExactly(false);
		assertThat(result.stairFreeAlternative().status())
			.isEqualTo(com.easysubway.journey.application.JourneyAlternatives.StairFreeStatus.UNDETERMINED);
	}

	@Test
	void viaSearchComposesCategoriesAndReportsUnverifiedJunctionAsUndetermined() {
		// #469: 경유 검색도 같은 결과 구성 규칙을 쓰고, 경유역 접속 환승의 근거 없는 계단 없는 동선도 판정에 넣는다.
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION,
			RouteTimetableRaptorPlannerAccessibleAlternativesTest.viaTimetable(
				RouteTimetableRaptorPlannerAccessibleAlternativesTest.StepFreePathway.UNVERIFIED));

		var result = new JourneyRaptorAdapter().plan(viaRequest(JourneyRequest.MobilityProfile.STANDARD), snapshot(runtime),
			RouteTimetableRaptorPlannerAccessibleAlternativesTest.READY_AT, null, measurement());

		assertThat(result.candidates())
			.extracting(JourneyCandidate::transferCount, JourneyCandidate::alternativeCategories)
			.containsExactly(
				org.assertj.core.groups.Tuple.tuple(1, List.of(com.easysubway.journey.application.JourneyAlternatives.Category.FASTEST,
					com.easysubway.journey.application.JourneyAlternatives.Category.FEWEST_TRANSFERS)),
				org.assertj.core.groups.Tuple.tuple(2, List.of()));
		assertThat(result.stairFreeAlternative().status())
			.isEqualTo(com.easysubway.journey.application.JourneyAlternatives.StairFreeStatus.UNDETERMINED);
	}

	@Test
	void viaSearchWithVerifiedStepFreeJunctionIncludesStairFreeJourneyForStepFreePreference() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION,
			RouteTimetableRaptorPlannerAccessibleAlternativesTest.viaTimetable(
				RouteTimetableRaptorPlannerAccessibleAlternativesTest.StepFreePathway.VERIFIED));

		var result = new JourneyRaptorAdapter().plan(viaRequest(JourneyRequest.MobilityProfile.STEP_FREE), snapshot(runtime),
			RouteTimetableRaptorPlannerAccessibleAlternativesTest.READY_AT, null, measurement());

		assertThat(result.candidates()).isNotEmpty();
		assertThat(result.candidates()).anyMatch(candidate -> candidate.accessibility().stairFree()
			&& candidate.alternativeCategories().contains(
				com.easysubway.journey.application.JourneyAlternatives.Category.STAIR_FREE));
		assertThat(result.stairFreeAlternative().status())
			.isEqualTo(com.easysubway.journey.application.JourneyAlternatives.StairFreeStatus.INCLUDED);
	}

	@Test
	void unconfirmedStairStatePathwayIsReportedUndeterminedAndNeverStairFree() {
		// #469 F1(#480): seq126처럼 계단 상태가 UNKNOWN이거나 없는 동선은 계단 없음으로 응답하지 않는다.
		for (String state : java.util.Arrays.asList("UNKNOWN", null)) {
			var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION,
				RouteTimetableRaptorPlannerAccessibleAlternativesTest.unconfirmedStairStateTimetable(state));

			var result = new JourneyRaptorAdapter().plan(accessibleAlternativesRequest(JourneyRequest.MobilityProfile.STANDARD, 1, 3),
				snapshot(runtime), RouteTimetableRaptorPlannerAccessibleAlternativesTest.READY_AT, null, measurement());

			assertThat(result.candidates()).as("stair state %s", state).isNotEmpty().allSatisfy(candidate -> {
				assertThat(candidate.accessibility().stairFree()).isFalse();
				assertThat(candidate.alternativeCategories())
					.doesNotContain(com.easysubway.journey.application.JourneyAlternatives.Category.STAIR_FREE);
			});
			assertThat(result.stairFreeAlternative().status()).as("stair state %s", state)
				.isEqualTo(com.easysubway.journey.application.JourneyAlternatives.StairFreeStatus.UNDETERMINED);
		}
	}

	@Test
	void reasonCodesReflectConfirmedStairsAndConfirmedStairFreeCandidates() {
		// #503: 확정 계단 포함 여정은 STAIRS_INCLUDED, 계단 없음이 확정된 여정은 VERIFIED다.
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION,
			RouteTimetableRaptorPlannerAccessibleAlternativesTest.timetable(false, true));

		var result = new JourneyRaptorAdapter().plan(accessibleAlternativesRequest(JourneyRequest.MobilityProfile.STANDARD, 1, 3),
			snapshot(runtime), RouteTimetableRaptorPlannerAccessibleAlternativesTest.READY_AT, null, measurement());

		assertThat(result.candidates())
			.extracting(candidate -> candidate.accessibility().stairFree(), candidate -> candidate.accessibility().reasonCodes())
			.containsExactly(
				org.assertj.core.groups.Tuple.tuple(false, List.of("ACCESSIBILITY_STAIRS_INCLUDED")),
				org.assertj.core.groups.Tuple.tuple(true, List.of("ACCESSIBILITY_VERIFIED")));
	}

	@Test
	void reasonCodesReportUndeterminedWhenATransferStairStateIsUnconfirmed() {
		// #503: 계단 상태가 UNKNOWN이거나 없는 환승을 지난 여정은 VERIFIED를 받지 않고 UNDETERMINED를 받는다.
		for (String state : java.util.Arrays.asList("UNKNOWN", null)) {
			var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION,
				RouteTimetableRaptorPlannerAccessibleAlternativesTest.unconfirmedStairStateTimetable(state));

			var result = new JourneyRaptorAdapter().plan(accessibleAlternativesRequest(JourneyRequest.MobilityProfile.STANDARD, 1, 3),
				snapshot(runtime), RouteTimetableRaptorPlannerAccessibleAlternativesTest.READY_AT, null, measurement());

			assertThat(result.candidates()).as("stair state %s", state).isNotEmpty().allSatisfy(candidate ->
				assertThat(candidate.accessibility().reasonCodes()).containsExactly("ACCESSIBILITY_UNDETERMINED"));
		}
	}

	private static JourneyRequest viaRequest(JourneyRequest.MobilityProfile profile) {
		return new JourneyRequest(
			REQUEST_ID, RouteTimetableRaptorPlannerAccessibleAlternativesTest.ORIGIN,
			RouteTimetableRaptorPlannerAccessibleAlternativesTest.DESTINATION, "v",
			new JourneyRequest.Departure.Scheduled(RouteTimetableRaptorPlannerAccessibleAlternativesTest.READY_AT),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD, profile,
			JourneyRequest.ConstraintMode.NONE, 3, 3, () -> false);
	}

	private static JourneyRequest accessibleAlternativesRequest(
		JourneyRequest.MobilityProfile profile, int maxTransfers, int alternativeCount
	) {
		return new JourneyRequest(
			REQUEST_ID, RouteTimetableRaptorPlannerAccessibleAlternativesTest.ORIGIN,
			RouteTimetableRaptorPlannerAccessibleAlternativesTest.DESTINATION,
			new JourneyRequest.Departure.Scheduled(RouteTimetableRaptorPlannerAccessibleAlternativesTest.READY_AT),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD, profile,
			JourneyRequest.ConstraintMode.NONE, maxTransfers, alternativeCount, () -> false);
	}

	// 대체 환승 픽스처: 기본 선택은 최단 검증 거리 "transfer"(100m, STANDARD 80초), 차단 시 "transfer-alt"(200m, 160초).
	private static final Clock FACILITY_CLOCK = Clock.fixed(EFFECTIVE, ServiceDayResolver.ZONE);

	@Test
	void throwingFacilityPortFailsRequiredStepFreeRequestAsFacilityStatusUnavailable() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		FacilityAvailabilityPort throwingPort = () -> {
			throw new IllegalStateException("facility provider down");
		};
		var adapter = new JourneyRaptorAdapter(throwingPort, true, FACILITY_CLOCK);

		assertThatThrownBy(() -> adapter.plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime), EFFECTIVE, null, measurement()))
			.isInstanceOf(FacilityStatusUnavailableException.class)
			.hasMessageContaining("FACILITY_STATUS_UNAVAILABLE");
	}

	@Test
	void throwingFacilityPortPlansOtherRequestsWithoutFacilityBlocks() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, alternateTransferTimetable());
		FacilityAvailabilityPort throwingPort = () -> {
			throw new IllegalStateException("facility provider down");
		};

		var requiredNone = new JourneyRaptorAdapter(throwingPort, true, FACILITY_CLOCK).plan(
			transferRequest(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime), EFFECTIVE, null, measurement());
		var notRequiredStepFree = new JourneyRaptorAdapter(throwingPort, false, FACILITY_CLOCK).plan(
			transferRequest(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime), EFFECTIVE, null, measurement());

		assertThat(requiredNone.candidates()).singleElement().satisfies(candidate ->
			assertThat(transferLeg(candidate)).isEqualTo(stationTransfer(80)));
		assertThat(notRequiredStepFree.candidates()).singleElement().satisfies(candidate ->
			assertThat(transferLeg(candidate)).isEqualTo(stationTransfer(80)));
	}

	@Test
	void freshFacilityViewBlocksTransitionForNoneRequest() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, alternateTransferTimetable());
		var freshView = FacilityAvailabilityView.blocked(EFFECTIVE.minus(Duration.ofMinutes(1)), Set.of("transfer"));
		var adapter = new JourneyRaptorAdapter(() -> freshView, false, FACILITY_CLOCK);

		var result = adapter.plan(
			transferRequest(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime), EFFECTIVE, null, measurement());

		assertThat(result.candidates()).singleElement().satisfies(candidate ->
			assertThat(transferLeg(candidate)).isEqualTo(stationTransfer(160)));
	}

	@Test
	void staleFacilityViewDoesNotBlockNoneRequest() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, alternateTransferTimetable());
		var staleView = FacilityAvailabilityView.blocked(EFFECTIVE.minus(Duration.ofMinutes(6)), Set.of("transfer"));

		for (boolean required : new boolean[] {false, true}) {
			var result = new JourneyRaptorAdapter(() -> staleView, required, FACILITY_CLOCK).plan(
				transferRequest(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
					JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
				snapshot(runtime), EFFECTIVE, null, measurement());

			assertThat(result.candidates()).singleElement().satisfies(candidate ->
				assertThat(transferLeg(candidate)).isEqualTo(stationTransfer(80)));
		}
	}

	@Test
	void blockedEdgeUnknownToCapturedBundleFailsRequiredStepFreeRequest() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, alternateTransferTimetable());
		var mismatchedView = FacilityAvailabilityView.blocked(EFFECTIVE, Set.of("edge-from-another-bundle"));
		var adapter = new JourneyRaptorAdapter(() -> mismatchedView, true, FACILITY_CLOCK);

		assertThatThrownBy(() -> adapter.plan(
			transferRequest(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime), EFFECTIVE, null, measurement()))
			.isInstanceOf(FacilityStatusUnavailableException.class)
			.hasMessageContaining("FACILITY_STATUS_UNAVAILABLE");
	}

	@Test
	void blockedEdgeUnknownToCapturedBundleRejectsWholeViewForNoneRequest() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, alternateTransferTimetable());
		var mismatchedView = FacilityAvailabilityView.blocked(EFFECTIVE, Set.of("transfer", "edge-from-another-bundle"));
		var adapter = new JourneyRaptorAdapter(() -> mismatchedView, true, FACILITY_CLOCK);

		var result = adapter.plan(
			transferRequest(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime), EFFECTIVE, null, measurement());

		assertThat(result.candidates()).singleElement().satisfies(candidate ->
			assertThat(transferLeg(candidate)).isEqualTo(stationTransfer(80)));
	}

	@Test
	void futureObservedAtBeyondClockSkewAllowanceIsNotFresh() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, alternateTransferTimetable());
		var withinSkew = FacilityAvailabilityView.blocked(EFFECTIVE.plusSeconds(30), Set.of("transfer"));
		var beyondSkew = FacilityAvailabilityView.blocked(EFFECTIVE.plusSeconds(31), Set.of("transfer"));

		assertThatThrownBy(() -> new JourneyRaptorAdapter(() -> beyondSkew, true, FACILITY_CLOCK).plan(
			transferRequest(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime), EFFECTIVE, null, measurement()))
			.isInstanceOf(FacilityStatusUnavailableException.class);
		var beyondSkewNone = new JourneyRaptorAdapter(() -> beyondSkew, true, FACILITY_CLOCK).plan(
			transferRequest(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime), EFFECTIVE, null, measurement());
		var withinSkewNone = new JourneyRaptorAdapter(() -> withinSkew, true, FACILITY_CLOCK).plan(
			transferRequest(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime), EFFECTIVE, null, measurement());

		assertThat(beyondSkewNone.candidates()).singleElement().satisfies(candidate ->
			assertThat(transferLeg(candidate)).isEqualTo(stationTransfer(80)));
		assertThat(withinSkewNone.candidates()).singleElement().satisfies(candidate ->
			assertThat(transferLeg(candidate)).isEqualTo(stationTransfer(160)));
	}

	@Test
	void realtimeQueryAppliesTrainDelayAndAvoidsBlockedTransferTogether() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, alternateTransferTimetable());
		var realtimeRuntime = RaptorRealtimeRuntimeView.compile("realtime-1", runtime, transferTimetableUpdates(180));
		var realtimeObservation = new JourneyRealtimePort.RealtimeObservation(
			"realtime-1", ROUTE_BUNDLE_SHA, realtimeRuntime, VALID_UNTIL, true);
		var blockedView = FacilityAvailabilityView.blocked(EFFECTIVE, Set.of("transfer"));

		var result = new JourneyRaptorAdapter(() -> blockedView, false, FACILITY_CLOCK).plan(
			transferRequest(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.REALTIME_REQUIRED),
			snapshot(runtime), EFFECTIVE, realtimeObservation, measurement());

		// 모든 열차 +180초. trip-first 00:03Z→00:13Z, 우회 환승 160초+여유 60초로 trip-second 00:33Z→00:43Z.
		assertThat(result.candidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.plannedArrivalTime()).isEqualTo(Instant.parse("2026-07-01T00:40:00Z"));
			assertThat(candidate.realtimeArrivalTime()).isEqualTo(Instant.parse("2026-07-01T00:43:00Z"));
			assertThat(candidate.legs()).containsExactly(
				TestRides.candidateRide("line-a", "trip-first", "station-transfer", "station-a", "station-transfer",
					Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-01T00:10:00Z"),
					Instant.parse("2026-07-01T00:03:00Z"), Instant.parse("2026-07-01T00:13:00Z")),
				stationTransfer(160),
				TestRides.candidateRide("line-b", "trip-second", "station-b", "station-transfer", "station-b",
					Instant.parse("2026-07-01T00:30:00Z"), Instant.parse("2026-07-01T00:40:00Z"),
					Instant.parse("2026-07-01T00:33:00Z"), Instant.parse("2026-07-01T00:43:00Z")));
		});
	}

	@Test
	void rejectsRealtimeObservationsThatDoNotMatchTheCapturedGeneration() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		var adapter = new JourneyRaptorAdapter();
		var realtimeRequest = request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
			JourneyRequest.TimePolicy.REALTIME_REQUIRED);
		JourneyRaptorRealtimeView foreignView = new JourneyRaptorRealtimeView() {
			@Override public String identity() { return "realtime-1"; }
			@Override public String routeBundleSha256() { return ROUTE_BUNDLE_SHA; }
			@Override public long generation() { return GENERATION; }
		};

		for (var observation : java.util.Arrays.asList(
			null,
			new JourneyRealtimePort.RealtimeObservation("realtime-1", ROUTE_BUNDLE_SHA, foreignView, VALID_UNTIL, true))) {
			assertThatThrownBy(() -> adapter.plan(realtimeRequest, snapshot(runtime), EFFECTIVE, observation, measurement()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("realtime runtime view does not match captured Journey generation");
		}
	}

	@Test
	void plansOneTimetableCandidateFromTheCapturedCompiledRuntimeOnly() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		var adapter = new JourneyRaptorAdapter();

		JourneyRaptorPort.PlanResult result = adapter.plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime),
			EFFECTIVE,
			null,
			measurement()
		);

		assertThat(result.queryId()).isEqualTo(REQUEST_ID);
		assertThat(result.candidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.journeyId()).matches("[a-f0-9]{64}");
			assertThat(candidate.plannedDepartureTime()).isEqualTo(EFFECTIVE);
			assertThat(candidate.plannedArrivalTime()).isEqualTo(Instant.parse("2026-07-01T00:10:00Z"));
			assertThat(candidate.realtimeDepartureTime()).isNull();
			assertThat(candidate.realtimeArrivalTime()).isNull();
			assertThat(candidate.durationSeconds()).isEqualTo(1_200);
			assertThat(candidate.transferCount()).isZero();
			assertThat(candidate.walkingDistanceMeters()).isZero();
			assertThat(candidate.timeSource()).isEqualTo(JourneyCandidate.TimeSource.TIMETABLE);
			assertThat(candidate.accessibility().stairFree()).isTrue();
			assertThat(candidate.accessibility().reasonCodes()).containsExactly("ACCESSIBILITY_VERIFIED");
			// #454: 출발역 승강장에서 승차해 도착역 승강장에서 내린다(진입·하차 구간 없음).
			assertThat(candidate.legs()).containsExactly(TestRides.candidateRide(
				"line", "trip", "station-b", "station-a", "station-b",
				Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-01T00:10:00Z"), null, null));
		});
		assertThat(result.scanMetrics().expandedRoutes()).isGreaterThanOrEqualTo(0);
		assertThat(result.scanMetrics().expandedTrips()).isGreaterThanOrEqualTo(0);
		assertThat(result.scanMetrics().expandedTransfers()).isGreaterThanOrEqualTo(0);
		assertThat(result.boundaryReceipt()).isEqualTo(JourneyRaptorPort.RouteBoundaryReceipt.observed(0));
		assertThat(result.measurementReceipt()).isEqualTo(JourneyRaptorPort.RouteMeasurementReceipt.unobservable());
	}

	@Test
	void bindsFallbackObservationToTheExactSnapshotRequestIdentity() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		var activeServingIdentity = new JourneyExecutionResult.ActiveServingIdentity(
			JourneyExecutionResult.ActiveServingIdentity.Status.OBSERVED,
			"b".repeat(64), "c".repeat(64), "sha256:" + "d".repeat(64), "e".repeat(40), "03:00");
		var activeReadinessIdentity = activeReadinessIdentity();
		var requestIdentity = new ActiveJourneySnapshotPort.RequestExecutionIdentity(
			REQUEST_ID, ROUTE_BUNDLE_SHA, GENERATION, activeReadinessIdentity, activeServingIdentity);
		var requestMeasurement = new JourneyRequestMeasurement(REQUEST_ID);
		requestMeasurement.observeActiveRegistryRead(REQUEST_ID, ROUTE_BUNDLE_SHA, GENERATION);
		var snapshotObservation = requestMeasurement.bindActiveIdentity(requestIdentity);
		var measurement = ActiveJourneySnapshotPort.SnapshotMeasurementReceipt.observed(snapshotObservation);

		var result = new JourneyRaptorAdapter().plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime, measurement), EFFECTIVE, null, requestMeasurement);

		assertThat(result.measurementReceipt())
			.isEqualTo(JourneyRaptorPort.RouteMeasurementReceipt.observed(
				new JourneyRequestMeasurement.RouteObservation(requestIdentity, 0)));

		var otherIdentity = new ActiveJourneySnapshotPort.RequestExecutionIdentity(
			"01ARZ3NDEKTSV4RRFFQ69G5FAW", ROUTE_BUNDLE_SHA, GENERATION,
			activeReadinessIdentity, activeServingIdentity);
		var mismatchedMeasurement = new JourneyRequestMeasurement(otherIdentity.requestId());
		mismatchedMeasurement.observeActiveRegistryRead(
			otherIdentity.requestId(), otherIdentity.routeBundleSha256(), otherIdentity.generation());
		var mismatched = new JourneyRaptorAdapter().plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime, ActiveJourneySnapshotPort.SnapshotMeasurementReceipt.observed(
				mismatchedMeasurement.bindActiveIdentity(otherIdentity))),
			EFFECTIVE, null, mismatchedMeasurement);
		assertThat(mismatched.measurementReceipt())
			.isEqualTo(JourneyRaptorPort.RouteMeasurementReceipt.unobservable());
	}

	@Test
	void selectsMinimumVerifiedDistanceWhenBaselineDurationOrderDisagrees() {
		// #454: 검증 환승 후보 선택 규칙은 환승 간선에 적용된다. 50m ÷ 4,500 m/h = 40초.
		var runtime = RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA,
			GENERATION,
			transferChoiceTimetable(
				transferEdge("baseline-short", 10, 100, false, "OFFICIAL_SOURCE"),
				transferEdge("distance-short", 200, 50, false, "OFFICIAL_SOURCE")));

		var candidate = new JourneyRaptorAdapter().plan(
			transferRequest(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime), EFFECTIVE, null, measurement()).candidates().getFirst();

		assertThat(transferLeg(candidate)).isEqualTo(stationTransfer(40));
	}

	@Test
	void prefersVerifiedStepFreeTransferOverShorterVerifiedStairsForStepFreeProfile() {
		// 무단차 80m: 64초 + 시설 대기 60초 = 124초.
		var runtime = RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA,
			GENERATION,
			transferChoiceTimetable(
				transferEdge("stairs-short", 10, 10, true, "OFFICIAL_SOURCE"),
				transferEdge("step-free-long", 80, 80, false, "OFFICIAL_SOURCE")));

		var candidate = new JourneyRaptorAdapter().plan(
			transferRequest(JourneyRequest.MobilityProfile.STEP_FREE, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime), EFFECTIVE, null, measurement()).candidates().getFirst();

		assertThat(transferLeg(candidate)).isEqualTo(stationTransfer(124));
		assertThat(candidate.accessibility().stairFree()).isTrue();
	}

	@Test
	void prefersShortestVerifiedDistanceWhenStepFreeCandidatesHaveSameStairsStatus() {
		// 무단차 50m: 40초 + 시설 대기 60초 = 100초.
		var runtime = RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA,
			GENERATION,
			transferChoiceTimetable(
				transferEdge("step-free-duration-short", 10, 80, false, "OFFICIAL_SOURCE"),
				transferEdge("step-free-distance-short", 80, 50, false, "OFFICIAL_SOURCE")));

		var candidate = new JourneyRaptorAdapter().plan(
			transferRequest(JourneyRequest.MobilityProfile.STEP_FREE, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime), EFFECTIVE, null, measurement()).candidates().getFirst();

		assertThat(transferLeg(candidate)).isEqualTo(stationTransfer(100));
		assertThat(candidate.accessibility().stairFree()).isTrue();
	}

	@Test
	void retainsFirstStepFreeCandidateWhenLaterStairsOrLongerStepFreeTransferIsCloserInBaselineTime() {
		var runtime = RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA,
			GENERATION,
			transferChoiceTimetable(
				transferEdge("step-free-first", 10, 50, false, "OFFICIAL_SOURCE"),
				transferEdge("stairs-short", 20, 10, true, "OFFICIAL_SOURCE"),
				transferEdge("step-free-long", 5, 80, false, "OFFICIAL_SOURCE")));

		var candidate = new JourneyRaptorAdapter().plan(
			transferRequest(JourneyRequest.MobilityProfile.STEP_FREE, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime), EFFECTIVE, null, measurement()).candidates().getFirst();

		assertThat(transferLeg(candidate)).isEqualTo(stationTransfer(100));
		assertThat(candidate.accessibility().stairFree()).isTrue();
	}

	@Test
	void skipsStatusOnlyVerifiedLowConfidenceCandidateForFullyVerifiedJourneyPath() {
		// 출처를 믿을 수 없는 짧은 환승은 건너뛰고 공식 80m(64초)를 쓴다.
		var runtime = RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA,
			GENERATION,
			transferChoiceTimetable(
				transferEdge("untrusted-short", 10, 10, false, "UNTRUSTED"),
				transferEdge("verified-long", 200, 80, false, "OFFICIAL_SOURCE")));

		var candidates = new JourneyRaptorAdapter().plan(
			transferRequest(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED), snapshot(runtime), EFFECTIVE, null, measurement()).candidates();

		assertThat(candidates).singleElement().satisfies(candidate ->
			assertThat(transferLeg(candidate)).isEqualTo(stationTransfer(64)));
	}

	@Test
	void ignoresVerifiedZeroDistanceEntryAndExitAcrossWalkingPaces() {
		var runtime = RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA, GENERATION, timetable(verifiedAccess(false, 0, 0)));
		var adapter = new JourneyRaptorAdapter();
		var slow = adapter.plan(request(
			JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE,
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW
		), snapshot(runtime), EFFECTIVE, null, measurement()).candidates().getFirst();
		var fast = adapter.plan(request(
			JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE,
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.FAST
		), snapshot(runtime), EFFECTIVE, null, measurement()).candidates().getFirst();

		// #454: 진입·하차 간선은 무시되므로 걸음 속도와 무관하게 승차 구간만 남는다.
		assertThat(slow.legs()).singleElement().isInstanceOf(JourneyCandidate.Ride.class);
		assertThat(fast.legs()).singleElement().isInstanceOf(JourneyCandidate.Ride.class);
		assertThat(slow.plannedArrivalTime()).isEqualTo(Instant.parse("2026-07-01T00:10:00Z"));
		assertThat(fast.plannedArrivalTime()).isEqualTo(Instant.parse("2026-07-01T00:10:00Z"));
		assertThat(fast.journeyId()).isEqualTo(slow.journeyId());
	}

	@Test
	void appliesWalkingPaceOnlyToVerifiedTransferDurationArrivalAndJourneyIdentity() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, transferTimetable());
		var adapter = new JourneyRaptorAdapter();
		var slow = adapter.plan(
			transferRequest(JourneyRequest.WalkingPace.SLOW), snapshot(runtime), EFFECTIVE, null, measurement())
			.candidates().getFirst();
		var standard = adapter.plan(
			transferRequest(JourneyRequest.WalkingPace.STANDARD), snapshot(runtime), EFFECTIVE, null, measurement())
			.candidates().getFirst();
		var fast = adapter.plan(
			transferRequest(JourneyRequest.WalkingPace.FAST), snapshot(runtime), EFFECTIVE, null, measurement())
			.candidates().getFirst();

		assertThat(transferLeg(slow)).isEqualTo(stationTransfer(103));
		assertThat(transferLeg(standard)).isEqualTo(stationTransfer(80));
		assertThat(transferLeg(fast)).isEqualTo(stationTransfer(60));
		assertThat(fast.plannedArrivalTime()).isEqualTo(Instant.parse("2026-07-01T00:22:00Z"));
		assertThat(standard.plannedArrivalTime()).isEqualTo(Instant.parse("2026-07-01T00:40:00Z"));
		assertThat(slow.plannedArrivalTime()).isEqualTo(Instant.parse("2026-07-01T00:40:00Z"));
		assertThat(fast.journeyId()).isNotEqualTo(standard.journeyId());
		assertThat(standard.journeyId()).isNotEqualTo(slow.journeyId());
	}

	@Test
	void preservesVerifiedTransferLegIdentityCountWalkingTotalAndOrder() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, transferTimetable());
		var transferRequest = new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 1, 1, () -> false);

		var candidate = new JourneyRaptorAdapter().plan(
			transferRequest, snapshot(runtime), EFFECTIVE, null, measurement()).candidates().getFirst();

		assertThat(candidate.transferCount()).isEqualTo(1);
		assertThat(candidate.walkingDistanceMeters()).isEqualTo(100);
		assertThat(candidate.legs()).containsExactly(
			TestRides.candidateRide(
				"line-a", "trip-first", "station-transfer", "station-a", "station-transfer",
				Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-01T00:10:00Z"), null, null),
			new JourneyCandidate.Transfer("station-transfer", "station-transfer", 80),
			TestRides.candidateRide(
				"line-b", "trip-second", "station-b", "station-transfer", "station-b",
				Instant.parse("2026-07-01T00:30:00Z"), Instant.parse("2026-07-01T00:40:00Z"), null, null));
	}

	@Test
	void plansChainedTwoLegRaptorCandidateWithWaypoint() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, waypointTimetable());
		var waypointRequest = new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", "station-transfer", new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 1, () -> false);

		var candidate = new JourneyRaptorAdapter().plan(
			waypointRequest, snapshot(runtime), EFFECTIVE, null, measurement()).candidates().getFirst();

		assertThat(candidate.transferCount()).isEqualTo(1);
		assertThat(candidate.walkingDistanceMeters()).isEqualTo(100);
		assertThat(candidate.legs()).hasSize(3);
		assertThat(candidate.legs().get(0)).isEqualTo(TestRides.candidateRide(
			"line-a", "trip-first", "station-transfer", "station-a", "station-transfer",
			Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-01T00:10:00Z"), null, null));
		var transfer = (JourneyCandidate.Transfer) candidate.legs().get(1);
		assertThat(transfer.fromStationId()).isEqualTo("station-transfer");
		assertThat(transfer.toStationId()).isEqualTo("station-transfer");
		assertThat(transfer.durationSeconds()).isEqualTo(120);
		assertThat(transfer.transferType()).isNull();
		assertThat(transfer.farePenaltyApplies()).isNull();
		assertThat(transfer.transferLimitMinutes()).isNull();
		assertThat(candidate.legs().get(2)).isEqualTo(TestRides.candidateRide(
			"line-b", "trip-second", "station-b", "station-transfer", "station-b",
			Instant.parse("2026-07-01T00:30:00Z"), Instant.parse("2026-07-01T00:40:00Z"), null, null));
	}

	@Test
	void plansWaypointTransferOnSameLineWithZeroWalkingMovementAndPlatformDwell() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, sameLineWaypointTimetable());
		var sameLineRequest = new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", "station-via", new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 1, () -> false);

		var candidate = new JourneyRaptorAdapter().plan(
			sameLineRequest, snapshot(runtime), EFFECTIVE, null, measurement()).candidates().getFirst();

		assertThat(candidate.transferCount()).isEqualTo(1);
		assertThat(candidate.walkingDistanceMeters()).isZero(); // 같은 노선 정차 대기만 있고 걷지 않는다
		assertThat(candidate.legs()).hasSize(3);
		assertThat(candidate.legs().get(0)).isEqualTo(TestRides.candidateRide(
			"line-a", "trip-first", "station-via", "station-a", "station-via",
			Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-01T00:10:00Z"), null, null));
		var transfer = (JourneyCandidate.Transfer) candidate.legs().get(1);
		assertThat(transfer.fromStationId()).isEqualTo("station-via");
		assertThat(transfer.toStationId()).isEqualTo("station-via");
		assertThat(transfer.durationSeconds()).isZero();
		assertThat(transfer.transferType()).isNull();
		assertThat(transfer.farePenaltyApplies()).isNull();
		assertThat(candidate.legs().get(2)).isEqualTo(TestRides.candidateRide(
			"line-a", "trip-second", "station-b", "station-via", "station-b",
			Instant.parse("2026-07-01T00:20:00Z"), Instant.parse("2026-07-01T00:30:00Z"), null, null));
	}

	@Test
	void chainsAWaypointLegThatAlreadyContainsATransfer() {
		// #454: 앞 구간이 승차·환승·승차이면 마지막 승차만 다시 만들고 앞의 승차·환승은 그대로 잇는다.
		var calendar = new ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Asia/Seoul");
		var routes = List.of(
			new TransitRoute("route-a", "line-a", "A", "A", "station-t", "Asia/Seoul"),
			new TransitRoute("route-b", "line-b", "B", "B", "station-b", "Asia/Seoul"));
		var trips = List.of(
			new TransitTrip("trip-first", "route-a", "daily", "station-t", "down", "SUBWAY", "LOCAL", "3001", 0),
			new TransitTrip("trip-mid", "route-b", "daily", "station-via", "down", "SUBWAY", "LOCAL", "3002", 0),
			new TransitTrip("trip-last", "route-b", "daily", "station-b", "down", "SUBWAY", "LOCAL", "3003", 0));
		var stopTimes = List.of(
			new TransitStopTime("trip-first", 1, "station-a", "line-a", 32_400, 32_400, 0, 0),
			new TransitStopTime("trip-first", 2, "station-t", "line-a", 33_000, 33_000, 0, 0),
			new TransitStopTime("trip-mid", 1, "station-t", "line-b", 33_600, 33_600, 0, 0),
			new TransitStopTime("trip-mid", 2, "station-via", "line-b", 34_200, 34_200, 0, 0),
			new TransitStopTime("trip-last", 1, "station-via", "line-b", 34_800, 34_800, 0, 0),
			new TransitStopTime("trip-last", 2, "station-b", "line-b", 35_400, 35_400, 0, 0));
		var access = new LoadRouteTimetablePort.RouteAccessData(
			List.of(new PathwayNode("t-a", "station-t", "line-a", "PLATFORM"),
				new PathwayNode("t-b", "station-t", "line-b", "PLATFORM")),
			List.of(new PathwayEdge("t-transfer", "t-a", "t-b", 120, 100, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE")),
			List.of(new TransferRule("t-rule", "station-t", "line-a", "station-t", "line-b", "IN_STATION",
				120, "t-transfer", "t-transfer", "VERIFIED")),
			List.of(new RouteEdgeEvidence("t-evidence", "station-t", "line-b", "t-transfer", "TRANSFER",
				"OFFICIAL_SOURCE", "VERIFIED", true, null)));
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, new RouteTimetable(
			List.of(calendar), List.of(), routes, trips, stopTimes, List.of(), List.of(), null, access));
		var waypointRequest = new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", "station-via", new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 1, () -> false);

		var candidate = new JourneyRaptorAdapter().plan(
			waypointRequest, snapshot(runtime), EFFECTIVE, null, measurement()).candidates().getFirst();

		assertThat(candidate.transferCount()).isEqualTo(2);
		assertThat(candidate.walkingDistanceMeters()).isEqualTo(100);
		assertThat(candidate.legs()).extracting(JourneyCandidate.Leg::type).containsExactly(
			JourneyCandidate.LegType.RIDE, JourneyCandidate.LegType.TRANSFER, JourneyCandidate.LegType.RIDE,
			JourneyCandidate.LegType.TRANSFER, JourneyCandidate.LegType.RIDE);
		assertThat(candidate.legs().get(1)).isEqualTo(new JourneyCandidate.Transfer("station-t", "station-t", 80));
		assertThat(candidate.legs().get(3)).isEqualTo(new JourneyCandidate.Transfer("station-via", "station-via", 0));
		assertThat(candidate.plannedArrivalTime()).isEqualTo(Instant.parse("2026-07-01T00:50:00Z"));
	}

	@Test
	void waypointJunctionWithUnconfirmedStairStateReportsUndetermined() {
		// #503: 경유역 접속 환승의 계단 상태가 미확정이면 연결 후보도 UNDETERMINED를 받는다.
		var request = new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", "station-transfer", new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 1, () -> false);
		for (String state : java.util.Arrays.asList("UNKNOWN", null)) {
			var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION,
				waypointTimetable("IN_STATION", 120, 34_200, true, state));

			var result = new JourneyRaptorAdapter().plan(request, snapshot(runtime), EFFECTIVE, null, measurement());

			assertThat(result.candidates()).as("stair state %s", state).isNotEmpty().allSatisfy(candidate -> {
				assertThat(candidate.accessibility().stairFree()).isFalse();
				assertThat(candidate.accessibility().reasonCodes()).containsExactly("ACCESSIBILITY_UNDETERMINED");
			});
		}
	}

	@Test
	void plansOutOfStationWaypointTransferWithinTimeLimitWithoutPenalty() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION,
			waypointTimetable("OUT_OF_STATION", 120, 34_200, true)); // 1,200s elapsed < 1,800s limit
		var request = new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", "station-transfer", new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 1, () -> false);

		var candidate = new JourneyRaptorAdapter().plan(
			request, snapshot(runtime), EFFECTIVE, null, measurement()).candidates().getFirst();

		var transfer = (JourneyCandidate.Transfer) candidate.legs().get(1);
		assertThat(transfer.transferType()).isEqualTo("OUT_OF_STATION");
		assertThat(transfer.farePenaltyApplies()).isFalse();
		assertThat(transfer.transferLimitMinutes()).isEqualTo(30);
	}

	@Test
	void plansOutOfStationWaypointTransferWithTimeoutPenaltyWhenExceedingLimit() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION,
			waypointTimetable("OUT_OF_STATION", 120, 35_400, true)); // 2,400s elapsed > 1,800s limit
		var request = new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", "station-transfer", new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 1, () -> false);

		var candidate = new JourneyRaptorAdapter().plan(
			request, snapshot(runtime), EFFECTIVE, null, measurement()).candidates().getFirst();

		var transfer = (JourneyCandidate.Transfer) candidate.legs().get(1);
		assertThat(transfer.transferType()).isEqualTo("OUT_OF_STATION");
		assertThat(transfer.farePenaltyApplies()).isTrue();
		assertThat(transfer.transferLimitMinutes()).isEqualTo(30);
	}

	@Test
	void rejectsWaypointWhenNoTransferTransitionExistsBetweenLines() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION,
			waypointTimetable("IN_STATION", 120, 34_200, false)); // hasTransferRule = false
		var request = new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", "station-transfer", new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 1, () -> false);

		var result = new JourneyRaptorAdapter().plan(
			request, snapshot(runtime), EFFECTIVE, null, measurement());

		assertThat(result.candidates()).isEmpty();
	}

	@Test
	void rejectsWaypointWhenTransferDurationExceedsAvailableSlack() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION,
			waypointTimetable("IN_STATION", 1500, 34_200, true)); // 1,500s walk > 1,200s slack
		var request = new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", "station-transfer", new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 1, () -> false);

		var result = new JourneyRaptorAdapter().plan(
			request, snapshot(runtime), EFFECTIVE, null, measurement());

		assertThat(result.candidates()).isEmpty();
	}

	@Test
	void cancelsChainedTwoLegRaptorCandidateWhenCancellationSignalFires() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, waypointTimetable());
		var cancelledRequest = new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", "station-transfer", new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 1, () -> true);

		assertThatThrownBy(() -> new JourneyRaptorAdapter().plan(
			cancelledRequest, snapshot(runtime), EFFECTIVE, null, measurement()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Journey planning was cancelled");
	}

	@Test
	void returnsEmptyCandidatesWhenWaypointIsUnreachable() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		var validUnreachableRequest = new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", "station-nonexistent", new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 1, () -> false);

		var result = new JourneyRaptorAdapter().plan(
			validUnreachableRequest, snapshot(runtime), EFFECTIVE, null, measurement());

		assertThat(result.candidates()).isEmpty();
	}

	@Test
	void rejectsWaypointJunctionWhenStepFreeRequiredAndTransferHasStairs() {
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
			new TransitStopTime("trip-second", 1, "station-transfer", "line-b", 34_200, 34_200, 0, 0),
			new TransitStopTime("trip-second", 2, "station-b", "line-b", 34_800, 34_800, 0, 0));
		var edges = List.of(
			new PathwayEdge(
				"entry", "entrance", "platform-a", 120, 100, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"),
			new PathwayEdge(
				"transfer", "platform-transfer-a", "platform-transfer-b", 120, 100, false, true, 100, // includes stairs!
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STAIR_ONLY"),
			new PathwayEdge(
				"exit", "platform-b", "outside", 60, 100, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"),
			new PathwayEdge(
				"entry-transfer", "entrance-transfer", "platform-transfer-b", 120, 100, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"),
			new PathwayEdge(
				"exit-transfer", "platform-transfer-a", "outside-transfer", 60, 100, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"));
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
			List.of(new TransferRule(
				"transfer-rule", "station-transfer", "line-a", "station-transfer", "line-b", "IN_STATION",
				120, "transfer", "transfer", "VERIFIED")),
			List.of(
				new RouteEdgeEvidence("entry-evidence", "station-a", "line-a", "entry", "ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null),
				new RouteEdgeEvidence("transfer-evidence", "station-transfer", "line-b", "transfer", "TRANSFER", "OFFICIAL_SOURCE", "VERIFIED", true, null),
				new RouteEdgeEvidence("exit-evidence", "station-b", "line-b", "exit", "EXIT", "OFFICIAL_SOURCE", "VERIFIED", true, null),
				new RouteEdgeEvidence("entry-transfer-evidence", "station-transfer", "line-b", "entry-transfer", "ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null),
				new RouteEdgeEvidence("exit-transfer-evidence", "station-transfer", "line-a", "exit-transfer", "EXIT", "OFFICIAL_SOURCE", "VERIFIED", true, null)));
		var timetableWithStairs = new RouteTimetable(
			List.of(calendar), List.of(), routes, trips, stopTimes, List.of(), List.of(), null, access);
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetableWithStairs);

		var stepFreeRequest = new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", "station-transfer", new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STEP_FREE, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE, 2, 1, () -> false);

		var result = new JourneyRaptorAdapter().plan(
			stepFreeRequest, snapshot(runtime), EFFECTIVE, null, measurement());

		assertThat(result.candidates()).isEmpty();
	}

	@Test
	void skipsLegTwoWhenMaxTransfersIsZero() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, waypointTimetable());
		var request = new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", "station-transfer", new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false);

		var result = new JourneyRaptorAdapter().plan(
			request, snapshot(runtime), EFFECTIVE, null, measurement());

		assertThat(result.candidates()).isEmpty();
	}


	@Test
	void plansChainedWaypointCandidateForDifferentMobilityProfiles() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, waypointTimetable());

		var slowRequest = new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", "station-transfer", new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.SLOW, JourneyRequest.ConstraintMode.NONE, 2, 1, () -> false);
		var slowResult = new JourneyRaptorAdapter().plan(
			slowRequest, snapshot(runtime), EFFECTIVE, null, measurement());
		assertThat(slowResult.candidates()).isNotEmpty();

		var stepFreeRequest = new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", "station-transfer", new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STEP_FREE, JourneyRequest.ConstraintMode.NONE, 2, 1, () -> false);
		var stepFreeResult = new JourneyRaptorAdapter().plan(
			stepFreeRequest, snapshot(runtime), EFFECTIVE, null, measurement());
		assertThat(stepFreeResult.candidates()).isNotEmpty();

		var noStairsRequest = new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", "station-transfer", new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.NO_STAIRS, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE, 2, 1, () -> false);
		var noStairsResult = new JourneyRaptorAdapter().plan(
			noStairsRequest, snapshot(runtime), EFFECTIVE, null, measurement());
		assertThat(noStairsResult.candidates()).isNotEmpty();
	}

	@Test
	void plansChainedWaypointCandidateWithRealtimeRequiredPolicy() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, waypointTimetable());
		var departure1 = new JourneyTimetableRealtimeResolver.Departure(
			"station-a", "line-a", "trip-first", "2001", "LOCAL", LocalDate.of(2026, 7, 1), 1,
			LocalDate.of(2026, 7, 1).atStartOfDay(ServiceDayResolver.ZONE).plusSeconds(32_400).toInstant(),
			LocalDate.of(2026, 7, 1).atStartOfDay(ServiceDayResolver.ZONE).plusSeconds(32_400).toInstant());
		var departure2 = new JourneyTimetableRealtimeResolver.Departure(
			"station-transfer", "line-b", "trip-second", "2002", "LOCAL", LocalDate.of(2026, 7, 1), 1,
			LocalDate.of(2026, 7, 1).atStartOfDay(ServiceDayResolver.ZONE).plusSeconds(34_200).toInstant(),
			LocalDate.of(2026, 7, 1).atStartOfDay(ServiceDayResolver.ZONE).plusSeconds(34_200).toInstant());
		var update1 = new JourneyTimetableRealtimeResolver.Update(
			departure1, 60, 60, false, "realtime-1", Instant.parse("2026-07-01T00:00:00Z"));
		var update2 = new JourneyTimetableRealtimeResolver.Update(
			departure2, 30, 30, false, "realtime-1", Instant.parse("2026-07-01T00:00:00Z"));
		var realtimeRuntime = RaptorRealtimeRuntimeView.compile(
			"realtime-1", runtime, updates(List.of(update1, update2)));
		var observation = new JourneyRealtimePort.RealtimeObservation(
			"realtime-1", ROUTE_BUNDLE_SHA, realtimeRuntime, VALID_UNTIL, true);

		var request = new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", "station-transfer", new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			JourneyRequest.TimePolicy.REALTIME_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 1, () -> false);

		var candidate = new JourneyRaptorAdapter().plan(
			request, snapshot(runtime), EFFECTIVE, observation, measurement()).candidates().getFirst();

		assertThat(candidate.timeSource()).isEqualTo(JourneyCandidate.TimeSource.REALTIME);
		assertThat(candidate.realtimeDepartureTime()).isNotNull();
		assertThat(candidate.realtimeArrivalTime()).isNotNull();
	}

	@Test
	void resolvesFootpathTransitionsExplicitly() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, waypointTimetable());
		var timetable = runtime.compiledTimetable();
		assertThat(JourneyRaptorAdapter.resolveFootpathTransition(null, 0, 1, 0, timetable)).isEqualTo(-1);

		var dummy = new RouteTimetableRaptorPlanner.OutOfStationFootpath[] {
			new RouteTimetableRaptorPlanner.OutOfStationFootpath(999, 999, 998, 998, new int[]{0}),
			new RouteTimetableRaptorPlanner.OutOfStationFootpath(0, 0, 1, 998, new int[]{0}),
			new RouteTimetableRaptorPlanner.OutOfStationFootpath(0, 0, 1, 1, new int[0])
		};
		assertThat(JourneyRaptorAdapter.resolveFootpathTransition(dummy, 0, 1, 0, timetable)).isEqualTo(-1);
	}

	@Test
	void preservesPlannedAndCompleteRealtimePairsFromTheSameRuntime() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		var realtimeRuntime = RaptorRealtimeRuntimeView.compile(
			"realtime-1",
			runtime,
			updates("trip", 60, 60, false, "realtime-1")
		);
		var observation = new JourneyRealtimePort.RealtimeObservation(
			"realtime-1", ROUTE_BUNDLE_SHA, realtimeRuntime, VALID_UNTIL, true);

		var candidate = new JourneyRaptorAdapter().plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.REALTIME_REQUIRED),
			snapshot(runtime), EFFECTIVE, observation, measurement()).candidates().getFirst();

		assertThat(candidate.plannedDepartureTime()).isEqualTo(EFFECTIVE);
		assertThat(candidate.plannedArrivalTime()).isEqualTo(Instant.parse("2026-07-01T00:10:00Z"));
		assertThat(candidate.realtimeDepartureTime()).isEqualTo(EFFECTIVE);
		assertThat(candidate.realtimeArrivalTime()).isEqualTo(Instant.parse("2026-07-01T00:11:00Z"));
		assertThat(candidate.timeSource()).isEqualTo(JourneyCandidate.TimeSource.REALTIME);
		assertThat(candidate.legs()).filteredOn(JourneyCandidate.Ride.class::isInstance)
			.singleElement().isEqualTo(TestRides.candidateRide(
				"line", "trip", "station-b", "station-a", "station-b",
				Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-01T00:10:00Z"),
				Instant.parse("2026-07-01T00:01:00Z"), Instant.parse("2026-07-01T00:11:00Z")));
	}

	@Test
	void rejectsSparseRealtimeThatDoesNotCoverTheSelectedRide() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		var realtimeRuntime = RaptorRealtimeRuntimeView.compile(
			"realtime-1",
			runtime,
			updates("trip-late", 60, 60, false, "realtime-1")
		);
		var observation = new JourneyRealtimePort.RealtimeObservation(
			"realtime-1", ROUTE_BUNDLE_SHA, realtimeRuntime, VALID_UNTIL, true);

		assertThatThrownBy(() -> new JourneyRaptorAdapter().plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.REALTIME_REQUIRED),
			snapshot(runtime), EFFECTIVE, observation, measurement()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("incomplete");
	}

	@Test
	void rejectsPointRuntimeReuseForAnotherEffectiveServiceDate() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		var realtimeRuntime = RaptorRealtimeRuntimeView.compile(
			"realtime-1", runtime, updates("trip", 60, 60, false, "realtime-1"));
		var observation = new JourneyRealtimePort.RealtimeObservation(
			"realtime-1", ROUTE_BUNDLE_SHA, realtimeRuntime, VALID_UNTIL, true);

		assertThatThrownBy(() -> new JourneyRaptorAdapter().plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.REALTIME_REQUIRED),
			snapshot(runtime), EFFECTIVE.plus(Duration.ofDays(1)), observation, measurement()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("service date");
	}

	@Test
	void keepsAdjacentServiceDateOccurrencesIsolatedAndRejectsMissingDate() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		var realtime = RaptorRealtimeRuntimeView.compile("realtime-1", runtime,
			updates(List.of(
				updateForDate(LocalDate.of(2026, 7, 1), 60, false),
				updateForDate(LocalDate.of(2026, 7, 2), 0, true))));
		var scheduled = runtime.compiledTimetable().activeServiceDay(LocalDate.of(2026, 7, 1))
			.tripsByPattern(0).getFirst();

		assertThat(realtime.realtimeOverlay(LocalDate.of(2026, 7, 1)).departureSeconds(scheduled, 0))
			.isEqualTo(32_460);
		assertThat(realtime.realtimeOverlay(LocalDate.of(2026, 7, 2)).cancelled(scheduled)).isTrue();
		assertThatThrownBy(() -> realtime.realtimeOverlay(LocalDate.of(2026, 7, 3)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("service date");
	}

	@Test
	void forwardProfileSelectsOnlyItsExactDateOverlayAndRejectsAMissingActiveDate() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		var realtime = RaptorRealtimeRuntimeView.compile("realtime-1", runtime, updates(List.of(
			updateForDate(LocalDate.of(2026, 7, 1), "trip", 60, false),
			updateForDate(LocalDate.of(2026, 7, 1), "trip-late", 0, true),
			updateForDate(LocalDate.of(2026, 7, 2), "trip", 0, true),
			updateForDate(LocalDate.of(2026, 7, 2), "trip-late", 0, true))));
		var query = new JourneyRaptorQuery(REQUEST_ID, "station-a", "station-b",
			new JourneyRaptorQuery.DepartBetween(serviceInstant(LocalDate.of(2026, 7, 1), 32_000),
				serviceInstant(LocalDate.of(2026, 7, 2), 32_500)),
			JourneyRequest.TimePolicy.REALTIME_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false);
		var limits = new JourneyProfileResourcePolicy.ProfilePlanningLimits(100_000, 32, 32, 32);

		var points = new RouteTimetableRaptorPlanner().departureProfile(query, runtime.compiledTimetable(), realtime,
			limits, new JourneyProfilePruningObservationAccumulator(REQUEST_ID,
				com.easysubway.journey.application.JourneyRaptorPruningInventoryV1.FORWARD_RANGE_RAPTOR));

		assertThat(points).extracting(RouteTimetableRaptorPlanner.JourneyDepartureProfilePoint::serviceDate)
			.containsOnly(LocalDate.of(2026, 7, 1));
		assertThat(points.getFirst().itineraries().getFirst().legs())
			.filteredOn(RouteTimetableRaptorPlanner.JourneyRideProjection.class::isInstance)
			.singleElement().isInstanceOfSatisfying(RouteTimetableRaptorPlanner.JourneyRideProjection.class, ride ->
				assertThat(ride.realtimeDepartureTime()).isEqualTo(serviceInstant(LocalDate.of(2026, 7, 1), 32_460)));
		var missingDateRuntime = RaptorRealtimeRuntimeView.compile("realtime-1", runtime,
			updates(List.of(updateForDate(LocalDate.of(2026, 7, 1), "trip", 60, false))));
		assertThatThrownBy(() -> new RouteTimetableRaptorPlanner().departureProfile(query,
			runtime.compiledTimetable(), missingDateRuntime, limits,
			new JourneyProfilePruningObservationAccumulator(REQUEST_ID,
				com.easysubway.journey.application.JourneyRaptorPruningInventoryV1.FORWARD_RANGE_RAPTOR)))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("service date");
	}

	@Test
	void keepsStandardAndNoStairsTransferSelectionDistinct() {
		// 일반: 가장 짧은 계단 환승 30m(24초). 계단 없이: 무단차 80m(64초).
		var runtime = RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA, GENERATION, transferChoiceTimetable(
				transferEdge("stairs-transfer", 60, 30, true, "OFFICIAL_SOURCE"),
				transferEdge("step-free-transfer", 120, 80, false, "OFFICIAL_SOURCE")));
		var adapter = new JourneyRaptorAdapter();

		var standard = adapter.plan(
			transferRequest(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED), snapshot(runtime), EFFECTIVE, null, measurement());
		var noStairs = adapter.plan(
			transferRequest(JourneyRequest.MobilityProfile.NO_STAIRS, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED), snapshot(runtime), EFFECTIVE, null, measurement());

		assertThat(standard.candidates()).singleElement().satisfies(candidate ->
			assertThat(transferLeg(candidate)).isEqualTo(stationTransfer(24)));
		assertThat(noStairs.candidates()).singleElement().satisfies(candidate ->
			assertThat(transferLeg(candidate)).isEqualTo(stationTransfer(64)));
	}

	@Test
	void stopsNativeJourneyScanWhenCancellationArrivesDuringTheMarkedRound() {
		var cancellationChecks = new AtomicInteger();
		var query = new JourneyRaptorQuery(
			REQUEST_ID, "station-a", "station-b", new JourneyRaptorQuery.DepartAt(EFFECTIVE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 0, 1,
			() -> cancellationChecks.incrementAndGet() > 1);
		var planner = new RouteTimetableRaptorPlanner();

		assertThatThrownBy(() -> planner.journeyItineraries(
			query, planner.compile(timetable(true)), RouteTimetableRaptorPlanner.RealtimeOverlay.empty(),
			measurement(), REQUEST_ID, ROUTE_BUNDLE_SHA, GENERATION))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("scan cancelled");
		assertThat(cancellationChecks.get()).isGreaterThan(1);
	}

	@Test
	void rejectsNonPointJourneyTemporalQueriesWithoutAProfileFallback() {
		var planner = new RouteTimetableRaptorPlanner();
		var query = new JourneyRaptorQuery(
			REQUEST_ID, "station-a", "station-b",
			new JourneyRaptorQuery.DepartBetween(EFFECTIVE, EFFECTIVE.plusSeconds(60)),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false);

		assertThatThrownBy(() -> planner.realtimeQueries(query, planner.compile(timetable(true))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("does not support temporal profile");
	}

	@Test
	void rejectsUnknownAndGenerationMixedRuntimeHandlesBeforePlanning() {
		JourneyRaptorRuntimeView unknown = new JourneyRaptorRuntimeView() {
			@Override
			public String routeBundleSha256() {
				return ROUTE_BUNDLE_SHA;
			}

			@Override
			public long generation() {
				return GENERATION;
			}
		};
		var unknownSnapshot = new ActiveJourneySnapshotPort.ActiveJourneySnapshot(
			"snapshot-1", "bundle-1", ROUTE_BUNDLE_SHA, "timetable-1", "accessibility-1",
			GENERATION, unknown, VALID_UNTIL, true,
			ActiveJourneySnapshotPort.ActiveServingEvidence.unobservable(),
			ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.observed(0, 0));

		assertThatThrownBy(() -> new JourneyRaptorAdapter().plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED), unknownSnapshot, EFFECTIVE, null, measurement()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("runtime view");

		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		assertThatThrownBy(() -> new ActiveJourneySnapshotPort.ActiveJourneySnapshot(
			"snapshot-1", "bundle-1", ROUTE_BUNDLE_SHA, "timetable-1", "accessibility-1",
			GENERATION + 1, runtime, VALID_UNTIL, true,
			ActiveJourneySnapshotPort.ActiveServingEvidence.unobservable(),
			ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.observed(0, 0)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("generation");
	}

	@Test
	void validatesRuntimeConstructionAndRealtimeObservationIdentity() {
		assertThatThrownBy(() -> RaptorRouteBundleRuntimeView.compile("BAD", GENERATION, timetable(true)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, 0, timetable(true)))
			.isInstanceOf(IllegalArgumentException.class);

		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		assertThatThrownBy(() -> RaptorRealtimeRuntimeView.compile(
			"realtime-1", runtime, JourneyTimetableRealtimeResolver.Updates.unavailable("NO_DATA")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("identity");
		assertThatThrownBy(() -> RaptorRealtimeRuntimeView.compile(
			"realtime-1", runtime, updates("trip", 0, 0, false, "different")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("identity");
		assertThatThrownBy(() -> RaptorRealtimeRuntimeView.compile(
			"realtime-1", runtime, updates("unknown-trip", 0, 0, false, "realtime-1")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("valid updates");
		assertThatThrownBy(() -> RaptorRealtimeRuntimeView.compile(
			"realtime-1", runtime, updates(new JourneyTimetableRealtimeResolver.Departure(
				"station-a", "line", "trip", "1001", "LOCAL", LocalDate.of(2026, 7, 2), 1,
				Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z")),
				0, 0, false, "realtime-1")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("valid updates");
	}

	@Test
	void rejectsUnexpectedRealtimeModeCancellationAndDifferentExactRouteHandle() {
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		var otherRuntime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(true));
		var otherRealtime = RaptorRealtimeRuntimeView.compile(
			"realtime-1", otherRuntime, updates("trip", 0, 0, false, "realtime-1"));
		var observation = new JourneyRealtimePort.RealtimeObservation(
			"realtime-1", ROUTE_BUNDLE_SHA, otherRealtime, VALID_UNTIL, true);

		assertThatThrownBy(() -> new JourneyRaptorAdapter().plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime), EFFECTIVE, observation, measurement()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("must not receive realtime");
		assertThatThrownBy(() -> new JourneyRaptorAdapter().plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.REALTIME_REQUIRED),
			snapshot(runtime), EFFECTIVE, observation, measurement()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("captured Journey generation");

		var cancelled = new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 0, 1, () -> true);
		assertThatThrownBy(() -> new JourneyRaptorAdapter().plan(
			cancelled, snapshot(runtime), EFFECTIVE, null, measurement()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("cancelled");
	}

	@Test
	void allowsVerifiedStairsTransferOnlyForNonStrictRequests() {
		var runtime = RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA, GENERATION, transferChoiceTimetable(transferEdge("stairs-transfer", 60, 40, true, "OFFICIAL_SOURCE")));
		var adapter = new JourneyRaptorAdapter();

		var standard = adapter.plan(
			transferRequest(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime), EFFECTIVE, null, measurement());
		assertThat(standard.candidates()).singleElement()
			.extracting(candidate -> candidate.accessibility().stairFree()).isEqualTo(false);

		var strict = adapter.plan(
			transferRequest(JourneyRequest.MobilityProfile.STEP_FREE, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime), EFFECTIVE, null, measurement());
		assertThat(strict.candidates()).isEmpty();
	}

	@Test
	void returnsADirectPlatformJourneyWhenTheBundleHasNoAccessData() {
		// #454: 직행은 이동 구간이 없어 접근 데이터가 없어도 경로다(진입·하차 기본값을 만들지 않는다).
		var runtime = RaptorRouteBundleRuntimeView.compile(ROUTE_BUNDLE_SHA, GENERATION, timetable(false));
		assertThat(new JourneyRaptorAdapter().plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(runtime), EFFECTIVE, null, measurement()).candidates())
			.singleElement().satisfies(candidate -> {
				assertThat(candidate.legs()).singleElement().isInstanceOf(JourneyCandidate.Ride.class);
				assertThat(candidate.walkingDistanceMeters()).isZero();
			});
	}

	@Test
	void ignoresVerifiedZeroDistanceEntryAndExitButRejectsRuleOnlyTransfer() {
		var zeroDistanceRuntime = RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA, GENERATION, timetable(verifiedAccess(false, 0, 40)));
		assertThat(new JourneyRaptorAdapter().plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(zeroDistanceRuntime), EFFECTIVE, null, measurement()).candidates())
			.singleElement().satisfies(candidate -> assertThat(candidate.legs()).extracting(JourneyCandidate.Leg::type)
				.containsExactly(JourneyCandidate.LegType.RIDE));

		var ruleOnlyRuntime = RaptorRouteBundleRuntimeView.compile(
			ROUTE_BUNDLE_SHA, GENERATION, transferTimetable(false));
		assertThat(new JourneyRaptorAdapter().plan(
			request(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED),
			snapshot(ruleOnlyRuntime), EFFECTIVE, null, measurement()).candidates())
			.isEmpty();
	}

	@Test
	void acceptsTimeOnlyVerifiedTransferButRejectsUntrustedOrStaleTransfer() {
		// #454·data#876: 거리 없이 공식 실측 시간(120초)만 있는 검증 환승은 쓴다. 4,500 m/h는 1.2 m/s보다 빨라 실측 시간 그대로다.
		assertThat(new JourneyRaptorAdapter().plan(
			transferRequest(JourneyRequest.WalkingPace.STANDARD),
			snapshot(RaptorRouteBundleRuntimeView.compile(
				ROUTE_BUNDLE_SHA, GENERATION, transferTimetable(true, 0, "OFFICIAL_SOURCE", "VERIFIED", "VERIFIED"))),
			EFFECTIVE,
			null,
			measurement()
		).candidates()).singleElement().satisfies(candidate -> {
			assertThat(transferLeg(candidate)).isEqualTo(stationTransfer(120));
			assertThat(candidate.walkingDistanceMeters()).isZero();
		});
		assertThat(new JourneyRaptorAdapter().plan(
			transferRequest(JourneyRequest.WalkingPace.STANDARD),
			snapshot(RaptorRouteBundleRuntimeView.compile(
				ROUTE_BUNDLE_SHA, GENERATION, transferTimetable(true, 100, "UNTRUSTED", "VERIFIED", "VERIFIED"))),
			EFFECTIVE,
			null,
			measurement()
		).candidates()).isEmpty();
		assertThat(new JourneyRaptorAdapter().plan(
			transferRequest(JourneyRequest.WalkingPace.STANDARD),
			snapshot(RaptorRouteBundleRuntimeView.compile(
				ROUTE_BUNDLE_SHA, GENERATION, transferTimetable(true, 100, "OFFICIAL_SOURCE", "VERIFIED", "STALE"))),
			EFFECTIVE,
			null,
			measurement()
		).candidates()).isEmpty();
	}

	private static JourneyRequest request(
		JourneyRequest.MobilityProfile profile,
		JourneyRequest.ConstraintMode constraint,
		JourneyRequest.TimePolicy timePolicy
	) {
		return request(profile, constraint, timePolicy, JourneyRequest.WalkingPace.STANDARD);
	}

	private static JourneyRequest request(
		JourneyRequest.MobilityProfile profile,
		JourneyRequest.ConstraintMode constraint,
		JourneyRequest.TimePolicy timePolicy,
		JourneyRequest.WalkingPace walkingPace
	) {
		return new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			timePolicy, walkingPace, profile, constraint, 0, 1, () -> false);
	}

	private static JourneyRequest transferRequest(JourneyRequest.WalkingPace walkingPace) {
		return new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, walkingPace,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 1, 1, () -> false);
	}

	private static JourneyRequestMeasurement measurement() {
		return new JourneyRequestMeasurement(REQUEST_ID);
	}

	private static ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot(
		RaptorRouteBundleRuntimeView runtime
	) {
		return snapshot(runtime, ActiveJourneySnapshotPort.SnapshotMeasurementReceipt.unobservable());
	}

	private static JourneyTimetableRealtimeResolver.Updates updates(
		String tripId,
		int arrivalDeltaSeconds,
		int departureDeltaSeconds,
		boolean cancelled,
		String identity
	) {
		String trainNo = "trip-late".equals(tripId) ? "1002" : "1001";
		int seconds = "trip-late".equals(tripId) ? 36_000 : 32_400;
		return updates(new JourneyTimetableRealtimeResolver.Departure(
			"station-a", "line", tripId, trainNo, "LOCAL", LocalDate.of(2026, 7, 1), 1,
			LocalDate.of(2026, 7, 1).atStartOfDay(ServiceDayResolver.ZONE).plusSeconds(seconds).toInstant(),
			LocalDate.of(2026, 7, 1).atStartOfDay(ServiceDayResolver.ZONE).plusSeconds(seconds).toInstant()),
			arrivalDeltaSeconds, departureDeltaSeconds, cancelled, identity);
	}

	private static JourneyTimetableRealtimeResolver.Updates updates(
		List<JourneyTimetableRealtimeResolver.Update> updates
	) {
		return new JourneyTimetableRealtimeResolver.Updates("overlay-v1", true, updates, null);
	}

	private static JourneyTimetableRealtimeResolver.Update updateForDate(
		LocalDate serviceDate,
		String tripId,
		int deltaSeconds,
		boolean cancelled
	) {
		String trainNo = "trip-late".equals(tripId) ? "1002" : "1001";
		int seconds = "trip-late".equals(tripId) ? 36_000 : 32_400;
		Instant scheduled = serviceInstant(serviceDate, seconds);
		return new JourneyTimetableRealtimeResolver.Update(
			new JourneyTimetableRealtimeResolver.Departure(
				"station-a", "line", tripId, trainNo, "LOCAL", serviceDate, 1, scheduled, scheduled),
			deltaSeconds, deltaSeconds, cancelled, "realtime-1", Instant.parse("2026-06-30T23:49:30Z"));
	}

	private static JourneyTimetableRealtimeResolver.Update updateForDate(
		LocalDate serviceDate, int deltaSeconds, boolean cancelled
	) {
		return updateForDate(serviceDate, "trip", deltaSeconds, cancelled);
	}

	private static Instant serviceInstant(LocalDate serviceDate, int seconds) {
		return serviceDate.atStartOfDay(ServiceDayResolver.ZONE).plusSeconds(seconds).toInstant();
	}

	private static JourneyTimetableRealtimeResolver.Updates updates(
		JourneyTimetableRealtimeResolver.Departure departure,
		int arrivalDeltaSeconds,
		int departureDeltaSeconds,
		boolean cancelled,
		String identity
	) {
		return new JourneyTimetableRealtimeResolver.Updates("overlay-v1", true, List.of(
			new JourneyTimetableRealtimeResolver.Update(
				departure, arrivalDeltaSeconds, departureDeltaSeconds, cancelled, identity,
				Instant.parse("2026-06-30T23:49:30Z"))), null);
	}

	private static JourneyExecutionResult.ActiveReadinessIdentity activeReadinessIdentity() {
		return new JourneyExecutionResult.ActiveReadinessIdentity(
			1, "journey-v3-active-readiness", "backend-a", "d".repeat(64),
			"sha256:" + "f".repeat(64), "1".repeat(64), "2".repeat(64), ROUTE_BUNDLE_SHA,
			"bundle-1", 1, GENERATION, "Asia/Seoul", "03:00", 1, true, false,
			VALID_UNTIL, EFFECTIVE.minusSeconds(60), "3".repeat(64));
	}

	private static ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot(
		RaptorRouteBundleRuntimeView runtime,
		ActiveJourneySnapshotPort.SnapshotMeasurementReceipt measurementReceipt
	) {
		var servingEvidence = measurementReceipt.status()
			== ActiveJourneySnapshotPort.SnapshotMeasurementReceipt.Status.OBSERVED
			? ActiveJourneySnapshotPort.ActiveServingEvidence.observed("b".repeat(64), "c".repeat(64))
			: ActiveJourneySnapshotPort.ActiveServingEvidence.unobservable();
		return new ActiveJourneySnapshotPort.ActiveJourneySnapshot(
			"snapshot-1", "bundle-1", ROUTE_BUNDLE_SHA, "timetable-1", "accessibility-1",
			GENERATION, runtime, VALID_UNTIL, true,
			servingEvidence,
			ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.observed(0, 0),
			measurementReceipt);
	}

	private static RouteTimetable timetable(boolean verifiedAccess) {
		return timetable(verifiedAccess, false);
	}

	private static RouteTimetable timetable(boolean verifiedAccess, boolean includesStairs) {
		return timetable(verifiedAccess ? verifiedAccess(includesStairs) : LoadRouteTimetablePort.RouteAccessData.empty());
	}

	private static RouteTimetable timetable(LoadRouteTimetablePort.RouteAccessData access) {
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
		return new RouteTimetable(
			List.of(calendar), List.of(), List.of(route), List.of(trip, lateTrip), stopTimes, List.of(), List.of(), null, access);
	}

	private static RouteTimetable alternateTransferTimetable() {
		return transferChoiceTimetable(
			transferEdge("transfer", 120, 100, false, "OFFICIAL_SOURCE"),
			transferEdge("transfer-alt", 300, 200, false, "OFFICIAL_SOURCE"));
	}

	private static JourneyRequest transferRequest(
		JourneyRequest.MobilityProfile profile,
		JourneyRequest.ConstraintMode constraint,
		JourneyRequest.TimePolicy timePolicy
	) {
		return new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", new JourneyRequest.Departure.Scheduled(EFFECTIVE),
			timePolicy, JourneyRequest.WalkingPace.STANDARD, profile, constraint, 1, 1, () -> false);
	}

	private static JourneyCandidate.Transfer stationTransfer(long durationSeconds) {
		return new JourneyCandidate.Transfer("station-transfer", "station-transfer", durationSeconds);
	}

	private static JourneyCandidate.Leg transferLeg(JourneyCandidate candidate) {
		return candidate.legs().stream().filter(JourneyCandidate.Transfer.class::isInstance).findFirst().orElseThrow();
	}

	private static PathwayEdge transferEdge(
		String id, int durationSeconds, int distanceMeters, boolean includesStairs, String provenanceKind
	) {
		return new PathwayEdge(id, "platform-transfer-a", "platform-transfer-b", durationSeconds, distanceMeters,
			false, includesStairs, 100, "AVAILABLE", provenanceKind, "VERIFIED").withStairAccessState((includesStairs) ? "STAIR_ONLY" : "STEP_FREE");
	}

	/** station-transfer의 line-a→line-b 환승 후보만 바꾸는 픽스처(#454: 진입·하차 간선 없음). */
	private static RouteTimetable transferChoiceTimetable(PathwayEdge... transfers) {
		var base = transferTimetable();
		var rules = new java.util.ArrayList<TransferRule>();
		var evidence = new java.util.ArrayList<RouteEdgeEvidence>();
		for (PathwayEdge transfer : transfers) {
			rules.add(new TransferRule(transfer.id() + "-rule", "station-transfer", "line-a", "station-transfer", "line-b",
				"IN_STATION", transfer.durationSeconds(), transfer.id(), transfer.includesStairs() ? null : transfer.id(),
				"VERIFIED"));
			evidence.add(new RouteEdgeEvidence(transfer.id() + "-evidence", "station-transfer", "line-b", transfer.id(),
				"TRANSFER", transfer.provenanceKind(), "VERIFIED", true, null));
		}
		var access = new LoadRouteTimetablePort.RouteAccessData(
			List.of(
				new PathwayNode("platform-transfer-a", "station-transfer", "line-a", "PLATFORM"),
				new PathwayNode("platform-transfer-b", "station-transfer", "line-b", "PLATFORM")),
			List.of(transfers), rules, evidence);
		return new RouteTimetable(base.serviceCalendars(), base.serviceCalendarDates(), base.transitRoutes(),
			base.transitTrips(), base.transitStopTimes(), base.transitFrequencies(), List.of(), null, access);
	}

	/** transferTimetable의 모든 열차를 같은 초만큼 지연한다(두 승차 모두 실시간 쌍을 갖게 한다). */
	private static JourneyTimetableRealtimeResolver.Updates transferTimetableUpdates(int delaySeconds) {
		LocalDate serviceDate = LocalDate.of(2026, 7, 1);
		var departures = List.of(
			new JourneyTimetableRealtimeResolver.Departure("station-a", "line-a", "trip-first", "2001", "LOCAL",
				serviceDate, 1, serviceInstant(serviceDate, 32_400), serviceInstant(serviceDate, 32_400)),
			new JourneyTimetableRealtimeResolver.Departure("station-transfer", "line-b", "trip-second-fast", "2002-fast",
				"LOCAL", serviceDate, 1, serviceInstant(serviceDate, 33_120), serviceInstant(serviceDate, 33_120)),
			new JourneyTimetableRealtimeResolver.Departure("station-transfer", "line-b", "trip-second", "2002", "LOCAL",
				serviceDate, 1, serviceInstant(serviceDate, 34_200), serviceInstant(serviceDate, 34_200)));
		return updates(departures.stream().map(departure -> new JourneyTimetableRealtimeResolver.Update(
			departure, delaySeconds, delaySeconds, false, "realtime-1", Instant.parse("2026-06-30T23:49:30Z"))).toList());
	}

	private static RouteTimetable waypointTimetable() {
		return waypointTimetable("IN_STATION", 120, 34_200, true);
	}

	private static RouteTimetable waypointTimetable(String transferType, int transferDuration, int tripSecondDeparture, boolean hasTransferRule) {
		return waypointTimetable(transferType, transferDuration, tripSecondDeparture, hasTransferRule, "STEP_FREE");
	}

	private static RouteTimetable waypointTimetable(
		String transferType, int transferDuration, int tripSecondDeparture, boolean hasTransferRule, String transferStairState
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
				"trip-second-fast", "route-second", "daily", "station-b", "down", "SUBWAY", "LOCAL", "2002-fast", 0),
			new TransitTrip(
				"trip-second", "route-second", "daily", "station-b", "down", "SUBWAY", "LOCAL", "2002", 0));
		var stopTimes = List.of(
			new TransitStopTime("trip-first", 1, "station-a", "line-a", 32_400, 32_400, 0, 0),
			new TransitStopTime("trip-first", 2, "station-transfer", "line-a", 33_000, 33_000, 0, 0),
			new TransitStopTime("trip-second-fast", 1, "station-transfer", "line-b", 33_120, 33_120, 0, 0),
			new TransitStopTime("trip-second-fast", 2, "station-b", "line-b", 33_720, 33_720, 0, 0),
			new TransitStopTime("trip-second", 1, "station-transfer", "line-b", tripSecondDeparture, tripSecondDeparture, 0, 0),
			new TransitStopTime("trip-second", 2, "station-b", "line-b", tripSecondDeparture + 600, tripSecondDeparture + 600, 0, 0));
		var edges = List.of(
			new PathwayEdge(
				"entry", "entrance", "platform-a", 120, 100, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"),
			new PathwayEdge(
				"transfer", "platform-transfer-a", "platform-transfer-b", transferDuration, 100, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState(transferStairState),
			new PathwayEdge(
				"exit", "platform-b", "outside", 60, 100, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"),
			new PathwayEdge(
				"entry-transfer", "entrance-transfer", "platform-transfer-b", 120, 100, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"),
			new PathwayEdge(
				"exit-transfer", "platform-transfer-a", "outside-transfer", 60, 100, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"));
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
				new RouteEdgeEvidence(
					"entry-evidence", "station-a", "line-a", "entry", "ENTRY",
					"OFFICIAL_SOURCE", "VERIFIED", true, null),
				new RouteEdgeEvidence(
					"transfer-evidence", "station-transfer", "line-b", "transfer", "TRANSFER",
					"OFFICIAL_SOURCE", "VERIFIED", true, null),
				new RouteEdgeEvidence(
					"exit-evidence", "station-b", "line-b", "exit", "EXIT",
					"OFFICIAL_SOURCE", "VERIFIED", true, null),
				new RouteEdgeEvidence(
					"entry-transfer-evidence", "station-transfer", "line-b", "entry-transfer", "ENTRY",
					"OFFICIAL_SOURCE", "VERIFIED", true, null),
				new RouteEdgeEvidence(
					"exit-transfer-evidence", "station-transfer", "line-a", "exit-transfer", "EXIT",
					"OFFICIAL_SOURCE", "VERIFIED", true, null)));
		return new RouteTimetable(
			List.of(calendar), List.of(), routes, trips, stopTimes, List.of(), List.of(), null, access);
	}

	private static RouteTimetable sameLineWaypointTimetable() {
		var calendar = new ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Asia/Seoul");
		var routes = List.of(
			new TransitRoute("route-line", "line-a", "A", "LineA", "station-b", "Asia/Seoul"));
		var trips = List.of(
			new TransitTrip(
				"trip-first", "route-line", "daily", "station-b", "down", "SUBWAY", "LOCAL", "2001", 0),
			new TransitTrip(
				"trip-second", "route-line", "daily", "station-b", "down", "SUBWAY", "LOCAL", "2002", 0));
		var stopTimes = List.of(
			new TransitStopTime("trip-first", 1, "station-a", "line-a", 32_400, 32_400, 0, 0),
			new TransitStopTime("trip-first", 2, "station-via", "line-a", 33_000, 33_000, 0, 0),
			new TransitStopTime("trip-second", 1, "station-via", "line-a", 33_600, 33_600, 0, 0),
			new TransitStopTime("trip-second", 2, "station-b", "line-a", 34_200, 34_200, 0, 0));
		var edges = List.of(
			new PathwayEdge(
				"entry", "entrance", "platform-a", 120, 100, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"),
			new PathwayEdge(
				"exit", "platform-b", "outside", 60, 100, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"),
			new PathwayEdge(
				"entry-via", "entrance-via", "platform-via", 120, 100, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"),
			new PathwayEdge(
				"exit-via", "platform-via", "outside-via", 60, 100, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"));
		var access = new LoadRouteTimetablePort.RouteAccessData(
			List.of(
				new PathwayNode("entrance", "station-a", null, "ENTRANCE"),
				new PathwayNode("platform-a", "station-a", "line-a", "PLATFORM"),
				new PathwayNode("entrance-via", "station-via", null, "ENTRANCE"),
				new PathwayNode("platform-via", "station-via", "line-a", "PLATFORM"),
				new PathwayNode("outside-via", "station-via", null, "EXIT"),
				new PathwayNode("platform-b", "station-b", "line-a", "PLATFORM"),
				new PathwayNode("outside", "station-b", null, "EXIT")),
			edges,
			List.of(),
			List.of(
				new RouteEdgeEvidence("entry-evidence", "station-a", "line-a", "entry", "ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null),
				new RouteEdgeEvidence("exit-evidence", "station-b", "line-a", "exit", "EXIT", "OFFICIAL_SOURCE", "VERIFIED", true, null),
				new RouteEdgeEvidence("entry-via-evidence", "station-via", "line-a", "entry-via", "ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null),
				new RouteEdgeEvidence("exit-via-evidence", "station-via", "line-a", "exit-via", "EXIT", "OFFICIAL_SOURCE", "VERIFIED", true, null)));
		return new RouteTimetable(
			List.of(calendar), List.of(), routes, trips, stopTimes, List.of(), List.of(), null, access);
	}

	private static RouteTimetable transferTimetable() {
		return transferTimetable(true);
	}

	private static RouteTimetable transferTimetable(boolean verifiedTransfer) {
		return transferTimetable(verifiedTransfer, 100, "OFFICIAL_SOURCE", "VERIFIED", "VERIFIED");
	}

	private static RouteTimetable transferTimetable(
		boolean verifiedTransfer,
		int transferDistanceMeters,
		String transferProvenanceKind,
		String transferPathwayVerificationStatus,
		String transferEvidenceVerificationStatus
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
				"trip-second-fast", "route-second", "daily", "station-b", "down", "SUBWAY", "LOCAL", "2002-fast", 0),
			new TransitTrip(
				"trip-second", "route-second", "daily", "station-b", "down", "SUBWAY", "LOCAL", "2002", 0));
		var stopTimes = List.of(
			new TransitStopTime("trip-first", 1, "station-a", "line-a", 32_400, 32_400, 0, 0),
			new TransitStopTime("trip-first", 2, "station-transfer", "line-a", 33_000, 33_000, 0, 0),
			new TransitStopTime("trip-second-fast", 1, "station-transfer", "line-b", 33_120, 33_120, 0, 0),
			new TransitStopTime("trip-second-fast", 2, "station-b", "line-b", 33_720, 33_720, 0, 0),
			new TransitStopTime("trip-second", 1, "station-transfer", "line-b", 34_200, 34_200, 0, 0),
			new TransitStopTime("trip-second", 2, "station-b", "line-b", 34_800, 34_800, 0, 0));
		var edges = java.util.stream.Stream.of(
			new PathwayEdge(
				"entry", "entrance", "platform-a", 120, 100, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"),
			verifiedTransfer ? new PathwayEdge(
				"transfer", "platform-transfer-a", "platform-transfer-b", 120, transferDistanceMeters, false, false, 100,
				"AVAILABLE", transferProvenanceKind, transferPathwayVerificationStatus).withStairAccessState("STEP_FREE") : null,
			new PathwayEdge(
				"exit", "platform-b", "outside", 60, 100, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"))
			.filter(java.util.Objects::nonNull).toList();
		var access = new LoadRouteTimetablePort.RouteAccessData(
			List.of(
				new PathwayNode("entrance", "station-a", null, "ENTRANCE"),
				new PathwayNode("platform-a", "station-a", "line-a", "PLATFORM"),
				new PathwayNode("platform-transfer-a", "station-transfer", "line-a", "PLATFORM"),
				new PathwayNode("platform-transfer-b", "station-transfer", "line-b", "PLATFORM"),
				new PathwayNode("platform-b", "station-b", "line-b", "PLATFORM"),
				new PathwayNode("outside", "station-b", null, "EXIT")),
			edges,
			List.of(new TransferRule(
				"transfer-rule", "station-transfer", "line-a", "station-transfer", "line-b", "IN_STATION",
				120, "transfer", "transfer", "VERIFIED")),
			java.util.stream.Stream.of(
				new RouteEdgeEvidence(
					"entry-evidence", "station-a", "line-a", "entry", "ENTRY",
					"OFFICIAL_SOURCE", "VERIFIED", true, null),
				verifiedTransfer ? new RouteEdgeEvidence(
					"transfer-evidence", "station-transfer", "line-b", "transfer", "TRANSFER",
					transferProvenanceKind, transferEvidenceVerificationStatus, true, null) : null,
				new RouteEdgeEvidence(
					"exit-evidence", "station-b", "line-b", "exit", "EXIT",
					"OFFICIAL_SOURCE", "VERIFIED", true, null)).filter(java.util.Objects::nonNull).toList());
		return new RouteTimetable(
			List.of(calendar), List.of(), routes, trips, stopTimes, List.of(), List.of(), null, access);
	}

	private static LoadRouteTimetablePort.RouteAccessData verifiedAccess(boolean includesStairs) {
		return verifiedAccess(includesStairs, 60, 40);
	}

	private static LoadRouteTimetablePort.RouteAccessData verifiedAccess(
		boolean includesStairs, int entryDistanceMeters, int exitDistanceMeters
	) {
		var edges = List.of(
			new LoadRouteTimetablePort.PathwayEdge(
				"entry", "entrance", "platform-a", 120, entryDistanceMeters, false, includesStairs, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState((includesStairs) ? "STAIR_ONLY" : "STEP_FREE"),
			new LoadRouteTimetablePort.PathwayEdge(
				"exit", "platform-b", "outside", 60, exitDistanceMeters, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"));
		var evidence = List.of(
			new LoadRouteTimetablePort.RouteEdgeEvidence(
				"entry-evidence", "station-a", "line", "entry", "ENTRY",
				"OFFICIAL_SOURCE", "VERIFIED", true, null),
			new LoadRouteTimetablePort.RouteEdgeEvidence(
				"exit-evidence", "station-b", "line", "exit", "EXIT",
				"OFFICIAL_SOURCE", "VERIFIED", true, null));
		return new LoadRouteTimetablePort.RouteAccessData(
			List.of(
				new LoadRouteTimetablePort.PathwayNode("entrance", "station-a", null, "ENTRANCE"),
				new LoadRouteTimetablePort.PathwayNode("platform-a", "station-a", "line", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("platform-b", "station-b", "line", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("outside", "station-b", null, "EXIT")),
			edges, List.of(), evidence);
	}
}
