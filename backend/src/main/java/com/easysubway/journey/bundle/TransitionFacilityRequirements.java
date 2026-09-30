package com.easysubway.journey.bundle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 경로 번들 accessibility 구성요소의 {@code transition_facility_requirement} 표(#418 QA 결정 추가 2, data#836).
 *
 * <p>행은 {@code (transition_key, path_id, direction_next_station_id, group_kind, facility_id)}이며
 * {@code transition_key}는 기존 역 단위 ENTRY/EXIT edge id다. 표가 없는 번들은 {@link #missing()}으로 표현해
 * "요구 행 0건"과 구분한다.</p>
 */
public final class TransitionFacilityRequirements {

	public static final String EXIT_ELEVATORS = "EXIT_ELEVATORS";
	public static final String PLATFORM_DIRECTION_ELEVATORS = "PLATFORM_DIRECTION_ELEVATORS";
	private static final Set<String> GROUP_KINDS = Set.of(EXIT_ELEVATORS, PLATFORM_DIRECTION_ELEVATORS);
	private static final TransitionFacilityRequirements MISSING = new TransitionFacilityRequirements(false, Map.of());

	private final boolean present;
	private final Map<String, List<Requirement>> byTransition;

	private TransitionFacilityRequirements(boolean present, Map<String, List<Requirement>> byTransition) {
		this.present = present;
		this.byTransition = byTransition;
	}

	/** 표가 없는 번들: 무단차 요구를 알 수 없다. */
	public static TransitionFacilityRequirements missing() {
		return MISSING;
	}

	public static TransitionFacilityRequirements of(List<Requirement> rows) {
		var grouped = new TreeMap<String, List<Requirement>>();
		for (Requirement row : Objects.requireNonNull(rows, "rows")) {
			grouped.computeIfAbsent(Objects.requireNonNull(row, "row").transitionKey(), ignored -> new ArrayList<>()).add(row);
		}
		var frozen = new TreeMap<String, List<Requirement>>();
		grouped.forEach((transitionKey, transitionRows) -> frozen.put(transitionKey, List.copyOf(transitionRows)));
		return new TransitionFacilityRequirements(true, Collections.unmodifiableMap(frozen));
	}

	public boolean present() {
		return present;
	}

	/** 요구 행이 있는 전환 키(바이트 순). */
	public Set<String> transitionKeys() {
		return byTransition.keySet();
	}

	/** 한 전환의 요구 행. 요구 행이 없는 전환은 빈 목록이다. */
	public List<Requirement> requirementsFor(String transitionKey) {
		return byTransition.getOrDefault(transitionKey, List.of());
	}

	public record Requirement(
		String transitionKey,
		String pathId,
		String directionNextStationId,
		String groupKind,
		String facilityId
	) {

		public Requirement {
			requireText(transitionKey, "transition_key");
			requireText(pathId, "path_id");
			requireText(directionNextStationId, "direction_next_station_id");
			requireText(groupKind, "group_kind");
			if (!GROUP_KINDS.contains(groupKind)) {
				throw new IllegalArgumentException("transition_facility_requirement group_kind is invalid");
			}
			requireText(facilityId, "facility_id");
		}

		private static void requireText(String value, String field) {
			if (value == null || value.isBlank()) {
				throw new IllegalArgumentException("transition_facility_requirement " + field + " is blank");
			}
		}
	}
}
