package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.FacilityAvailabilityPort;
import com.easysubway.journey.application.FacilityAvailabilityView;
import com.easysubway.journey.application.FacilityStatusUnavailableException;
import com.easysubway.journey.application.JourneyAlternatives;
import com.easysubway.journey.application.JourneyRequest.ConstraintMode;
import com.easysubway.journey.application.SimpleFacilityAvailabilityView;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteAccessData;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransferRule;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 시설 가동 뷰의 신선도 판정과 거절 사유 계약. 차단 edge를 번들 전환으로 해석하는 경로는 엔진 통합 테스트가 다루고,
 * 여기서는 뷰에 차단 edge가 없어 timetable을 읽지 않는 경로만 확인한다.
 */
class JourneyFacilityBlockOverlayTest {

	private static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	// 계약(Javadoc)에 적힌 한도를 리터럴로 고정한다. production 상수에서 값을 끌어오면 한도가 바뀌어도 테스트가 따라 바뀐다.
	private static final Duration MAX_AGE = Duration.ofMinutes(5);
	private static final Duration MAX_FUTURE_SKEW = Duration.ofSeconds(30);
	private static final String REJECTION_PREFIX = "FACILITY_STATUS_UNAVAILABLE: ";
	private static final RouteTimetableRaptorPlanner.CompiledTimetable NO_TIMETABLE_NEEDED = null;

	@Test
	void rejectsAStepFreeRequestWithTheReasonWhenTheViewCannotBeTrusted() {
		assertRejected(FacilityAvailabilityView.unavailable(), "facility status unavailable");
		assertRejected(new SimpleFacilityAvailabilityView(true, null, Set.of()), "facility status has no observation time");
		assertRejected(FacilityAvailabilityView.empty(NOW.minus(MAX_AGE).minusNanos(1)),
			"facility status is older than 5 minutes");
		assertRejected(FacilityAvailabilityView.empty(NOW.plus(MAX_FUTURE_SKEW).plusNanos(1)),
			"facility status observation time is in the future");
	}

	@Test
	void treatsAFailingLookupAsAnUnavailableView() {
		FacilityAvailabilityPort failing = () -> {
			throw new IllegalStateException("source down");
		};

		assertThatThrownBy(() -> new JourneyFacilityBlockOverlay(failing, true, CLOCK)
			.captureWithStatus(ConstraintMode.REQUIRE_STEP_FREE, NO_TIMETABLE_NEEDED))
			.isInstanceOf(FacilityStatusUnavailableException.class)
			.hasMessage(REJECTION_PREFIX + "facility status unavailable");
	}

	@Test
	void appliesAViewObservedExactlyAtTheAgeAndFutureSkewLimits() {
		for (Instant observedAt : new Instant[] {
			NOW, NOW.minus(MAX_AGE), NOW.plus(MAX_FUTURE_SKEW)
		}) {
			var atLimit = new JourneyFacilityBlockOverlay(() -> FacilityAvailabilityView.empty(observedAt), true, CLOCK)
				.captureWithStatus(ConstraintMode.REQUIRE_STEP_FREE, NO_TIMETABLE_NEEDED);
			assertThat(atLimit.status()).as("observed at %s", observedAt).isEqualTo(JourneyAlternatives.FacilityStatus.APPLIED);
		}
	}

	@Test
	void searchesWithoutBlocksAndReportsUnobservedWhenAnUnusableViewIsNotAStepFreeRequirement() {
		var staleView = FacilityAvailabilityView.empty(NOW.minus(Duration.ofHours(1)));

		var notStepFree = new JourneyFacilityBlockOverlay(() -> staleView, true, CLOCK)
			.captureWithStatus(ConstraintMode.NONE, NO_TIMETABLE_NEEDED);
		var notRequired = new JourneyFacilityBlockOverlay(() -> staleView, false, CLOCK)
			.captureWithStatus(ConstraintMode.REQUIRE_STEP_FREE, NO_TIMETABLE_NEEDED);

		for (var capture : new JourneyFacilityBlockOverlay.Capture[] {notStepFree, notRequired}) {
			assertThat(capture.status()).isEqualTo(JourneyAlternatives.FacilityStatus.UNOBSERVED);
			assertThat(capture.overlay().isEmpty()).isTrue();
		}
	}

	@Test
	void preservesMappedBlocksAndReportsUnobservedWhenUnmappedEdgeOccursInNonRequireStepFree() {
		var planner = new RouteTimetableRaptorPlanner();
		var compiled = planner.compile(sampleTimetableWithTransfer());
		int[] mappedTransitions = compiled.transitionIdsForEdge("transfer-primary");
		assertThat(mappedTransitions).isNotEmpty();

		// "transfer-primary"는 정상 매핑, "unmapped-edge-999"는 미매핑
		var view = new SimpleFacilityAvailabilityView(true, NOW, Set.of("transfer-primary", "unmapped-edge-999"));

		// 1) REQUIRE_STEP_FREE + required=true: 미매핑 간선으로 인해 예외 발생 (Fail-Closed)
		assertThatThrownBy(() -> new JourneyFacilityBlockOverlay(() -> view, true, CLOCK)
			.captureWithStatus(ConstraintMode.REQUIRE_STEP_FREE, compiled))
			.isInstanceOf(FacilityStatusUnavailableException.class)
			.hasMessage(REJECTION_PREFIX + "blocked pathway edge is not in the captured route bundle");

		// 2) ConstraintMode.NONE + required=true: 일반 요청은 미매핑 간선 시 전체 뷰 거절 계약(empty) 유지
		var nonStepFree = new JourneyFacilityBlockOverlay(() -> view, true, CLOCK)
			.captureWithStatus(ConstraintMode.NONE, compiled);
		assertThat(nonStepFree.status()).isEqualTo(JourneyAlternatives.FacilityStatus.UNOBSERVED);
		assertThat(nonStepFree.overlay().isEmpty()).isTrue();

		// 3) required=false인 REQUIRE_STEP_FREE: 마찬가지로 차단 유지 + UNOBSERVED
		var notRequired = new JourneyFacilityBlockOverlay(() -> view, false, CLOCK)
			.captureWithStatus(ConstraintMode.REQUIRE_STEP_FREE, compiled);
		assertThat(notRequired.status()).isEqualTo(JourneyAlternatives.FacilityStatus.UNOBSERVED);
		assertThat(notRequired.overlay().isEmpty()).isFalse();
		for (int trans : mappedTransitions) {
			assertThat(notRequired.overlay().isTransitionBlocked(trans)).isTrue();
		}
	}

	@Test
	void retainsInternalVerifiedBlockedEdgesWhenExternalFeedIsMissingOrStale() {
		var compiled = new RouteTimetableRaptorPlanner().compile(sampleTimetableWithTransfer());
		int[] primaryTransitions = compiled.transitionIdsForEdge("transfer-primary");

		var unavailableExternal = FacilityAvailabilityView.unavailable(Set.of("transfer-primary"));
		var preferResult = new JourneyFacilityBlockOverlay(() -> unavailableExternal, true, CLOCK)
			.captureWithStatus(ConstraintMode.NONE, compiled);
		assertThat(preferResult.status()).isEqualTo(JourneyAlternatives.FacilityStatus.UNOBSERVED);
		assertThat(preferResult.overlay().isEmpty()).isFalse();
		for (int trans : primaryTransitions) {
			assertThat(preferResult.overlay().isTransitionBlocked(trans)).isTrue();
		}

		var staleExternal = FacilityAvailabilityView.blocked(
			NOW.minus(MAX_AGE).minusSeconds(1), Set.of("transfer-primary"), Set.of("transfer-primary"));
		var stalePreferResult = new JourneyFacilityBlockOverlay(() -> staleExternal, false, CLOCK)
			.captureWithStatus(ConstraintMode.REQUIRE_STEP_FREE, compiled);
		assertThat(stalePreferResult.status()).isEqualTo(JourneyAlternatives.FacilityStatus.UNOBSERVED);
		for (int trans : primaryTransitions) {
			assertThat(stalePreferResult.overlay().isTransitionBlocked(trans)).isTrue();
		}

		assertThatThrownBy(() -> new JourneyFacilityBlockOverlay(() -> unavailableExternal, true, CLOCK)
			.captureWithStatus(ConstraintMode.REQUIRE_STEP_FREE, compiled))
			.isInstanceOf(FacilityStatusUnavailableException.class)
			.hasMessage(REJECTION_PREFIX + "facility status unavailable");
	}

	@Test
	void compilesBothExternalAndInternalBlockedEdgesWhenFeedIsFresh() {
		var compiled = new RouteTimetableRaptorPlanner().compile(sampleTimetableWithTwoTransfers());
		int[] primaryTransitions = compiled.transitionIdsForEdge("transfer-primary");
		int[] secondaryTransitions = compiled.transitionIdsForEdge("transfer-secondary");
		assertThat(primaryTransitions).isNotEmpty();
		assertThat(secondaryTransitions).isNotEmpty();

		// 1) 외부 피드 차단은 비어 있고 내부 확정 차단만 있는 신선한 뷰 -> APPLIED + 내부 차단 반영
		var internalOnly = FacilityAvailabilityView.blocked(NOW, Set.of(), Set.of("transfer-secondary"));
		for (ConstraintMode mode : new ConstraintMode[] {ConstraintMode.NONE, ConstraintMode.REQUIRE_STEP_FREE}) {
			var capture = new JourneyFacilityBlockOverlay(() -> internalOnly, true, CLOCK)
				.captureWithStatus(mode, compiled);
			assertThat(capture.status()).isEqualTo(JourneyAlternatives.FacilityStatus.APPLIED);
			for (int trans : secondaryTransitions) {
				assertThat(capture.overlay().isTransitionBlocked(trans)).isTrue();
			}
			for (int trans : primaryTransitions) {
				assertThat(capture.overlay().isTransitionBlocked(trans)).isFalse();
			}
		}

		// 2) 외부 피드 차단 + 내부 확정 차단이 서로 다른 간선일 때 -> APPLIED + 양쪽 모두 반영
		var disjointBoth = FacilityAvailabilityView.blocked(
			NOW, Set.of("transfer-primary"), Set.of("transfer-secondary"));
		for (ConstraintMode mode : new ConstraintMode[] {ConstraintMode.NONE, ConstraintMode.REQUIRE_STEP_FREE}) {
			var capture = new JourneyFacilityBlockOverlay(() -> disjointBoth, true, CLOCK)
				.captureWithStatus(mode, compiled);
			assertThat(capture.status()).isEqualTo(JourneyAlternatives.FacilityStatus.APPLIED);
			for (int trans : primaryTransitions) {
				assertThat(capture.overlay().isTransitionBlocked(trans)).isTrue();
			}
			for (int trans : secondaryTransitions) {
				assertThat(capture.overlay().isTransitionBlocked(trans)).isTrue();
			}
		}

		// 3) 외부 피드에 미매핑 간선이 섞여도 일반 요청(NONE)에서는 매핑된 내부 확정 차단은 유지한다
		var unmappedExternalWithInternal = FacilityAvailabilityView.blocked(
			NOW, Set.of("unmapped-external"), Set.of("transfer-secondary"));
		assertThatThrownBy(() -> new JourneyFacilityBlockOverlay(() -> unmappedExternalWithInternal, true, CLOCK)
			.captureWithStatus(ConstraintMode.REQUIRE_STEP_FREE, compiled))
			.isInstanceOf(FacilityStatusUnavailableException.class)
			.hasMessage(REJECTION_PREFIX + "blocked pathway edge is not in the captured route bundle");
		var noneWithUnmappedExternal = new JourneyFacilityBlockOverlay(() -> unmappedExternalWithInternal, true, CLOCK)
			.captureWithStatus(ConstraintMode.NONE, compiled);
		assertThat(noneWithUnmappedExternal.status()).isEqualTo(JourneyAlternatives.FacilityStatus.UNOBSERVED);
		for (int trans : secondaryTransitions) {
			assertThat(noneWithUnmappedExternal.overlay().isTransitionBlocked(trans)).isTrue();
		}

		// 4) 내부 확정 차단 간선이 미매핑이면 Fail-Closed (required=true) / 매핑분 보존 (required=false)
		var unmappedInternal = FacilityAvailabilityView.blocked(
			NOW, Set.of("transfer-primary"), Set.of("unmapped-internal"));
		assertThatThrownBy(() -> new JourneyFacilityBlockOverlay(() -> unmappedInternal, true, CLOCK)
			.captureWithStatus(ConstraintMode.REQUIRE_STEP_FREE, compiled))
			.isInstanceOf(FacilityStatusUnavailableException.class)
			.hasMessage(REJECTION_PREFIX + "blocked pathway edge is not in the captured route bundle");
		var optionalStepFree = new JourneyFacilityBlockOverlay(() -> unmappedInternal, false, CLOCK)
			.captureWithStatus(ConstraintMode.REQUIRE_STEP_FREE, compiled);
		assertThat(optionalStepFree.status()).isEqualTo(JourneyAlternatives.FacilityStatus.UNOBSERVED);
		for (int trans : primaryTransitions) {
			assertThat(optionalStepFree.overlay().isTransitionBlocked(trans)).isTrue();
		}

		// 5) 외부 피드 비가용 + required=false + 내부 차단 일부 미매핑이어도 매핑된 내부 차단은 유지한다
		var staleWithPartialInternal = FacilityAvailabilityView.unavailable(
			Set.of("transfer-secondary", "unmapped-internal"));
		var staleOptional = new JourneyFacilityBlockOverlay(() -> staleWithPartialInternal, false, CLOCK)
			.captureWithStatus(ConstraintMode.REQUIRE_STEP_FREE, compiled);
		assertThat(staleOptional.status()).isEqualTo(JourneyAlternatives.FacilityStatus.UNOBSERVED);
		for (int trans : secondaryTransitions) {
			assertThat(staleOptional.overlay().isTransitionBlocked(trans)).isTrue();
		}
	}

	private static RouteTimetable sampleTimetableWithTwoTransfers() {
		var calendar = new ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Asia/Seoul");
		var routes = List.of(
			new TransitRoute("route-first", "line-a", "A", "First", "transfer", "Asia/Seoul"),
			new TransitRoute("route-second", "line-b", "B", "Second", "destination", "Asia/Seoul"));
		var trips = List.of(
			new TransitTrip("trip-first", "route-first", "daily", "station-transfer", "down",
				"SUBWAY", "LOCAL", "3001", 0),
			new TransitTrip("trip-second", "route-second", "daily", "station-b", "down",
				"SUBWAY", "LOCAL", "3002", 0));
		var stopTimes = List.of(
			new TransitStopTime("trip-first", 1, "station-a", "line-a", 32_400, 32_400, 0, 0),
			new TransitStopTime("trip-first", 2, "station-transfer", "line-a", 33_000, 33_000, 0, 0),
			new TransitStopTime("trip-second", 1, "station-transfer", "line-b", 34_200, 34_200, 0, 0),
			new TransitStopTime("trip-second", 2, "station-b", "line-b", 34_800, 34_800, 0, 0));
		var edges = List.of(
			new PathwayEdge(
				"transfer-primary", "platform-transfer-a", "platform-transfer-b", 180, 150, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"),
			new PathwayEdge(
				"transfer-secondary", "platform-transfer-a", "platform-transfer-b", 220, 180, false, false, 90,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"));
		var evidence = List.of(
			new RouteEdgeEvidence(
				"transfer-evidence-primary", "station-transfer", "line-b", "transfer-primary", "TRANSFER",
				"OFFICIAL_SOURCE", "VERIFIED", true, null),
			new RouteEdgeEvidence(
				"transfer-evidence-secondary", "station-transfer", "line-b", "transfer-secondary", "TRANSFER",
				"OFFICIAL_SOURCE", "VERIFIED", true, null));
		var transferRules = List.of(
			new TransferRule(
				"transfer-rule-primary", "station-transfer", "line-a", "station-transfer", "line-b",
				"IN_STATION", 180, "transfer-primary", "transfer-primary", "VERIFIED"),
			new TransferRule(
				"transfer-rule-secondary", "station-transfer", "line-a", "station-transfer", "line-b",
				"IN_STATION", 220, "transfer-secondary", "transfer-secondary", "VERIFIED"));
		var nodes = List.of(
			new PathwayNode("entrance", "station-a", null, "ENTRANCE"),
			new PathwayNode("platform-a", "station-a", "line-a", "PLATFORM"),
			new PathwayNode("platform-transfer-a", "station-transfer", "line-a", "PLATFORM"),
			new PathwayNode("platform-transfer-b", "station-transfer", "line-b", "PLATFORM"),
			new PathwayNode("platform-b", "station-b", "line-b", "PLATFORM"),
			new PathwayNode("outside", "station-b", null, "EXIT"));
		var accessData = new RouteAccessData(nodes, edges, transferRules, evidence);
		return new RouteTimetable(List.of(calendar), List.of(), routes, trips, stopTimes,
			List.of(), List.of(), null, accessData);
	}

	private static RouteTimetable sampleTimetableWithTransfer() {
		var calendar = new ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "Asia/Seoul");
		var routes = List.of(
			new TransitRoute("route-first", "line-a", "A", "First", "transfer", "Asia/Seoul"),
			new TransitRoute("route-second", "line-b", "B", "Second", "destination", "Asia/Seoul"));
		var trips = List.of(
			new TransitTrip("trip-first", "route-first", "daily", "station-transfer", "down",
				"SUBWAY", "LOCAL", "3001", 0),
			new TransitTrip("trip-second", "route-second", "daily", "station-b", "down",
				"SUBWAY", "LOCAL", "3002", 0));
		var stopTimes = List.of(
			new TransitStopTime("trip-first", 1, "station-a", "line-a", 32_400, 32_400, 0, 0),
			new TransitStopTime("trip-first", 2, "station-transfer", "line-a", 33_000, 33_000, 0, 0),
			new TransitStopTime("trip-second", 1, "station-transfer", "line-b", 34_200, 34_200, 0, 0),
			new TransitStopTime("trip-second", 2, "station-b", "line-b", 34_800, 34_800, 0, 0));
		var edges = List.of(
			new PathwayEdge(
				"transfer-primary", "platform-transfer-a", "platform-transfer-b", 180, 150, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"));
		var evidence = List.of(
			new RouteEdgeEvidence(
				"transfer-evidence-primary", "station-transfer", "line-b", "transfer-primary", "TRANSFER",
				"OFFICIAL_SOURCE", "VERIFIED", true, null));
		var transferRulePrimary = new TransferRule(
			"transfer-rule-primary", "station-transfer", "line-a", "station-transfer", "line-b",
			"IN_STATION", 180, "transfer-primary", "transfer-primary", "VERIFIED");
		var nodes = List.of(
			new PathwayNode("entrance", "station-a", null, "ENTRANCE"),
			new PathwayNode("platform-a", "station-a", "line-a", "PLATFORM"),
			new PathwayNode("platform-transfer-a", "station-transfer", "line-a", "PLATFORM"),
			new PathwayNode("platform-transfer-b", "station-transfer", "line-b", "PLATFORM"),
			new PathwayNode("platform-b", "station-b", "line-b", "PLATFORM"),
			new PathwayNode("outside", "station-b", null, "EXIT"));
		var accessData = new RouteAccessData(
			nodes, edges, List.of(transferRulePrimary), evidence);
		return new RouteTimetable(List.of(calendar), List.of(), routes, trips, stopTimes,
			List.of(), List.of(), null, accessData);
	}

	private static void assertRejected(FacilityAvailabilityView view, String reason) {
		assertThatThrownBy(() -> new JourneyFacilityBlockOverlay(() -> view, true, CLOCK)
			.captureWithStatus(ConstraintMode.REQUIRE_STEP_FREE, NO_TIMETABLE_NEEDED))
			.isInstanceOf(FacilityStatusUnavailableException.class)
			.hasMessage(REJECTION_PREFIX + reason);
	}
}
