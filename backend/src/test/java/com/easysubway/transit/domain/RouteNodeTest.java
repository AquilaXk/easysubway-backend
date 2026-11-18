package com.easysubway.transit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("내부 이동 노드")
class RouteNodeTest {

	@Test
	@DisplayName("필수 문자열은 공백을 정리해 저장한다")
	void trimsRequiredTextFields() {
		var node = new RouteNode(
			" node-sangnoksu-elevator-1 ",
			" station-sangnoksu ",
			RouteNodeType.ELEVATOR,
			" 1번 출구 엘리베이터 ",
			" B1 ",
			new BigDecimal("37.302421"),
			new BigDecimal("126.866221"),
			" facility-sangnoksu-elevator-1 ",
			" layout-sangnoksu-draft ",
			120,
			240,
			" 엘리베이터 ",
			" 휠체어 이동 가능 "
		);

		assertThat(node.id()).isEqualTo("node-sangnoksu-elevator-1");
		assertThat(node.stationId()).isEqualTo("station-sangnoksu");
		assertThat(node.name()).isEqualTo("1번 출구 엘리베이터");
		assertThat(node.facilityId()).isEqualTo("facility-sangnoksu-elevator-1");
		assertThat(node.layoutId()).isEqualTo("layout-sangnoksu-draft");
		assertThat(node.displayLabel()).isEqualTo("엘리베이터");
		assertThat(node.accessibilityNote()).isEqualTo("휠체어 이동 가능");
	}

	@Test
	@DisplayName("표시 좌표는 0 이상이어야 한다")
	void rejectsNegativeDisplayCoordinates() {
		assertThatThrownBy(() -> nodeWithDisplayX(-1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("displayX must not be negative.");
	}

	@Test
	@DisplayName("위도와 경도는 함께 있거나 함께 없어야 한다")
	void rejectsOnlyOneCoordinate() {
		assertThatThrownBy(() -> new RouteNode(
			"node-sangnoksu-elevator-1",
			"station-sangnoksu",
			RouteNodeType.ELEVATOR,
			"1번 출구 엘리베이터",
			"B1",
			new BigDecimal("37.302421"),
			null,
			"facility-sangnoksu-elevator-1",
			"layout-sangnoksu-draft",
			120,
			240,
			"엘리베이터",
			"휠체어 이동 가능"
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("latitude and longitude must be provided together.");
	}

	@Test
	@DisplayName("위도와 경도는 실제 좌표 범위를 벗어날 수 없다")
	void rejectsOutOfRangeCoordinates() {
		assertThatThrownBy(() -> nodeWithCoordinates(new BigDecimal("91"), new BigDecimal("126.866221")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("latitude must be between -90 and 90.");

		assertThatThrownBy(() -> nodeWithCoordinates(new BigDecimal("37.302421"), new BigDecimal("181")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("longitude must be between -180 and 180.");
	}

	private RouteNode nodeWithDisplayX(int displayX) {
		return nodeWithCoordinatesAndDisplayX(new BigDecimal("37.302421"), new BigDecimal("126.866221"), displayX);
	}

	private RouteNode nodeWithCoordinates(BigDecimal latitude, BigDecimal longitude) {
		return nodeWithCoordinatesAndDisplayX(latitude, longitude, 120);
	}

	private RouteNode nodeWithCoordinatesAndDisplayX(BigDecimal latitude, BigDecimal longitude, int displayX) {
		return new RouteNode(
			"node-sangnoksu-elevator-1",
			"station-sangnoksu",
			RouteNodeType.ELEVATOR,
			"1번 출구 엘리베이터",
			"B1",
			latitude,
			longitude,
			"facility-sangnoksu-elevator-1",
			"layout-sangnoksu-draft",
			displayX,
			240,
			"엘리베이터",
			"휠체어 이동 가능"
		);
	}
}
