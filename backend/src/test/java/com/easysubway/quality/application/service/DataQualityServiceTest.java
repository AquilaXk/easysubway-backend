package com.easysubway.quality.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("데이터 품질 요약 서비스")
class DataQualityServiceTest {

	@Test
	@DisplayName("역 품질 등급이 비어 있으면 집계하지 않고 마스터 데이터 오류를 알린다")
	void summarizeDataQualityRejectsNullStationQualityLevel() {
		var service = new DataQualityService(new StubTransitMasterPort(
			List.of(station("station-null-quality", null)),
			List.of(),
			List.of()
		));

		assertThatThrownBy(service::summarizeDataQuality)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("station-null-quality")
			.hasMessageContaining("dataQualityLevel");
	}

	@Test
	@DisplayName("출구 데이터 신뢰도가 비어 있으면 집계하지 않고 마스터 데이터 오류를 알린다")
	void summarizeDataQualityRejectsNullExitConfidenceLevel() {
		var service = new DataQualityService(new StubTransitMasterPort(
			List.of(station("station-sangnoksu", DataQualityLevel.LEVEL_1)),
			List.of(exit("exit-null-confidence", null)),
			List.of()
		));

		assertThatThrownBy(service::summarizeDataQuality)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("exit-null-confidence")
			.hasMessageContaining("dataConfidence");
	}

	@Test
	@DisplayName("시설 데이터 신뢰도가 비어 있으면 집계하지 않고 마스터 데이터 오류를 알린다")
	void summarizeDataQualityRejectsNullFacilityConfidenceLevel() {
		var service = new DataQualityService(new StubTransitMasterPort(
			List.of(station("station-sangnoksu", DataQualityLevel.LEVEL_1)),
			List.of(exit("exit-sangnoksu-1", DataConfidenceLevel.HIGH)),
			List.of(facility("facility-null-confidence", null))
		));

		assertThatThrownBy(service::summarizeDataQuality)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("facility-null-confidence")
			.hasMessageContaining("dataConfidence");
	}

	private static Station station(String id, DataQualityLevel qualityLevel) {
		return new Station(
			id,
			"상록수",
			"Sangnoksu",
			"수도권",
			new BigDecimal("37.302795"),
			new BigDecimal("126.866489"),
			qualityLevel,
			LocalDate.of(2026, 6, 12),
			true
		);
	}

	private static StationExit exit(String id, DataConfidenceLevel confidenceLevel) {
		return new StationExit(
			id,
			"station-sangnoksu",
			"1",
			"1번 출구",
			new BigDecimal("37.302100"),
			new BigDecimal("126.866100"),
			true,
			false,
			confidenceLevel
		);
	}

	private static AccessibilityFacility facility(String id, DataConfidenceLevel confidenceLevel) {
		return new AccessibilityFacility(
			id,
			"station-sangnoksu",
			"exit-sangnoksu-1",
			AccessibilityFacilityType.ELEVATOR,
			"엘리베이터",
			"B1",
			"1F",
			new BigDecimal("37.302200"),
			new BigDecimal("126.866200"),
			"교통약자 승강 편의시설",
			AccessibilityFacilityStatus.NORMAL,
			confidenceLevel,
			LocalDate.of(2026, 6, 12)
		);
	}

	private record StubTransitMasterPort(
		List<Station> stations,
		List<StationExit> exits,
		List<AccessibilityFacility> facilities
	) implements LoadTransitMasterPort {

		@Override
		public List<TransitOperator> loadOperators() {
			return List.of();
		}

		@Override
		public List<SubwayLine> loadLines() {
			return List.of();
		}

		@Override
		public List<Station> loadStations() {
			return stations;
		}

		@Override
		public List<StationLine> loadStationLines() {
			return List.of();
		}

		@Override
		public List<StationExit> loadStationExits() {
			return exits;
		}

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return facilities;
		}
	}
}
