package com.easysubway.admin.web;

import com.easysubway.transit.domain.SubwayLine;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubwayLineBadgeViewTest {

	@Test
	@DisplayName("수도권 호선 접두사를 제거하고 올바른 엠블럼과 색상을 반환한다")
	void fromNumberedLine() {
		var badge = SubwayLineBadgeView.fromName("수도권 4호선");
		assertThat(badge.name()).isEqualTo("4호선");
		assertThat(badge.emblemText()).isEqualTo("4");
		assertThat(badge.color()).isEqualTo("#00A5DE");
	}

	@Test
	@DisplayName("특수 노선명을 정규화하고 약칭 엠블럼을 반환한다")
	void fromSpecialLine() {
		var badge = SubwayLineBadgeView.fromName("수인분당선");
		assertThat(badge.name()).isEqualTo("수인분당선");
		assertThat(badge.emblemText()).isEqualTo("수인");
		assertThat(badge.color()).isEqualTo("#F5A200");
	}

	@Test
	@DisplayName("콤마로 구분된 복수 노선명을 개별 뱃지 목록으로 파싱한다")
	void fromCommaSeparated() {
		List<SubwayLineBadgeView> badges = SubwayLineBadgeView.fromCommaSeparated("수도권 1호선, 수도권 4호선, 수인분당선");
		assertThat(badges).hasSize(3);
		assertThat(badges.get(0).name()).isEqualTo("1호선");
		assertThat(badges.get(1).name()).isEqualTo("4호선");
		assertThat(badges.get(2).name()).isEqualTo("수인분당선");

		assertThat(SubwayLineBadgeView.fromCommaSeparated(null)).isEmpty();
		assertThat(SubwayLineBadgeView.fromCommaSeparated("—")).isEmpty();
	}

	@Test
	@DisplayName("SubwayLine 도메인 객체로부터 뱃지를 생성한다")
	void fromDomainObject() {
		SubwayLine line = new SubwayLine("line-4", "op-seoul", "수도권 4호선", "#00A5DE", "수도권", "4", true);
		var badge = SubwayLineBadgeView.from(line);
		assertThat(badge.id()).isEqualTo("line-4");
		assertThat(badge.name()).isEqualTo("4호선");
		assertThat(badge.emblemText()).isEqualTo("4");
		assertThat(badge.color()).isEqualTo("#00A5DE");

		assertThat(SubwayLineBadgeView.from(null).name()).isEqualTo("—");
		assertThat(SubwayLineBadgeView.fromName(null).name()).isEqualTo("—");
	}
}
