package com.easysubway.transit.adapter.out.persistence;

import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
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

	private static final List<StationExit> STATION_EXITS = List.of(
		new StationExit(
			"exit-sangnoksu-1",
			"station-sangnoksu",
			"1",
			"1번 출구",
			new BigDecimal("37.302421"),
			new BigDecimal("126.866221"),
			true,
			false,
			DataConfidenceLevel.HIGH
		),
		new StationExit(
			"exit-sangnoksu-2",
			"station-sangnoksu",
			"2",
			"2번 출구",
			new BigDecimal("37.303041"),
			new BigDecimal("126.866768"),
			false,
			true,
			DataConfidenceLevel.MEDIUM
		)
	);

	private static final List<AccessibilityFacility> ACCESSIBILITY_FACILITIES = List.of(
		new AccessibilityFacility(
			"facility-sangnoksu-elevator-1",
			"station-sangnoksu",
			"exit-sangnoksu-1",
			AccessibilityFacilityType.ELEVATOR,
			"1번 출구 엘리베이터",
			"지상",
			"대합실",
			new BigDecimal("37.302421"),
			new BigDecimal("126.866221"),
			"1번 출구와 대합실을 연결합니다.",
			AccessibilityFacilityStatus.NORMAL,
			DataConfidenceLevel.HIGH,
			LocalDate.of(2026, 6, 12)
		),
		new AccessibilityFacility(
			"facility-sangnoksu-escalator-1",
			"station-sangnoksu",
			"exit-sangnoksu-1",
			AccessibilityFacilityType.ESCALATOR,
			"1번 출구 에스컬레이터",
			"지상",
			"대합실",
			new BigDecimal("37.302444"),
			new BigDecimal("126.866250"),
			"1번 출구 방향 상행 에스컬레이터입니다.",
			AccessibilityFacilityStatus.NORMAL,
			DataConfidenceLevel.MEDIUM,
			LocalDate.of(2026, 6, 12)
		),
		new AccessibilityFacility(
			"facility-sangnoksu-accessible-toilet",
			"station-sangnoksu",
			null,
			AccessibilityFacilityType.ACCESSIBLE_TOILET,
			"장애인 화장실",
			"대합실",
			"대합실",
			new BigDecimal("37.302820"),
			new BigDecimal("126.866401"),
			"개찰구 안쪽 대합실에 있습니다.",
			AccessibilityFacilityStatus.UNKNOWN,
			DataConfidenceLevel.NEEDS_VERIFICATION,
			LocalDate.of(2026, 6, 12)
		)
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

	@Override
	public List<StationExit> loadStationExits() {
		return STATION_EXITS;
	}

	@Override
	public List<AccessibilityFacility> loadAccessibilityFacilities() {
		return ACCESSIBILITY_FACILITIES;
	}
}
