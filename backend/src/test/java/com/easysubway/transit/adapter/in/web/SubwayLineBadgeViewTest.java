package com.easysubway.transit.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.transit.domain.SubwayLine;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("지하철 노선 뱃지 뷰 모델")
class SubwayLineBadgeViewTest {

	@Test
	@DisplayName("수도권 4호선 이름을 4호선 및 엠블럼 4로 정제하고 공인 컬러를 부여한다")
	void parsesNumberedLine() {
		var badge = SubwayLineBadgeView.fromName("수도권 4호선");
		assertThat(badge.name()).isEqualTo("4호선");
		assertThat(badge.emblemText()).isEqualTo("4");
		assertThat(badge.color()).isEqualTo("#00A5DE");
	}

	@Test
	@DisplayName("수인분당선 엠블럼과 노란색 컬러를 부여한다")
	void parsesNamedLine() {
		var badge = SubwayLineBadgeView.fromName("수인분당선");
		assertThat(badge.name()).isEqualTo("수인분당선");
		assertThat(badge.emblemText()).isEqualTo("수인");
		assertThat(badge.color()).isEqualTo("#F5A200");
	}

	@Test
	@DisplayName("콤마로 구분된 복수 노선을 개별 뱃지 목록으로 파싱한다")
	void parsesMultipleLines() {
		List<SubwayLineBadgeView> badges = SubwayLineBadgeView.fromCommaSeparated("수도권 1호선, 수도권 4호선, 수인분당선");
		assertThat(badges).hasSize(3);
		assertThat(badges.get(0).name()).isEqualTo("1호선");
		assertThat(badges.get(0).color()).isEqualTo("#0052A4");
		assertThat(badges.get(1).name()).isEqualTo("4호선");
		assertThat(badges.get(1).color()).isEqualTo("#00A5DE");
		assertThat(badges.get(2).name()).isEqualTo("수인분당선");
		assertThat(badges.get(2).color()).isEqualTo("#F5A200");
	}

	@Test
	@DisplayName("도메인 SubwayLine 엔티티로부터 직접 생성한다")
	void fromDomainEntity() {
		var line = new SubwayLine("seoul-2", "seoul-metro", "2호선", "#00A84D", "수도권", "2", true);
		var badge = SubwayLineBadgeView.from(line);
		assertThat(badge.name()).isEqualTo("2호선");
		assertThat(badge.emblemText()).isEqualTo("2");
		assertThat(badge.color()).isEqualTo("#00A84D");
	}
}
