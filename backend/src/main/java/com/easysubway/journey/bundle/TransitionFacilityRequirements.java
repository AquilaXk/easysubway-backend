package com.easysubway.journey.bundle;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable mapping of transition keys to sequential required segments,
 * where each segment contains a set of alternative facility IDs.
 */
public final class TransitionFacilityRequirements {

	private static final TransitionFacilityRequirements MISSING =
		new TransitionFacilityRequirements(false, Map.of());

	private final boolean present;
	private final Map<String, List<Set<String>>> requirementsByTransition;

	private TransitionFacilityRequirements(
		boolean present,
		Map<String, List<Set<String>>> requirementsByTransition
	) {
		this.present = present;
		if (requirementsByTransition == null || requirementsByTransition.isEmpty()) {
			this.requirementsByTransition = Map.of();
		} else {
			var copy = new LinkedHashMap<String, List<Set<String>>>();
			requirementsByTransition.forEach((transitionKey, segments) -> {
				Objects.requireNonNull(transitionKey, "transitionKey");
				Objects.requireNonNull(segments, "segments");
				copy.put(transitionKey, List.copyOf(segments.stream().map(Set::copyOf).toList()));
			});
			this.requirementsByTransition = Collections.unmodifiableMap(copy);
		}
	}

	public static TransitionFacilityRequirements missing() {
		return MISSING;
	}

	public static TransitionFacilityRequirements of(Map<String, List<Set<String>>> requirements) {
		Objects.requireNonNull(requirements, "requirements");
		return new TransitionFacilityRequirements(true, requirements);
	}

	public boolean isPresent() {
		return present;
	}

	public Map<String, List<Set<String>>> requirementsByTransition() {
		return requirementsByTransition;
	}

	public List<Set<String>> segmentsForTransition(String transitionKey) {
		if (transitionKey == null) {
			return List.of();
		}
		return requirementsByTransition.getOrDefault(transitionKey, List.of());
	}
}
