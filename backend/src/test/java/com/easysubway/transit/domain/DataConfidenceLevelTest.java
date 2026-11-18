package com.easysubway.transit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("데이터 신뢰도 수준")
class DataConfidenceLevelTest {

	@Test
	@DisplayName("모든 신뢰도 수준은 한국어 라벨을 제공한다")
	void allConfidenceLevelsHaveKoreanLabels() {
		for (DataConfidenceLevel level : DataConfidenceLevel.values()) {
			assertThat(level.label()).isNotBlank();
		}
		assertThat(DataConfidenceLevel.HIGH.label()).isEqualTo("최근 확인된 정보");
		assertThat(DataConfidenceLevel.MEDIUM.label()).isEqualTo("일부 확인된 정보");
		assertThat(DataConfidenceLevel.LOW.label()).isEqualTo("확인이 더 필요한 정보");
		assertThat(DataConfidenceLevel.NEEDS_VERIFICATION.label()).isEqualTo("확인이 더 필요해요");
	}
}
