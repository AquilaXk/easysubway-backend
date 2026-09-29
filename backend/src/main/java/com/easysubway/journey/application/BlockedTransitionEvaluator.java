package com.easysubway.journey.application;

import com.easysubway.journey.bundle.TransitionFacilityRequirements;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure evaluation functions for computing blocked transitions based on
 * facility requirements and live facility statuses.
 */
public final class BlockedTransitionEvaluator {

	private BlockedTransitionEvaluator() {
	}

	/**
	 * Determines whether a facility status is considered unavailable/blocking.
	 * Only BROKEN, UNDER_CONSTRUCTION, and CLOSED block.
	 * USER_REPORTED, UNKNOWN, NORMAL, and ADMIN_VERIFIED do NOT block.
	 */
	public static boolean isFacilityDisabled(AccessibilityFacilityStatus status) {
		if (status == null) {
			return false;
		}
		return switch (status) {
			case BROKEN, UNDER_CONSTRUCTION, CLOSED -> true;
			case NORMAL, ADMIN_VERIFIED, UNKNOWN, USER_REPORTED -> false;
		};
	}

	/**
	 * Pure function to evaluate blocked transition keys from transition requirements
	 * and a status map of all facilities.
	 */
	public static Set<String> evaluateBlockedTransitions(
		TransitionFacilityRequirements requirements,
		Map<String, AccessibilityFacilityStatus> facilityStatuses
	) {
		if (requirements == null || !requirements.isPresent() || facilityStatuses == null || facilityStatuses.isEmpty()) {
			return Set.of();
		}
		Set<String> disabledFacilityIds = facilityStatuses.entrySet().stream()
			.filter(entry -> isFacilityDisabled(entry.getValue()))
			.map(Map.Entry::getKey)
			.collect(Collectors.toSet());
		return evaluateBlockedTransitionsWithDisabledIds(requirements, disabledFacilityIds);
	}

	/**
	 * Pure function to evaluate blocked transition keys given requirements
	 * and a pre-filtered set of disabled facility IDs.
	 */
	public static Set<String> evaluateBlockedTransitionsWithDisabledIds(
		TransitionFacilityRequirements requirements,
		Set<String> disabledFacilityIds
	) {
		if (requirements == null || !requirements.isPresent() || disabledFacilityIds == null || disabledFacilityIds.isEmpty()) {
			return Set.of();
		}
		Set<String> blockedTransitions = new HashSet<>();
		for (var entry : requirements.requirementsByTransition().entrySet()) {
			String transitionKey = entry.getKey();
			List<Set<String>> segments = entry.getValue();
			if (isTransitionBlocked(segments, disabledFacilityIds)) {
				blockedTransitions.add(transitionKey);
			}
		}
		return Collections.unmodifiableSet(blockedTransitions);
	}

	/**
	 * A transition is blocked if ANY of its required segments is blocked.
	 */
	public static boolean isTransitionBlocked(
		List<Set<String>> segments,
		Set<String> disabledFacilityIds
	) {
		if (segments == null || segments.isEmpty()) {
			return false;
		}
		for (Set<String> alternativeFacilities : segments) {
			if (isSegmentBlocked(alternativeFacilities, disabledFacilityIds)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A segment is blocked if and only if ALL of its alternative facilities are disabled.
	 * If at least one alternative facility is not disabled (available), the segment is passable.
	 */
	public static boolean isSegmentBlocked(
		Set<String> alternativeFacilities,
		Set<String> disabledFacilityIds
	) {
		if (alternativeFacilities == null || alternativeFacilities.isEmpty()) {
			return false;
		}
		if (disabledFacilityIds == null || disabledFacilityIds.isEmpty()) {
			return false;
		}
		for (String facilityId : alternativeFacilities) {
			if (!disabledFacilityIds.contains(facilityId)) {
				return false;
			}
		}
		return true;
	}
}
