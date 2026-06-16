package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.domain.RouteProfileWeight;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.SubwayLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("환승 경로 그래프")
class TransferRouteGraphTest {

	@Test
	@DisplayName("공통 환승역을 찾아 한 번 환승 후보를 만든다")
	void findBestOneTransferRouteBuildsCandidateFromSharedTransferStation() {
		var graph = new TransferRouteGraph(
			List.of(line("line-a", "A 노선"), line("line-b", "B 노선")),
			List.of(station("origin", "출발"), station("transfer", "환승"), station("destination", "도착")),
			List.of(
				stationLine("origin", "line-a", 1),
				stationLine("transfer", "line-a", 3),
				stationLine("transfer", "line-b", 1),
				stationLine("destination", "line-b", 4)
			)
		);

		var route = graph.findBestOneTransferRoute(
			"origin",
			"destination",
			RouteProfileWeight.from(MobilityType.SENIOR),
			stationId -> false,
			stationId -> false
		);

		assertThat(route).isPresent();
		assertThat(route.get().transferStation().id()).isEqualTo("transfer");
		assertThat(route.get().firstSegmentStopCount()).isEqualTo(2);
		assertThat(route.get().secondSegmentStopCount()).isEqualTo(3);
		assertThat(route.get().stopCount()).isEqualTo(5);
		assertThat(route.get().firstLine().name()).isEqualTo("A 노선");
		assertThat(route.get().secondLine().name()).isEqualTo("B 노선");
	}

	@Test
	@DisplayName("휠체어 이동은 이동 거리가 길어도 계단 없는 환승역을 먼저 고른다")
	void findBestOneTransferRoutePrefersStepFreeTransferForWheelchair() {
		var graph = new TransferRouteGraph(
			List.of(line("line-a", "A 노선"), line("line-b", "B 노선")),
			List.of(
				station("origin", "출발"),
				station("stair-transfer", "계단환승"),
				station("step-free-transfer", "무단차환승"),
				station("destination", "도착")
			),
			List.of(
				stationLine("origin", "line-a", 1),
				stationLine("stair-transfer", "line-a", 2),
				stationLine("step-free-transfer", "line-a", 30),
				stationLine("stair-transfer", "line-b", 1),
				stationLine("step-free-transfer", "line-b", 30),
				stationLine("destination", "line-b", 3)
			)
		);

		var route = graph.findBestOneTransferRoute(
			"origin",
			"destination",
			RouteProfileWeight.from(MobilityType.WHEELCHAIR),
			stationId -> stationId.equals("stair-transfer"),
			stationId -> false
		);

		assertThat(route).isPresent();
		assertThat(route.get().transferStation().id()).isEqualTo("step-free-transfer");
	}

	private SubwayLine line(String id, String name) {
		return new SubwayLine(id, "operator", name, "#111111", "수도권", id, true);
	}

	private Station station(String id, String nameKo) {
		return new Station(
			id,
			nameKo,
			id,
			"수도권",
			BigDecimal.ZERO,
			BigDecimal.ZERO,
			DataQualityLevel.LEVEL_3,
			DataSourceType.OFFICIAL_FILE,
			LocalDate.of(2026, 6, 16),
			true
		);
	}

	private StationLine stationLine(String stationId, String lineId, int sequence) {
		return new StationLine(stationId, lineId, stationId + "-" + lineId, sequence, "상행 / 하행");
	}
}
