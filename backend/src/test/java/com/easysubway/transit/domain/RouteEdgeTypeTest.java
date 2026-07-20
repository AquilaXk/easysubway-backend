package com.easysubway.transit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("내부 이동 간선 유형")
class RouteEdgeTypeTest {

	@Test
	@DisplayName("모든 간선 유형은 비어있지 않은 고유한 한글 라벨을 가진다(#2349 label() 표시 계약)")
	void everyTypeHasNonBlankUniqueLabel() {
		for (RouteEdgeType type : RouteEdgeType.values()) {
			assertThat(type.label()).as(type.name()).isNotNull().isNotBlank();
		}

		assertThat(Arrays.stream(RouteEdgeType.values()).map(RouteEdgeType::label))
			.as("간선 유형 라벨은 서로 달라야 관리자 화면에서 유형을 구분할 수 있다")
			.doesNotHaveDuplicates();
	}

	@Test
	@DisplayName("환승 계열 4종(TRANSFER/IN_STATION_TRANSFER/OUT_OF_STATION_TRANSFER/LEGACY_TRANSFER)은 서로 다른 라벨로 구분된다")
	void transferFamilyLabelsRemainDistinct() {
		assertThat(RouteEdgeType.TRANSFER.label()).isEqualTo("환승");
		assertThat(RouteEdgeType.IN_STATION_TRANSFER.label()).isEqualTo("역내 환승");
		assertThat(RouteEdgeType.OUT_OF_STATION_TRANSFER.label()).isEqualTo("역외 환승");
		assertThat(RouteEdgeType.LEGACY_TRANSFER.label()).isEqualTo("환승(구버전)");

		assertThat(List.of(
			RouteEdgeType.TRANSFER.label(),
			RouteEdgeType.IN_STATION_TRANSFER.label(),
			RouteEdgeType.OUT_OF_STATION_TRANSFER.label(),
			RouteEdgeType.LEGACY_TRANSFER.label()
		)).doesNotHaveDuplicates();
	}
}
