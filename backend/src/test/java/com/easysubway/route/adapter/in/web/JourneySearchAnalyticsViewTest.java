package com.easysubway.route.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.analytics.JourneySearchKind;
import com.easysubway.journey.analytics.JourneySearchOutcome;
import com.easysubway.journey.analytics.JourneySearchRecord;
import com.easysubway.journey.application.JourneyAlternatives;
import com.easysubway.journey.application.JourneyFrontierPolicyV1;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Journey 탐색 분석 화면 문구")
class JourneySearchAnalyticsViewTest {

	@Test
	@DisplayName("모든 탐색 종류와 결과 분류에 운영자용 한국어 문구가 있다")
	void everyKindAndOutcomeHasLabel() {
		Arrays.stream(JourneySearchKind.values())
			.forEach(kind -> assertThat(JourneySearchAnalyticsView.kindLabel(kind)).isNotBlank().doesNotContain("_"));
		Arrays.stream(JourneySearchOutcome.values())
			.forEach(outcome -> assertThat(JourneySearchAnalyticsView.outcomeLabel(outcome)).isNotBlank().doesNotContain("_"));
	}

	@Test
	@DisplayName("모든 대표 분류 값에 문구가 있고 기타로 떨어지지 않는다")
	void everyCategoryValueHasLabel() {
		Arrays.stream(JourneyAlternatives.Category.values()).map(Enum::name)
			.forEach(name -> assertThat(JourneySearchAnalyticsView.categoryLabel(name)).isNotEqualTo("기타"));
		Arrays.stream(JourneyFrontierPolicyV1.ObjectiveTag.values()).map(Enum::name)
			.forEach(name -> assertThat(JourneySearchAnalyticsView.categoryLabel(name)).isNotEqualTo("기타"));
	}

	@Test
	@DisplayName("모든 계단 없는 경로 판정 값에 문구가 있고 기타로 떨어지지 않는다")
	void everyStairFreeValueHasLabel() {
		Arrays.stream(JourneyAlternatives.StairFreeStatus.values()).map(Enum::name)
			.forEach(name -> assertThat(JourneySearchAnalyticsView.stairFreeLabel(name)).isNotEqualTo("기타"));
		assertThat(JourneySearchAnalyticsView.stairFreeLabel(JourneySearchRecord.UNKNOWN)).isNotEqualTo("기타");
		assertThat(JourneySearchAnalyticsView.stairFreeLabel(JourneySearchRecord.NOT_APPLICABLE)).isNotEqualTo("기타");
	}

	@Test
	@DisplayName("알 수 없는 값은 원래 코드를 드러내지 않고 기타로 표시한다")
	void unknownValuesAreLabeledOtherWithoutRawCode() {
		assertThat(JourneySearchAnalyticsView.categoryLabel("BRAND_NEW_TAG")).isEqualTo("기타");
		assertThat(JourneySearchAnalyticsView.stairFreeLabel("BRAND_NEW_STATUS")).isEqualTo("기타");
	}

	@Test
	@DisplayName("엔진 버전은 짧은 문구로 보여 주고 원본 식별자는 보조 설명에만 둔다")
	void engineVersionShowsShortLabelWithRawIdOnlyAsDetail() {
		var row = JourneySearchAnalyticsView.engineRow("EASYSUBWAY_RAPTOR_SUITE_V2/REVERSE_RANGE_RAPTOR/2.0.0", 3);

		assertThat(row.label()).isEqualTo("버전 2.0.0").doesNotContain("RAPTOR");
		assertThat(row.detail()).isEqualTo("EASYSUBWAY_RAPTOR_SUITE_V2/REVERSE_RANGE_RAPTOR/2.0.0");
		assertThat(row.count()).isEqualTo(3);
		assertThat(JourneySearchAnalyticsView.engineRow("UNKNOWN", 1).label()).isEqualTo("확인되지 않음");
		assertThat(JourneySearchAnalyticsView.engineRow("UNKNOWN", 1).detail()).isNull();
		assertThat(JourneySearchAnalyticsView.engineRow("weird", 1).label()).isEqualTo("기타");
		assertThat(JourneySearchAnalyticsView.engineRow("suite/algorithm/ ", 1).label()).isEqualTo("기타");
		assertThat(JourneySearchAnalyticsView.engineRow("weird", 1).detail()).isNull();
	}
}
