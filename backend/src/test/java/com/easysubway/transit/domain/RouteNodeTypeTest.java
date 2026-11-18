package com.easysubway.transit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("내부 이동 노드 유형")
class RouteNodeTypeTest {

	@Test
	@DisplayName("모든 노드 유형은 비어있지 않은 고유한 한글 라벨을 가진다(#2349 label() 표시 계약)")
	void everyTypeHasNonBlankUniqueLabel() {
		for (RouteNodeType type : RouteNodeType.values()) {
			assertThat(type.label()).as(type.name()).isNotNull().isNotBlank();
		}

		assertThat(Arrays.stream(RouteNodeType.values()).map(RouteNodeType::label))
			.as("노드 유형 라벨은 서로 달라야 관리자 화면에서 유형을 구분할 수 있다")
			.doesNotHaveDuplicates();
	}
}
