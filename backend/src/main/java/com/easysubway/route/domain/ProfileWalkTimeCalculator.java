package com.easysubway.route.domain;

import com.easysubway.profile.domain.MobilityType;
import java.util.Objects;

public final class ProfileWalkTimeCalculator {

	private static final int STEP_FREE_FACILITY_WAIT_SECONDS = 60;

	private ProfileWalkTimeCalculator() {
	}

	public static WalkTime estimateSeconds(
		int baselineSeconds,
		MobilityPreset preset,
		WalkTimeSource timeSource,
		boolean facilityWaitAlreadyIncluded
	) {
		if (baselineSeconds < 0) {
			throw new IllegalArgumentException("baselineSeconds must not be negative");
		}
		Objects.requireNonNull(preset, "preset must not be null");
		Objects.requireNonNull(timeSource, "timeSource must not be null");

		int seconds = Math.toIntExact(Math.ceilDiv((long) baselineSeconds * preset.speedFactorPercent, 100));
		if (preset == MobilityPreset.STEP_FREE && !facilityWaitAlreadyIncluded) {
			seconds += STEP_FREE_FACILITY_WAIT_SECONDS;
		}
		return new WalkTime(seconds, timeSource, preset);
	}

	public static MobilityPreset presetFor(MobilityType mobilityType) {
		return switch (Objects.requireNonNull(mobilityType, "mobilityType must not be null")) {
			case SENIOR, PREGNANT, TEMPORARY_INJURY -> MobilityPreset.SLOW;
			case LUGGAGE -> MobilityPreset.NO_STAIRS;
			case STROLLER, WHEELCHAIR -> MobilityPreset.STEP_FREE;
		};
	}

	public record WalkTime(int seconds, WalkTimeSource timeSource, MobilityPreset appliedPreset) {
	}

	public enum WalkTimeSource {
		MEASURED_PATHWAY,
		OFFICIAL_BASELINE,
		DISTANCE_ESTIMATE
	}

	public enum MobilityPreset {
		STANDARD(100),
		SLOW(135),
		NO_STAIRS(120),
		STEP_FREE(100);

		private final int speedFactorPercent;

		MobilityPreset(int speedFactorPercent) {
			this.speedFactorPercent = speedFactorPercent;
		}
	}
}
