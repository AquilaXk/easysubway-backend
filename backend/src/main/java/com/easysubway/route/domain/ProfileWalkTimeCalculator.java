package com.easysubway.route.domain;

import com.easysubway.profile.domain.MobilityType;
import java.util.Objects;

public final class ProfileWalkTimeCalculator {

	private static final int STEP_FREE_FACILITY_WAIT_SECONDS = 60;
	/** 공공 환승 소요시간 산정 기준 보행속도 1.2 m/s(보행 프로필 앵커, hub #1700). */
	public static final int OFFICIAL_ANCHOR_SPEED_METERS_PER_HOUR = 4_320;

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

	public static int journeySeconds(
		int distanceMeters,
		int speedMetersPerHour,
		MobilityPreset mobilityPreset,
		boolean facilityWaitAlreadyIncluded
	) {
		if (distanceMeters <= 0) {
			throw new IllegalArgumentException("distanceMeters must be positive");
		}
		if (speedMetersPerHour <= 0) {
			throw new IllegalArgumentException("speedMetersPerHour must be positive");
		}
		Objects.requireNonNull(mobilityPreset, "mobilityPreset must not be null");
		long seconds = Math.ceilDiv((long) distanceMeters * 3_600L, speedMetersPerHour);
		if (mobilityPreset == MobilityPreset.STEP_FREE && !facilityWaitAlreadyIncluded) {
			seconds = Math.addExact(seconds, STEP_FREE_FACILITY_WAIT_SECONDS);
		}
		return Math.toIntExact(seconds);
	}

	/**
	 * #454·data#876: 거리 없이 공식 실측 소요시간만 있는 검증 환승(서울교통공사 15098252)의 프로필 반영 시간.
	 *
	 * <p>실측 시간이 하한이다. 걸음 속도가 공공 산정 기준 1.2 m/s({@link #OFFICIAL_ANCHOR_SPEED_METERS_PER_HOUR},
	 * 보행 프로필 앵커)보다 느리면 실측 시간 × 1.2 m/s ÷ 걸음 속도로 늘리고(올림), 같거나 빠르면 실측 시간을 그대로 쓴다.
	 * 무단차 프리셋은 거리 환승({@link #journeySeconds})과 같은 시설 대기 시간을 더한다.</p>
	 */
	public static int measuredJourneySeconds(
		int measuredSeconds,
		int speedMetersPerHour,
		MobilityPreset mobilityPreset,
		boolean facilityWaitAlreadyIncluded
	) {
		if (measuredSeconds <= 0) {
			throw new IllegalArgumentException("measuredSeconds must be positive");
		}
		if (speedMetersPerHour <= 0) {
			throw new IllegalArgumentException("speedMetersPerHour must be positive");
		}
		Objects.requireNonNull(mobilityPreset, "mobilityPreset must not be null");
		long seconds = speedMetersPerHour >= OFFICIAL_ANCHOR_SPEED_METERS_PER_HOUR
			? measuredSeconds
			: Math.ceilDiv((long) measuredSeconds * OFFICIAL_ANCHOR_SPEED_METERS_PER_HOUR, speedMetersPerHour);
		if (mobilityPreset == MobilityPreset.STEP_FREE && !facilityWaitAlreadyIncluded) {
			seconds = Math.addExact(seconds, STEP_FREE_FACILITY_WAIT_SECONDS);
		}
		return Math.toIntExact(seconds);
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
