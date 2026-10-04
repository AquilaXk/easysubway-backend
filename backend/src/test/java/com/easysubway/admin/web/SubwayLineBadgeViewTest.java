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

		var badge2 = SubwayLineBadgeView.fromName("2호선");
		assertThat(badge2.color()).isEqualTo("#00A84D");
		assertThat(badge2.emblemText()).isEqualTo("2");

		var badge9 = SubwayLineBadgeView.fromName("9호선");
		assertThat(badge9.color()).isEqualTo("#BB8336");
	}

	@Test
	@DisplayName("특수 노선명을 정규화하고 약칭 엠블럼을 반환한다")
	void fromSpecialLine() {
		var suin = SubwayLineBadgeView.fromName("수인분당선");
		assertThat(suin.name()).isEqualTo("수인분당선");
		assertThat(suin.emblemText()).isEqualTo("수인");
		assertThat(suin.color()).isEqualTo("#F5A200");

		var sinbundang = SubwayLineBadgeView.fromName("신분당선");
		assertThat(sinbundang.emblemText()).isEqualTo("신분당");
		assertThat(sinbundang.color()).isEqualTo("#D4003B");

		var gyeongui = SubwayLineBadgeView.fromName("경의중앙선");
		assertThat(gyeongui.emblemText()).isEqualTo("경의");

		var airport = SubwayLineBadgeView.fromName("공항철도");
		assertThat(airport.emblemText()).isEqualTo("공항");

		var itx = SubwayLineBadgeView.fromName("ITX-청춘");
		assertThat(itx.emblemText()).isEqualTo("ITX");

		var gtx = SubwayLineBadgeView.fromName("GTX-A");
		assertThat(gtx.emblemText()).isEqualTo("GTX");

		var unknown = SubwayLineBadgeView.fromName("자가용");
		assertThat(unknown.emblemText()).isEqualTo("자가");
		assertThat(unknown.color()).isEqualTo("#5C6BC0");

		var shortName = SubwayLineBadgeView.fromName("경전철");
		assertThat(shortName.emblemText()).isEqualTo("경전");
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
		assertThat(SubwayLineBadgeView.fromCommaSeparated("")).isEmpty();
		assertThat(SubwayLineBadgeView.fromCommaSeparated("   ")).isEmpty();
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

		SubwayLine emptyColorLine = new SubwayLine("line-2", "op-seoul", "2호선", "", "수도권", null, true);
		var badgeEmptyColor = SubwayLineBadgeView.from(emptyColorLine);
		assertThat(badgeEmptyColor.color()).isEqualTo("#00A84D");

		assertThat(SubwayLineBadgeView.from(null).name()).isEqualTo("—");
		assertThat(SubwayLineBadgeView.fromName(null).name()).isEqualTo("—");
		assertThat(SubwayLineBadgeView.cleanLineName(null)).isEmpty();
	}
}
