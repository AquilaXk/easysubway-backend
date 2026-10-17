package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.FacilityAvailabilityPort;
import com.easysubway.journey.application.FacilityAvailabilityView;
import com.easysubway.journey.application.FacilityStatusUnavailableException;
import com.easysubway.journey.application.JourneyAlternatives;
import com.easysubway.journey.application.JourneyRequest.ConstraintMode;
import com.easysubway.journey.application.SimpleFacilityAvailabilityView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 시설 가동 뷰의 신선도 판정과 거절 사유 계약. 차단 edge를 번들 전환으로 해석하는 경로는 엔진 통합 테스트가 다루고,
 * 여기서는 뷰에 차단 edge가 없어 timetable을 읽지 않는 경로만 확인한다.
 */
class JourneyFacilityBlockOverlayTest {

	private static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final String REJECTION_PREFIX = "FACILITY_STATUS_UNAVAILABLE: ";
	private static final RouteTimetableRaptorPlanner.CompiledTimetable NO_TIMETABLE_NEEDED = null;

	@Test
	void rejectsAStepFreeRequestWithTheReasonWhenTheViewCannotBeTrusted() {
		assertRejected(FacilityAvailabilityView.unavailable(), "facility status unavailable");
		assertRejected(new SimpleFacilityAvailabilityView(true, null, Set.of()), "facility status has no observation time");
		assertRejected(FacilityAvailabilityView.empty(NOW.minus(JourneyFacilityBlockOverlay.MAX_AGE).minusNanos(1)),
			"facility status is older than 5 minutes");
		assertRejected(FacilityAvailabilityView.empty(NOW.plus(JourneyFacilityBlockOverlay.MAX_FUTURE_SKEW).plusNanos(1)),
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
			NOW, NOW.minus(JourneyFacilityBlockOverlay.MAX_AGE), NOW.plus(JourneyFacilityBlockOverlay.MAX_FUTURE_SKEW)
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

	private static void assertRejected(FacilityAvailabilityView view, String reason) {
		assertThatThrownBy(() -> new JourneyFacilityBlockOverlay(() -> view, true, CLOCK)
			.captureWithStatus(ConstraintMode.REQUIRE_STEP_FREE, NO_TIMETABLE_NEEDED))
			.isInstanceOf(FacilityStatusUnavailableException.class)
			.hasMessage(REJECTION_PREFIX + reason);
	}
}
