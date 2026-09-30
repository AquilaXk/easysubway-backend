package com.easysubway.journey.application;

import com.easysubway.journey.bundle.TransitionFacilityRequirements;
import com.easysubway.journey.bundle.TransitionFacilityRequirements.Requirement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * 무단차 전환 통과 판정(#418 QA 결정 추가 2). data {@code evaluateStepFreeTransition}과 같은 규칙이다.
 *
 * <ul>
 *   <li>group 안의 시설은 한 대 이상 가동이면 그 group이 통과한다.</li>
 *   <li>경로({@code path_id})는 모든 group이 통과할 때 통과한다.</li>
 *   <li>전환은 요구 행이 있는 모든 방향({@code direction_next_station_id})에서 통과하는 경로가 하나 이상 있을 때만 통과한다.</li>
 *   <li>요구 행이 없는 전환은 통과한다(차단 근거 없음).</li>
 * </ul>
 */
public final class StepFreeTransitionEvaluator {

	private StepFreeTransitionEvaluator() {
	}

	/** 한 전환의 요구 행으로 통과 여부를 판정한다. 방향 목록은 다음 역 id의 UTF-16 코드 단위 순이다. */
	public static TransitionOutcome evaluate(List<Requirement> rows, Predicate<String> isOperating) {
		Objects.requireNonNull(rows, "rows");
		Objects.requireNonNull(isOperating, "isOperating");
		String transitionKey = null;
		// 방향 → 경로 → group → 시설
		var directions = new TreeMap<String, Map<String, Map<String, List<String>>>>();
		for (Requirement row : rows) {
			if (transitionKey != null && !transitionKey.equals(row.transitionKey())) {
				throw new IllegalArgumentException("step-free evaluation requires rows of a single transition_key");
			}
			transitionKey = row.transitionKey();
			directions.computeIfAbsent(row.directionNextStationId(), ignored -> new LinkedHashMap<>())
				.computeIfAbsent(row.pathId(), ignored -> new LinkedHashMap<>())
				.computeIfAbsent(row.groupKind(), ignored -> new ArrayList<>())
				.add(row.facilityId());
		}
		var outcomes = new ArrayList<DirectionOutcome>(directions.size());
		boolean passable = true;
		for (var direction : directions.entrySet()) {
			boolean directionPassable = direction.getValue().values().stream()
				.anyMatch(groups -> groups.values().stream()
					.allMatch(facilityIds -> facilityIds.stream().anyMatch(isOperating)));
			outcomes.add(new DirectionOutcome(direction.getKey(), directionPassable));
			passable &= directionPassable;
		}
		return new TransitionOutcome(passable, outcomes);
	}

	/**
	 * 불가 시설 집합으로 통과하지 못하는 전환 키를 모은다. 매핑이 없는 번들은 차단 0건으로 숨기지 않도록 거부한다.
	 */
	public static Set<String> blockedTransitionKeys(
		TransitionFacilityRequirements requirements,
		Set<String> outOfServiceFacilityIds
	) {
		Objects.requireNonNull(requirements, "requirements");
		Objects.requireNonNull(outOfServiceFacilityIds, "outOfServiceFacilityIds");
		if (!requirements.present()) {
			throw new IllegalArgumentException("route bundle has no transition facility requirement mapping");
		}
		if (outOfServiceFacilityIds.isEmpty()) {
			return Set.of();
		}
		var blocked = new TreeSet<String>();
		Predicate<String> isOperating = facilityId -> !outOfServiceFacilityIds.contains(facilityId);
		for (String transitionKey : requirements.transitionKeys()) {
			if (!evaluate(requirements.requirementsFor(transitionKey), isOperating).passable()) {
				blocked.add(transitionKey);
			}
		}
		return Set.copyOf(blocked);
	}

	public record DirectionOutcome(String nextStationId, boolean passable) {
	}

	public record TransitionOutcome(boolean passable, List<DirectionOutcome> directions) {

		public TransitionOutcome {
			directions = List.copyOf(directions);
		}
	}
}
