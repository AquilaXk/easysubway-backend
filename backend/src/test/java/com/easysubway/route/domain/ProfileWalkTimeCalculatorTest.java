package com.easysubway.route.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.domain.ProfileWalkTimeCalculator.MobilityPreset;
import com.easysubway.route.domain.ProfileWalkTimeCalculator.WalkTimeSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("보행 프로필 시간 계산기")
class ProfileWalkTimeCalculatorTest {

	@Test
	@DisplayName("프리셋별 speedFactor와 timeSource 라벨을 적용한다")
	void appliesPresetSpeedFactorAndTimeSource() {
		assertThat(ProfileWalkTimeCalculator.estimateSeconds(100, MobilityPreset.STANDARD, WalkTimeSource.MEASURED_PATHWAY, false))
			.isEqualTo(new ProfileWalkTimeCalculator.WalkTime(100, WalkTimeSource.MEASURED_PATHWAY, MobilityPreset.STANDARD));
		assertThat(ProfileWalkTimeCalculator.estimateSeconds(100, MobilityPreset.SLOW, WalkTimeSource.OFFICIAL_BASELINE, false))
			.isEqualTo(new ProfileWalkTimeCalculator.WalkTime(135, WalkTimeSource.OFFICIAL_BASELINE, MobilityPreset.SLOW));
		assertThat(ProfileWalkTimeCalculator.estimateSeconds(100, MobilityPreset.NO_STAIRS, WalkTimeSource.DISTANCE_ESTIMATE, false))
			.isEqualTo(new ProfileWalkTimeCalculator.WalkTime(120, WalkTimeSource.DISTANCE_ESTIMATE, MobilityPreset.NO_STAIRS));
		assertThat(ProfileWalkTimeCalculator.estimateSeconds(100, MobilityPreset.STEP_FREE, WalkTimeSource.MEASURED_PATHWAY, false))
			.isEqualTo(new ProfileWalkTimeCalculator.WalkTime(160, WalkTimeSource.MEASURED_PATHWAY, MobilityPreset.STEP_FREE));
	}

	@Test
	@DisplayName("시설 대기 시간이 baseline에 이미 포함됐으면 중복 가산하지 않는다")
	void doesNotDoubleCountFacilityWait() {
		assertThat(ProfileWalkTimeCalculator.estimateSeconds(160, MobilityPreset.STEP_FREE, WalkTimeSource.MEASURED_PATHWAY, true))
			.isEqualTo(new ProfileWalkTimeCalculator.WalkTime(160, WalkTimeSource.MEASURED_PATHWAY, MobilityPreset.STEP_FREE));
	}

	@Test
	@DisplayName("기존 mobilityType은 기본 preset으로 매핑한다")
	void mapsMobilityTypeToPreset() {
		assertThat(ProfileWalkTimeCalculator.presetFor(MobilityType.SENIOR)).isEqualTo(MobilityPreset.SLOW);
		assertThat(ProfileWalkTimeCalculator.presetFor(MobilityType.PREGNANT)).isEqualTo(MobilityPreset.SLOW);
		assertThat(ProfileWalkTimeCalculator.presetFor(MobilityType.TEMPORARY_INJURY)).isEqualTo(MobilityPreset.SLOW);
		assertThat(ProfileWalkTimeCalculator.presetFor(MobilityType.LUGGAGE)).isEqualTo(MobilityPreset.NO_STAIRS);
		assertThat(ProfileWalkTimeCalculator.presetFor(MobilityType.STROLLER)).isEqualTo(MobilityPreset.STEP_FREE);
		assertThat(ProfileWalkTimeCalculator.presetFor(MobilityType.WHEELCHAIR)).isEqualTo(MobilityPreset.STEP_FREE);
	}

	@Test
	@DisplayName("baseline은 음수가 될 수 없다")
	void rejectsNegativeBaseline() {
		assertThatThrownBy(() -> ProfileWalkTimeCalculator.estimateSeconds(-1, MobilityPreset.STANDARD, WalkTimeSource.MEASURED_PATHWAY, false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("baselineSeconds must not be negative");
	}
}
