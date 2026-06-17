package com.easysubway.quality.domain;

import java.util.List;

public record AccessibilityImprovementPriority(
	String stationId,
	String facilityId,
	int priorityScore,
	List<String> reasons
) {

	public AccessibilityImprovementPriority {
		reasons = List.copyOf(reasons);
	}
}
