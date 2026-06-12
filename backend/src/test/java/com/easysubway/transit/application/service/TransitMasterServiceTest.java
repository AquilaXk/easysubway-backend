package com.easysubway.transit.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.transit.adapter.out.persistence.InMemoryTransitMasterRepository;
import com.easysubway.transit.application.port.in.StationSearchCommand;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.StationNotFoundException;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransitMasterServiceTest {

	private final TransitMasterService service = new TransitMasterService(new InMemoryTransitMasterRepository());

	@Test
	void listOperatorsReturnsActiveMasterData() {
		var operators = service.listOperators();

		assertThat(operators)
			.extracting("id")
			.contains("seoul-metro", "korail");
	}

	@Test
	void listLinesCanFilterByOperatorId() {
		var lines = service.listLines("korail");

		assertThat(lines)
			.extracting("id")
			.containsExactly("suin-bundang");
	}

	@Test
	void searchStationsMatchesKoreanAndEnglishNames() {
		var koreanMatches = service.searchStations(new StationSearchCommand("상록수", null));
		var englishMatches = service.searchStations(new StationSearchCommand("sang", null));

		assertThat(koreanMatches).hasSize(1);
		assertThat(englishMatches).hasSize(1);
		assertThat(koreanMatches.getFirst().station().dataQualityLevel()).isEqualTo(DataQualityLevel.LEVEL_1);
	}

	@Test
	void searchStationsExcludesInactiveLinesFromStationResponses() {
		var serviceWithInactiveLine = new TransitMasterService(new TransitMasterPortWithInactiveLine());

		var stations = serviceWithInactiveLine.searchStations(new StationSearchCommand("상록수", null));
		var inactiveLineMatches = serviceWithInactiveLine.searchStations(new StationSearchCommand("상록수", "closed-line"));

		assertThat(stations).hasSize(1);
		assertThat(stations.getFirst().lines())
			.extracting("id")
			.containsExactly("seoul-4");
		assertThat(inactiveLineMatches).isEmpty();
	}

	@Test
	void getStationThrowsDomainExceptionForUnknownStation() {
		assertThatThrownBy(() -> service.getStation("missing"))
			.isInstanceOf(StationNotFoundException.class)
			.hasMessage("역 정보를 찾을 수 없습니다.");
	}

	private static class TransitMasterPortWithInactiveLine implements LoadTransitMasterPort {

		@Override
		public List<TransitOperator> loadOperators() {
			return List.of(
				new TransitOperator(
					"seoul-metro",
					"서울교통공사",
					"수도권",
					"https://www.seoulmetro.co.kr",
					"https://www.seoulmetro.co.kr/kr/customerMain.do",
					DataSourceType.OFFICIAL_FILE,
					true
				)
			);
		}

		@Override
		public List<SubwayLine> loadLines() {
			return List.of(
				new SubwayLine("seoul-4", "seoul-metro", "수도권 4호선", "#00A5DE", "수도권", "4", true),
				new SubwayLine("closed-line", "seoul-metro", "운영 종료 노선", "#999999", "수도권", "C", false)
			);
		}

		@Override
		public List<Station> loadStations() {
			return List.of(
				new Station(
					"station-sangnoksu",
					"상록수",
					"Sangnoksu",
					"수도권",
					new BigDecimal("37.302795"),
					new BigDecimal("126.866489"),
					DataQualityLevel.LEVEL_1,
					LocalDate.of(2026, 6, 12),
					true
				)
			);
		}

		@Override
		public List<StationLine> loadStationLines() {
			return List.of(
				new StationLine("station-sangnoksu", "seoul-4", "448", 48, "당고개 방면 / 오이도 방면"),
				new StationLine("station-sangnoksu", "closed-line", "999", 99, "운영 종료")
			);
		}
	}
}
