package com.easysubway.admin.metric.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("카드 스파크라인 좌표")
class AdminMetricSparklineTest {

	@Test
	@DisplayName("값이 1개 이하면 빈 문자열을 준다")
	void tooFewPointsIsEmpty() {
		assertThat(AdminMetricSparkline.points(List.of(3.0), 100, 20)).isEmpty();
		assertThat(AdminMetricSparkline.points(Arrays.asList(null, null), 100, 20)).isEmpty();
	}

	@Test
	@DisplayName("최솟값은 아래(height), 최댓값은 위(0)로 정규화한다")
	void normalizesMinToBottomMaxToTop() {
		String points = AdminMetricSparkline.points(List.of(0.0, 10.0), 100, 20);

		// 첫 점 x=0 최솟값 → y=20, 끝 점 x=100 최댓값 → y=0
		assertThat(points).isEqualTo("0.0,20.0 100.0,0.0");
	}

	@Test
	@DisplayName("결측(null) 점은 건너뛰되 x 간격은 전체 인덱스 기준으로 유지한다")
	void skipsNullsKeepingXSpacing() {
		String points = AdminMetricSparkline.points(Arrays.asList(0.0, null, 10.0), 100, 20);

		// 인덱스 0,1,2 → x는 0,50,100. null인 1은 생략.
		assertThat(points).isEqualTo("0.0,20.0 100.0,0.0");
	}

	@Test
	@DisplayName("모든 값이 같으면 가운데 수평선으로 그린다")
	void flatSeriesDrawsMidline() {
		String points = AdminMetricSparkline.points(List.of(5.0, 5.0, 5.0), 100, 20);

		assertThat(points).isEqualTo("0.0,10.0 50.0,10.0 100.0,10.0");
	}
}
