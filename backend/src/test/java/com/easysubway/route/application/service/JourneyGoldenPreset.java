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

	/** 계단 제약만 푼 대조 질의의 프로필. NO_STAIRS는 계약상 NONE과 함께 못 쓰므로 STANDARD로 대신한다. */
	MobilityProfile unconstrainedControlProfile() {
		return profile == MobilityProfile.NO_STAIRS ? MobilityProfile.STANDARD : profile;
	}
}
