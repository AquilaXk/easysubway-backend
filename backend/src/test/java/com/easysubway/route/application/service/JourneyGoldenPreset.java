package com.easysubway.route.application.service;

import com.easysubway.journey.application.JourneyRequest.ConstraintMode;
import com.easysubway.journey.application.JourneyRequest.MobilityProfile;

/**
 * 모바일이 보내는 보행 프로필 프리셋 4종(profile, constraint 조합). 같은 질의를 프리셋마다 따로 고정한다.
 */
enum JourneyGoldenPreset {
	STANDARD(MobilityProfile.STANDARD, ConstraintMode.NONE),
	SLOW(MobilityProfile.SLOW, ConstraintMode.NONE),
	NO_STAIRS(MobilityProfile.NO_STAIRS, ConstraintMode.REQUIRE_STEP_FREE),
	STEP_FREE(MobilityProfile.STEP_FREE, ConstraintMode.REQUIRE_STEP_FREE);

	private final MobilityProfile profile;
	private final ConstraintMode constraint;

	JourneyGoldenPreset(MobilityProfile profile, ConstraintMode constraint) {
		this.profile = profile;
		this.constraint = constraint;
	}

	MobilityProfile profile() {
		return profile;
	}

	ConstraintMode constraint() {
		return constraint;
	}

	boolean requiresStepFree() {
		return constraint == ConstraintMode.REQUIRE_STEP_FREE;
	}
}
