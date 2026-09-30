package com.easysubway.route.application.service;

import com.easysubway.journey.application.FacilityAvailabilityPort;
import com.easysubway.journey.application.FacilityAvailabilityView;
import com.easysubway.journey.application.FacilityStatusUnavailableException;
import com.easysubway.journey.application.JourneyRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.BitSet;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 요청마다 시설 가동 뷰를 한 번 캡처해 차단 전용 오버레이로 컴파일한다(point·profile 공용).
 *
 * <p>차단은 {@code REQUIRE_STEP_FREE} 요청에만 적용한다(#418 QA 결정 추가 4). 그 밖의 요청(NONE, 선호 모드)에는
 * 시설 뷰가 무관하므로 조회하지 않고 차단 0건이다.</p>
 *
 * <p>무단차 요청의 차단은 뷰가 신선할 때만 적용한다: 가용 상태이고, observedAt이 있으며, 현재 기준 5분 이내이고
 * 30초를 넘는 미래가 아니며, 모든 차단 edge id가 캡처된 번들의 전환으로 해석되어야 한다. 그렇지 않은
 * 뷰(조회 실패 포함)는 required=true면 명시적 오류가 되고, required=false면 차단 0건이다.</p>
 */
final class JourneyFacilityBlockOverlay {

	static final Duration MAX_AGE = Duration.ofMinutes(5);
	static final Duration MAX_FUTURE_SKEW = Duration.ofSeconds(30);

	private static final Logger log = LoggerFactory.getLogger(JourneyFacilityBlockOverlay.class);

	private final FacilityAvailabilityPort port;
	private final boolean required;
	private final Clock clock;

	JourneyFacilityBlockOverlay(FacilityAvailabilityPort port, boolean required, Clock clock) {
		this.port = Objects.requireNonNull(port, "facilityAvailabilityPort");
		this.required = required;
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	RouteTimetableRaptorPlanner.RealtimeOverlay capture(
		JourneyRequest.ConstraintMode constraintMode,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable
	) {
		if (constraintMode != JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE) {
			return RouteTimetableRaptorPlanner.RealtimeOverlay.empty();
		}
		FacilityAvailabilityView view = currentView();
		BitSet blocked = new BitSet();
		String unusableReason = unusableReason(view, clock.instant());
		if (unusableReason == null) {
			unusableReason = compileBlocked(view, timetable, blocked);
		}
		if (unusableReason == null) {
			return RouteTimetableRaptorPlanner.RealtimeOverlay.blockedOnly(blocked);
		}
		if (required) {
			throw new FacilityStatusUnavailableException("FACILITY_STATUS_UNAVAILABLE: " + unusableReason);
		}
		return RouteTimetableRaptorPlanner.RealtimeOverlay.empty();
	}

	private FacilityAvailabilityView currentView() {
		try {
			return port.currentView();
		} catch (RuntimeException exception) {
			log.warn("Facility availability lookup failed; facility status is treated as unavailable", exception);
			return null;
		}
	}

	private static String unusableReason(FacilityAvailabilityView view, Instant now) {
		if (view == null || !view.available()) return "facility status unavailable";
		Instant observedAt = view.observedAt();
		if (observedAt == null) return "facility status has no observation time";
		if (observedAt.isBefore(now.minus(MAX_AGE))) return "facility status is older than 5 minutes";
		if (observedAt.isAfter(now.plus(MAX_FUTURE_SKEW))) return "facility status observation time is in the future";
		return null;
	}

	private static String compileBlocked(
		FacilityAvailabilityView view,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		BitSet blocked
	) {
		for (String edgeId : view.blockedPathwayEdgeIds()) {
			int[] transitions = timetable.transitionIdsForEdge(edgeId);
			if (transitions.length == 0) return "blocked pathway edge is not in the captured route bundle";
			for (int transition : transitions) {
				blocked.set(transition);
			}
		}
		return null;
	}
}
