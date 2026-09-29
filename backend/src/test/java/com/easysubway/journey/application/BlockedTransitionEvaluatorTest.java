package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.bundle.TransitionFacilityRequirements;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BlockedTransitionEvaluatorTest {

	@Test
	@DisplayName("구간 대체 2대 중 1대 불가 시 통과 (대체 시설 가용)")
	void passesWhenOneOfTwoAlternativesIsAvailable() {
		// transition t1 has 1 segment with alternatives fac-1 and fac-2
		var requirements = TransitionFacilityRequirements.of(Map.of(
			"t1", List.of(Set.of("fac-1", "fac-2"))
		));
		// fac-1 is broken, fac-2 is normal
		var statuses = Map.of(
			"fac-1", AccessibilityFacilityStatus.BROKEN,
			"fac-2", AccessibilityFacilityStatus.NORMAL
		);

		Set<String> blocked = BlockedTransitionEvaluator.evaluateBlockedTransitions(requirements, statuses);

		assertThat(blocked).isEmpty();
	}

	@Test
	@DisplayName("구간 대체 2대 모두 불가 시 막힘")
	void blocksWhenAllAlternativesInSegmentAreUnavailable() {
		var requirements = TransitionFacilityRequirements.of(Map.of(
			"t1", List.of(Set.of("fac-1", "fac-2"))
		));
		// fac-1 is broken, fac-2 is under construction
		var statuses = Map.of(
			"fac-1", AccessibilityFacilityStatus.BROKEN,
			"fac-2", AccessibilityFacilityStatus.UNDER_CONSTRUCTION
		);

		Set<String> blocked = BlockedTransitionEvaluator.evaluateBlockedTransitions(requirements, statuses);

		assertThat(blocked).containsExactly("t1");
	}

	@Test
	@DisplayName("필요한 구간 2개 중 1개 막힘 시 전환 전체 막힘")
	void blocksWhenAnyRequiredSegmentIsBlocked() {
		// transition t1 requires segment 0 fac-1 AND segment 1 fac-2
		var requirements = TransitionFacilityRequirements.of(Map.of(
			"t1", List.of(Set.of("fac-1"), Set.of("fac-2"))
		));
		// segment 0 (fac-1) is broken, segment 1 (fac-2) is normal
		var statuses = Map.of(
			"fac-1", AccessibilityFacilityStatus.BROKEN,
			"fac-2", AccessibilityFacilityStatus.NORMAL
		);

		Set<String> blocked = BlockedTransitionEvaluator.evaluateBlockedTransitions(requirements, statuses);

		assertThat(blocked).containsExactly("t1");
	}

	@Test
	@DisplayName("USER_REPORTED만 있는 경우 차단되지 않고 통과")
	void passesWhenOnlyUserReported() {
		var requirements = TransitionFacilityRequirements.of(Map.of(
			"t1", List.of(Set.of("fac-1"))
		));
		var statuses = Map.of(
			"fac-1", AccessibilityFacilityStatus.USER_REPORTED
		);

		Set<String> blocked = BlockedTransitionEvaluator.evaluateBlockedTransitions(requirements, statuses);

		assertThat(blocked).isEmpty();
	}

	@Test
	@DisplayName("UNKNOWN 상태인 경우 차단되지 않고 통과")
	void passesWhenUnknownStatus() {
		var requirements = TransitionFacilityRequirements.of(Map.of(
			"t1", List.of(Set.of("fac-1"))
		));
		var statuses = Map.of(
			"fac-1", AccessibilityFacilityStatus.UNKNOWN
		);

		Set<String> blocked = BlockedTransitionEvaluator.evaluateBlockedTransitions(requirements, statuses);

		assertThat(blocked).isEmpty();
	}

	@Test
	@DisplayName("ADMIN_VERIFIED 및 NORMAL 상태는 통과, CLOSED 상태는 차단")
	void validatesNormalAdminAndClosedStatuses() {
		var requirements = TransitionFacilityRequirements.of(Map.of(
			"t-closed", List.of(Set.of("fac-closed")),
			"t-admin", List.of(Set.of("fac-admin")),
			"t-normal", List.of(Set.of("fac-normal"))
		));
		var statuses = Map.of(
			"fac-closed", AccessibilityFacilityStatus.CLOSED,
			"fac-admin", AccessibilityFacilityStatus.ADMIN_VERIFIED,
			"fac-normal", AccessibilityFacilityStatus.NORMAL
		);

		Set<String> blocked = BlockedTransitionEvaluator.evaluateBlockedTransitions(requirements, statuses);

		assertThat(blocked).containsExactly("t-closed");
	}

	@Test
	@DisplayName("매핑이 missing(누락)이거나 비어있을 때 빈 차단 집합 반환")
	void returnsEmptyWhenRequirementsMissingOrEmpty() {
		var missingReqs = TransitionFacilityRequirements.missing();
		var statuses = Map.of("fac-1", AccessibilityFacilityStatus.BROKEN);

		assertThat(BlockedTransitionEvaluator.evaluateBlockedTransitions(missingReqs, statuses)).isEmpty();
		assertThat(BlockedTransitionEvaluator.evaluateBlockedTransitions(null, statuses)).isEmpty();
		assertThat(BlockedTransitionEvaluator.evaluateBlockedTransitions(missingReqs, null)).isEmpty();
	}

	@Test
	@DisplayName("null 상태 및 빈 컬렉션에 대한 방어 로직 전수 검증")
	void edgeCasesAndNullChecks() {
		assertThat(BlockedTransitionEvaluator.isFacilityDisabled(null)).isFalse();

		var reqs = TransitionFacilityRequirements.of(Map.of(
			"t1", List.of(Set.of("f1"))
		));
		assertThat(BlockedTransitionEvaluator.evaluateBlockedTransitions(reqs, Map.of())).isEmpty();
		assertThat(BlockedTransitionEvaluator.evaluateBlockedTransitionsWithDisabledIds(null, Set.of("f1"))).isEmpty();
		assertThat(BlockedTransitionEvaluator.evaluateBlockedTransitionsWithDisabledIds(TransitionFacilityRequirements.missing(), Set.of("f1"))).isEmpty();
		assertThat(BlockedTransitionEvaluator.evaluateBlockedTransitionsWithDisabledIds(reqs, null)).isEmpty();
		assertThat(BlockedTransitionEvaluator.evaluateBlockedTransitionsWithDisabledIds(reqs, Set.of())).isEmpty();

		assertThat(BlockedTransitionEvaluator.isTransitionBlocked(null, Set.of("f1"))).isFalse();
		assertThat(BlockedTransitionEvaluator.isTransitionBlocked(List.of(), Set.of("f1"))).isFalse();

		assertThat(BlockedTransitionEvaluator.isSegmentBlocked(null, Set.of("f1"))).isFalse();
		assertThat(BlockedTransitionEvaluator.isSegmentBlocked(Set.of(), Set.of("f1"))).isFalse();
		assertThat(BlockedTransitionEvaluator.isSegmentBlocked(Set.of("f1"), null)).isFalse();
		assertThat(BlockedTransitionEvaluator.isSegmentBlocked(Set.of("f1"), Set.of())).isFalse();
	}
}
