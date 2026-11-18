package com.easysubway.transit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("내부 이동 간선")
class RouteEdgeTest {

	@Test
	@DisplayName("상용 network edge type 계약값을 모두 표현한다")
	void includesCommercialNetworkEdgeTypes() {
		assertThat(Arrays.stream(RouteEdgeType.values()).map(Enum::name))
			.contains(
				"RIDE",
				"IN_STATION_TRANSFER",
				"OUT_OF_STATION_TRANSFER",
				"ENTRY",
				"EXIT",
				"WALKWAY",
				"ELEVATOR",
				"RAMP",
				"STAIR",
				"ESCALATOR",
				"FACILITY_CONNECTOR",
				"LEGACY_TRANSFER"
			);
	}

	@Test
	@DisplayName("필수 문자열은 공백을 정리해 저장한다")
	void trimsRequiredTextFields() {
		var edge = new RouteEdge(
			" edge-sangnoksu-elevator-to-faregate ",
			" station-sangnoksu ",
			" node-sangnoksu-elevator-1 ",
			" node-sangnoksu-faregate ",
			RouteEdgeType.WALK,
			28,
			75,
			false,
			true,
			false,
			1,
			2,
			92,
			true
		);

		assertThat(edge.id()).isEqualTo("edge-sangnoksu-elevator-to-faregate");
		assertThat(edge.stationId()).isEqualTo("station-sangnoksu");
		assertThat(edge.fromNodeId()).isEqualTo("node-sangnoksu-elevator-1");
		assertThat(edge.toNodeId()).isEqualTo("node-sangnoksu-faregate");
	}

	@Test
	@DisplayName("거리와 예상 시간은 0 이상이어야 한다")
	void rejectsNegativeDistanceAndSeconds() {
		assertThatThrownBy(() -> edgeWithDistanceMeters(-1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("distanceMeters must not be negative.");

		assertThatThrownBy(() -> edgeWithEstimatedSeconds(-1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("estimatedSeconds must not be negative.");
	}

	@Test
	@DisplayName("경사와 통로 폭 수준은 1에서 5 사이여야 한다")
	void rejectsOutOfRangeSlopeAndWidthLevels() {
		assertThatThrownBy(() -> edgeWithSlopeLevel(0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("slopeLevel must be between 1 and 5.");

		assertThatThrownBy(() -> edgeWithWidthLevel(6))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("widthLevel must be between 1 and 5.");
	}

	@Test
	@DisplayName("신뢰도 점수는 0에서 100 사이여야 한다")
	void rejectsOutOfRangeReliabilityScore() {
		assertThatThrownBy(() -> edgeWithReliabilityScore(101))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("reliabilityScore must be between 0 and 100.");
	}

	private RouteEdge edgeWithDistanceMeters(int distanceMeters) {
		return edge(distanceMeters, 75, 1, 2, 92);
	}

	private RouteEdge edgeWithEstimatedSeconds(int estimatedSeconds) {
		return edge(28, estimatedSeconds, 1, 2, 92);
	}

	private RouteEdge edgeWithSlopeLevel(int slopeLevel) {
		return edge(28, 75, slopeLevel, 2, 92);
	}

	private RouteEdge edgeWithWidthLevel(int widthLevel) {
		return edge(28, 75, 1, widthLevel, 92);
	}

	private RouteEdge edgeWithReliabilityScore(int reliabilityScore) {
		return edge(28, 75, 1, 2, reliabilityScore);
	}

	private RouteEdge edge(
		int distanceMeters,
		int estimatedSeconds,
		int slopeLevel,
		int widthLevel,
		int reliabilityScore
	) {
		return new RouteEdge(
			"edge-sangnoksu-elevator-to-faregate",
			"station-sangnoksu",
			"node-sangnoksu-elevator-1",
			"node-sangnoksu-faregate",
			RouteEdgeType.WALK,
			distanceMeters,
			estimatedSeconds,
			false,
			true,
			false,
			slopeLevel,
			widthLevel,
			reliabilityScore,
			true
		);
	}
}
