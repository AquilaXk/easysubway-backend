package com.easysubway.route.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.route.domain.ProfileWalkTimeCalculator.MobilityPreset;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("보행 프로필·배차 간격 기반 결정론적 환승 여유 위험도 산출 검증 (#457)")
class TransferConnectionRiskTest {

	@Test
	@DisplayName("환승 여유가 10분(600초) 이상이고 후속 배차가 촘촘하면 LOW 등급과 낮은 위험도 점수를 반환한다")
	void returnsLowRiskWhenSlackReachesSafeCapAndHeadwayIsShort() {
		var riskAtCap = TransferConnectionRisk.evaluate(600L, 120, 180);
		var riskAboveCap = TransferConnectionRisk.evaluate(900L, 120, 180);

		assertThat(riskAtCap.slackSeconds()).isEqualTo(600L);
		assertThat(riskAtCap.verifiedTransferSeconds()).isEqualTo(120);
		assertThat(riskAtCap.nextHeadwaySeconds()).isEqualTo(180);
		assertThat(riskAtCap.isLastTrainConnection()).isFalse();
		assertThat(riskAtCap.level()).isEqualTo(TransferConnectionRisk.Level.LOW);
		assertThat(riskAtCap.riskScore()).isEqualTo(3);
		assertThat(riskAboveCap.riskScore()).isEqualTo(riskAtCap.riskScore());
		assertThat(riskAboveCap.level()).isEqualTo(TransferConnectionRisk.Level.LOW);
	}

	@Test
	@DisplayName("동일 배차 간격에서 환승 여유 시간이 줄어들수록 위험도 점수가 단조 증가하며 LOW -> MODERATE -> HIGH로 전이된다")
	void increasesRiskMonotonicallyAsSlackDecreases() {
		var generous = TransferConnectionRisk.evaluate(540L, 120, 300);
		var moderate = TransferConnectionRisk.evaluate(300L, 120, 300);
		var tight = TransferConnectionRisk.evaluate(60L, 120, 300);
		var zeroSlack = TransferConnectionRisk.evaluate(0L, 120, 300);

		assertThat(generous.level()).isEqualTo(TransferConnectionRisk.Level.LOW);
		assertThat(moderate.level()).isEqualTo(TransferConnectionRisk.Level.MODERATE);
		assertThat(tight.level()).isEqualTo(TransferConnectionRisk.Level.HIGH);
		assertThat(zeroSlack.level()).isEqualTo(TransferConnectionRisk.Level.HIGH);
		assertThat(generous.riskScore())
			.isLessThan(moderate.riskScore());
		assertThat(moderate.riskScore())
			.isLessThan(tight.riskScore());
		assertThat(tight.riskScore())
			.isLessThan(zeroSlack.riskScore());
	}

	@Test
	@DisplayName("동일 환승 여유 시간에서 공식 후속 배차 간격이 길어질수록 위험도 점수가 단조 증가하고 30분(1800초)에서 상한 고정된다")
	void increasesRiskMonotonicallyAsOfficialHeadwayIncreasesWithCap() {
		var headway3Min = TransferConnectionRisk.evaluate(300L, 120, 180);
		var headway10Min = TransferConnectionRisk.evaluate(300L, 120, 600);
		var headway30Min = TransferConnectionRisk.evaluate(300L, 120, 1800);
		var headway45Min = TransferConnectionRisk.evaluate(300L, 120, 2700);

		assertThat(headway3Min.riskScore()).isLessThan(headway10Min.riskScore());
		assertThat(headway10Min.riskScore()).isLessThan(headway30Min.riskScore());
		assertThat(headway45Min.riskScore()).isEqualTo(headway30Min.riskScore());
	}

	@Test
	@DisplayName("후속 열차가 없는 막차 환승(nextHeadwaySeconds == null)은 CRITICAL_LAST_TRAIN 등급과 85~100점 위험도를 반환한다")
	void returnsCriticalLastTrainWhenNoSubsequentTrainExists() {
		var lastTrainGenerous = TransferConnectionRisk.evaluate(600L, 180, null);
		var lastTrainModerate = TransferConnectionRisk.evaluate(300L, 180, null);
		var lastTrainZeroSlack = TransferConnectionRisk.evaluate(0L, 180, null);

		assertThat(lastTrainGenerous.isLastTrainConnection()).isTrue();
		assertThat(lastTrainGenerous.level()).isEqualTo(TransferConnectionRisk.Level.CRITICAL_LAST_TRAIN);
		assertThat(lastTrainGenerous.riskScore()).isEqualTo(85);

		assertThat(lastTrainModerate.level()).isEqualTo(TransferConnectionRisk.Level.CRITICAL_LAST_TRAIN);
		assertThat(lastTrainModerate.riskScore()).isBetween(86, 99);

		assertThat(lastTrainZeroSlack.level()).isEqualTo(TransferConnectionRisk.Level.CRITICAL_LAST_TRAIN);
		assertThat(lastTrainZeroSlack.riskScore()).isEqualTo(100);
	}

	@Test
	@DisplayName("동일한 도착·출발 시각과 동선에서도 보행 프로필(STANDARD -> SLOW -> STEP_FREE)에 따라 환승 소요 시간과 위험도가 결정론적으로 달라진다")
	void evaluatesProfileSpecificRiskDeterministically() {
		Instant arrival = Instant.parse("2026-07-06T00:10:00Z");
		Instant departure = Instant.parse("2026-07-06T00:16:00Z"); // 360초 간격

		var standardRisk = TransferConnectionRisk.evaluateForProfile(
			arrival, departure, 120, 100, MobilityPreset.STANDARD, 4000, 360);
		var slowRisk = TransferConnectionRisk.evaluateForProfile(
			arrival, departure, 120, 100, MobilityPreset.SLOW, 3000, 360);
		var stepFreeRisk = TransferConnectionRisk.evaluateForProfile(
			arrival, departure, 120, 100, MobilityPreset.STEP_FREE, 3000, 360);

		assertThat(standardRisk.verifiedTransferSeconds()).isLessThan(slowRisk.verifiedTransferSeconds());
		assertThat(slowRisk.verifiedTransferSeconds()).isLessThan(stepFreeRisk.verifiedTransferSeconds());
		assertThat(standardRisk.slackSeconds()).isGreaterThan(slowRisk.slackSeconds());
		assertThat(slowRisk.slackSeconds()).isGreaterThan(stepFreeRisk.slackSeconds());
		assertThat(standardRisk.riskScore()).isLessThan(slowRisk.riskScore());
		assertThat(slowRisk.riskScore()).isLessThan(stepFreeRisk.riskScore());

		// 거리 없이 공식 실측 시간만 있는 경우(distanceMeters == 0)에도 프로필 가중치가 반영된다.
		var measuredStandard = TransferConnectionRisk.evaluateForProfile(
			arrival, departure, 120, 0, MobilityPreset.STANDARD, 4000, 360);
		var measuredStepFree = TransferConnectionRisk.evaluateForProfile(
			arrival, departure, 120, 0, MobilityPreset.STEP_FREE, 3000, 360);
		assertThat(measuredStepFree.verifiedTransferSeconds()).isGreaterThan(measuredStandard.verifiedTransferSeconds());
		assertThat(measuredStepFree.riskScore()).isGreaterThan(measuredStandard.riskScore());
	}

	@Test
	@DisplayName("RISK_DESCENDING 비교기는 등급 -> 위험도 점수 -> 여유 시간 오름차순(더 촉박한 순)으로 정렬한다")
	void ordersRisksFromHighestToLowest() {
		var low = TransferConnectionRisk.evaluate(500L, 120, 180);
		var moderate = TransferConnectionRisk.evaluate(300L, 120, 300);
		var high = TransferConnectionRisk.evaluate(30L, 120, 900);
		var lastTrainMoreSlack = TransferConnectionRisk.evaluate(700L, 120, null);
		var lastTrainLessSlack = TransferConnectionRisk.evaluate(600L, 120, null);

		var sorted = List.of(low, moderate, lastTrainMoreSlack, high, lastTrainLessSlack).stream()
			.sorted(TransferConnectionRisk.RISK_DESCENDING)
			.toList();

		assertThat(sorted).containsExactly(
			lastTrainLessSlack,
			lastTrainMoreSlack,
			high,
			moderate,
			low
		);
	}

	@Test
	@DisplayName("생성자 및 팩토리 메서드는 음수 여유 시간·음수 환승 시간·0 이하 배차 간격·등급 불일치를 거부한다")
	void validatesConstructorAndFactoryBoundaries() {
		assertThatThrownBy(() -> TransferConnectionRisk.evaluate(-1L, 120, 180))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("slackSeconds");
		assertThatThrownBy(() -> TransferConnectionRisk.evaluate(60L, -1, 180))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("verifiedTransferSeconds");
		assertThatThrownBy(() -> TransferConnectionRisk.evaluate(60L, 120, 0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("nextHeadwaySeconds");
		assertThatThrownBy(() -> TransferConnectionRisk.evaluate(60L, 120, -60))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("nextHeadwaySeconds");
		assertThatThrownBy(() -> TransferConnectionRisk.evaluate(
			Instant.parse("2026-07-06T00:10:00Z"),
			Instant.parse("2026-07-06T00:11:00Z"),
			120,
			180))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("slackSeconds");
		assertThatThrownBy(() -> new TransferConnectionRisk(60L, 120, 180, -1, TransferConnectionRisk.Level.LOW))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("riskScore");
		assertThatThrownBy(() -> new TransferConnectionRisk(60L, 120, 180, 101, TransferConnectionRisk.Level.HIGH))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("riskScore");
		assertThatThrownBy(() -> new TransferConnectionRisk(60L, 120, 180, 50, null))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new TransferConnectionRisk(
			60L, 120, null, 50, TransferConnectionRisk.Level.HIGH))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("CRITICAL_LAST_TRAIN");
		assertThatThrownBy(() -> new TransferConnectionRisk(
			60L, 120, 180, 90, TransferConnectionRisk.Level.CRITICAL_LAST_TRAIN))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("CRITICAL_LAST_TRAIN");
	}
}
