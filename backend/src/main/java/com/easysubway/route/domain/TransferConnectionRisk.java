package com.easysubway.route.domain;

import com.easysubway.route.domain.ProfileWalkTimeCalculator.MobilityPreset;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;

/**
 * 보행 프로필별 환승 소요 시간과 공식 후속 배차 간격만으로 결정론적(deterministic) 환승 여유 위험도를 산출하는 도메인 모델(#457).
 *
 * <p>과거 지연 이력이나 확률·추정치를 일절 사용하지 않으며, 실제 확보된 환승 여유 시간({@code slackSeconds})과
 * 다음 탑승 노선의 공식 후속 열차 간격({@code nextHeadwaySeconds}, 후속 열차가 없으면 {@code null} = 막차 연결)만으로
 * 위험도 점수(0~100)와 등급({@link Level})을 계산한다.</p>
 */
public record TransferConnectionRisk(
	long slackSeconds,
	int verifiedTransferSeconds,
	Integer nextHeadwaySeconds,
	int riskScore,
	Level level
) {
	/** #469 SAFEST_CONNECTION 상한과 동일한 환승 여유 안전 포화 기준(10분 = 600초). */
	public static final long SAFE_SLACK_CAP_SECONDS = 600L;

	/** 공식 후속 배차 간격 위험도 포화 기준(30분 = 1,800초). */
	public static final int HEADWAY_RISK_CAP_SECONDS = 1_800;

	private static final int MAX_SLACK_RISK_POINTS = 70;
	private static final int MAX_HEADWAY_RISK_POINTS = 30;
	private static final int LAST_TRAIN_BASE_RISK_SCORE = 85;
	private static final int LAST_TRAIN_SLACK_PENALTY_POINTS = 15;
	private static final int MODERATE_RISK_THRESHOLD = 35;
	private static final int HIGH_RISK_THRESHOLD = 65;

	/** 위험도가 높은 환승부터 내림차순으로 정렬하는 비교기(등급 -> 위험도 점수 -> 적은 여유 시간 순). */
	public static final Comparator<TransferConnectionRisk> RISK_DESCENDING = Comparator
		.comparingInt((TransferConnectionRisk risk) -> risk.level().ordinal())
		.thenComparingInt(TransferConnectionRisk::riskScore)
		.thenComparing(Comparator.comparingLong(TransferConnectionRisk::slackSeconds).reversed())
		.reversed();

	public enum Level {
		LOW,
		MODERATE,
		HIGH,
		CRITICAL_LAST_TRAIN
	}

	public TransferConnectionRisk {
		validateInputs(slackSeconds, verifiedTransferSeconds, nextHeadwaySeconds);
		if (riskScore < 0 || riskScore > 100) {
			throw new IllegalArgumentException("riskScore must be between 0 and 100");
		}
		Objects.requireNonNull(level, "level must not be null");
		if ((nextHeadwaySeconds == null) != (level == Level.CRITICAL_LAST_TRAIN)) {
			throw new IllegalArgumentException("CRITICAL_LAST_TRAIN level must match absent nextHeadwaySeconds");
		}
	}

	public boolean isLastTrainConnection() {
		return nextHeadwaySeconds == null;
	}

	public static TransferConnectionRisk evaluate(
		long slackSeconds,
		int verifiedTransferSeconds,
		Integer nextHeadwaySeconds
	) {
		validateInputs(slackSeconds, verifiedTransferSeconds, nextHeadwaySeconds);
		long clampedSlack = Math.min(slackSeconds, SAFE_SLACK_CAP_SECONDS);
		long slackDeficit = SAFE_SLACK_CAP_SECONDS - clampedSlack;
		if (nextHeadwaySeconds == null) {
			int slackPenalty = (int) Math.ceilDiv(
				slackDeficit * LAST_TRAIN_SLACK_PENALTY_POINTS,
				SAFE_SLACK_CAP_SECONDS
			);
			return new TransferConnectionRisk(
				slackSeconds,
				verifiedTransferSeconds,
				null,
				LAST_TRAIN_BASE_RISK_SCORE + slackPenalty,
				Level.CRITICAL_LAST_TRAIN
			);
		}
		int slackRisk = (int) Math.ceilDiv(slackDeficit * MAX_SLACK_RISK_POINTS, SAFE_SLACK_CAP_SECONDS);
		int clampedHeadway = Math.min(nextHeadwaySeconds, HEADWAY_RISK_CAP_SECONDS);
		int headwayRisk = Math.ceilDiv(clampedHeadway * MAX_HEADWAY_RISK_POINTS, HEADWAY_RISK_CAP_SECONDS);
		int riskScore = slackRisk + headwayRisk;
		Level level = Level.LOW;
		if (riskScore >= HIGH_RISK_THRESHOLD) {
			level = Level.HIGH;
		} else if (riskScore >= MODERATE_RISK_THRESHOLD) {
			level = Level.MODERATE;
		}
		return new TransferConnectionRisk(
			slackSeconds,
			verifiedTransferSeconds,
			nextHeadwaySeconds,
			riskScore,
			level
		);
	}

	public static TransferConnectionRisk evaluate(
		Instant previousArrival,
		Instant nextDeparture,
		int verifiedTransferSeconds,
		Integer nextHeadwaySeconds
	) {
		long elapsedSeconds = Duration.between(
			Objects.requireNonNull(previousArrival, "previousArrival must not be null"),
			Objects.requireNonNull(nextDeparture, "nextDeparture must not be null")
		).getSeconds();
		return evaluate(elapsedSeconds - verifiedTransferSeconds, verifiedTransferSeconds, nextHeadwaySeconds);
	}

	public static TransferConnectionRisk evaluateForProfile(
		Instant previousArrival,
		Instant nextDeparture,
		int baseDurationSeconds,
		int distanceMeters,
		MobilityPreset mobilityPreset,
		int walkingSpeedMetersPerHour,
		Integer nextHeadwaySeconds
	) {
		int verifiedTransferSeconds = distanceMeters > 0
			? ProfileWalkTimeCalculator.journeySeconds(
				distanceMeters, walkingSpeedMetersPerHour, mobilityPreset, false)
			: ProfileWalkTimeCalculator.measuredJourneySeconds(
				baseDurationSeconds, walkingSpeedMetersPerHour, mobilityPreset, false);
		return evaluate(previousArrival, nextDeparture, verifiedTransferSeconds, nextHeadwaySeconds);
	}

	private static void validateInputs(
		long slackSeconds,
		int verifiedTransferSeconds,
		Integer nextHeadwaySeconds
	) {
		if (slackSeconds < 0) {
			throw new IllegalArgumentException("slackSeconds must not be negative");
		}
		if (verifiedTransferSeconds < 0) {
			throw new IllegalArgumentException("verifiedTransferSeconds must not be negative");
		}
		if (nextHeadwaySeconds != null && nextHeadwaySeconds <= 0) {
			throw new IllegalArgumentException("nextHeadwaySeconds must be positive when present");
		}
	}
}
