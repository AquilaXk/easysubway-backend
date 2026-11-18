package com.easysubway.route.domain;

import com.easysubway.profile.domain.MobilityType;

public enum ConstraintMode {
	STRICT_STEP_FREE,
	PREFER_STEP_FREE,
	ALLOW_WITH_WARNINGS;

	public static ConstraintMode defaultFor(MobilityType mobilityType) {
		return mobilityType == MobilityType.WHEELCHAIR ? STRICT_STEP_FREE : PREFER_STEP_FREE;
	}
}
