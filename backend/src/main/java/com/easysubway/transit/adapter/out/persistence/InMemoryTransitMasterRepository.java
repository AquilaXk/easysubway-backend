package com.easysubway.transit.adapter.out.persistence;

import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryTransitMasterRepository implements LoadTransitMasterPort {

	private static final List<TransitOperator> OPERATORS = List.of(
		new TransitOperator(
			"seoul-metro",
			"서울교통공사",
			"수도권",
			"https://www.seoulmetro.co.kr",
			"https://www.seoulmetro.co.kr/kr/customerMain.do",
			DataSourceType.OFFICIAL_FILE,
			true
		),
		new TransitOperator(
			"korail",
			"한국철도공사",
			"수도권",
			"https://www.letskorail.com",
			"https://info.korail.com",
			DataSourceType.OFFICIAL_FILE,
			true
		)
	);

	private static final List<SubwayLine> LINES = List.of(
		new SubwayLine("seoul-4", "seoul-metro", "수도권 4호선", "#00A5DE", "수도권", "4", true),
		new SubwayLine("suin-bundang", "korail", "수인분당선", "#F5A200", "수도권", "K1", true)
	);

	private static final List<Station> STATIONS = List.of(
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
		),
		new Station(
			"station-sadang",
			"사당",
			"Sadang",
			"수도권",
			new BigDecimal("37.476530"),
			new BigDecimal("126.981685"),
			DataQualityLevel.LEVEL_1,
			LocalDate.of(2026, 6, 12),
			true
		)
	);

	private static final List<StationLine> STATION_LINES = List.of(
		new StationLine("station-sangnoksu", "seoul-4", "448", 48, "당고개 방면 / 오이도 방면"),
		new StationLine("station-sadang", "seoul-4", "433", 33, "당고개 방면 / 오이도 방면")
	);

	@Override
	public List<TransitOperator> loadOperators() {
		return OPERATORS;
	}

	@Override
	public List<SubwayLine> loadLines() {
		return LINES;
	}

	@Override
	public List<Station> loadStations() {
		return STATIONS;
	}

	@Override
	public List<StationLine> loadStationLines() {
		return STATION_LINES;
	}
}
