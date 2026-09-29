package com.easysubway.report.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.BlockedTransitionEvaluator;
import com.easysubway.journey.bundle.TransitionFacilityRequirements;
import com.easysubway.report.domain.FacilityReportType;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("미검증 제보(USER_REPORTED) 및 관리자 확인 큐 연계 검증 (#419)")
class FacilityReportAdminQueueTest {

	@Test
	@DisplayName("미검증 시민 제보(USER_REPORTED)는 경로 전환을 차단하지 않는다")
	void unverifiedUserReportDoesNotBlockTransition() {
		var requirements = TransitionFacilityRequirements.of(Map.of(
			"transition-1", List.of(Set.of("facility-sangnoksu-elevator-1"))
		));
		var statuses = Map.of(
			"facility-sangnoksu-elevator-1", AccessibilityFacilityStatus.USER_REPORTED
		);

		// USER_REPORTED is not considered disabled
		assertThat(BlockedTransitionEvaluator.isFacilityDisabled(AccessibilityFacilityStatus.USER_REPORTED)).isFalse();

		// Transition remains unblocked
		Set<String> blocked = BlockedTransitionEvaluator.evaluateBlockedTransitions(requirements, statuses);
		assertThat(blocked).isEmpty();
	}

	@Test
	@DisplayName("관리자가 제보를 확인(승인)하여 BROKEN으로 전환하면 비로소 차단된다")
	void adminAcceptedReportTransitionsToBrokenAndBlocks() {
		var requirements = TransitionFacilityRequirements.of(Map.of(
			"transition-1", List.of(Set.of("facility-sangnoksu-elevator-1"))
		));

		// When admin accepts ELEVATOR_UNAVAILABLE, it transitions to BROKEN
		var statusesAfterAdminAccept = Map.of(
			"facility-sangnoksu-elevator-1", AccessibilityFacilityStatus.BROKEN
		);

		assertThat(BlockedTransitionEvaluator.isFacilityDisabled(AccessibilityFacilityStatus.BROKEN)).isTrue();
		Set<String> blocked = BlockedTransitionEvaluator.evaluateBlockedTransitions(requirements, statusesAfterAdminAccept);
		assertThat(blocked).containsExactly("transition-1");
	}
}
