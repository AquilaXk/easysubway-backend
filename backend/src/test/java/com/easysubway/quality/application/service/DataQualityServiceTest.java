package com.easysubway.quality.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("데이터 품질 요약 서비스")
class DataQualityServiceTest {

	private static final Clock FIXED_CLOCK = Clock.fixed(
		Instant.parse("2026-06-17T00:00:00Z"),
		ZoneId.of("Asia/Seoul")
	);

	@Test
	@DisplayName("시설 상태 갱신 지연은 30일 초과 또는 갱신일 없음 기준으로 상태별 집계한다")
	void summarizeDataQualityCountsDelayedFacilityStatusByStatus() {
		var service = new DataQualityService(new StubTransitMasterPort(
			List.of(station("station-sangnoksu", DataQualityLevel.LEVEL_1)),
			List.of(),
			List.of(
				facility("facility-recent", AccessibilityFacilityStatus.NORMAL, LocalDate.of(2026, 6, 12)),
				facility("facility-exact-threshold", AccessibilityFacilityStatus.CLOSED, LocalDate.of(2026, 5, 18)),
				facility("facility-old-broken", AccessibilityFacilityStatus.BROKEN, LocalDate.of(2026, 5, 17)),
				facility("facility-missing-date", AccessibilityFacilityStatus.UNKNOWN, null)
			)
		), FIXED_CLOCK);

		var summary = service.summarizeDataQuality();

		assertThat(summary.delayedFacilityStatusCount()).isEqualTo(2);
		assertThat(summary.delayedFacilityStatusCounts())
			.containsEntry(AccessibilityFacilityStatus.BROKEN, 1L)
			.containsEntry(AccessibilityFacilityStatus.UNKNOWN, 1L)
			.containsEntry(AccessibilityFacilityStatus.NORMAL, 0L)
			.containsEntry(AccessibilityFacilityStatus.CLOSED, 0L);
	}

	@Test
	@DisplayName("접근성 개선 우선순위는 상태, 신뢰도, 갱신 지연을 점수화해 높은 순서로 반환한다")
	void summarizeDataQualityRanksAccessibilityImprovementPriorities() {
		var service = new DataQualityService(new StubTransitMasterPort(
			List.of(station("station-sangnoksu", DataQualityLevel.LEVEL_1)),
			List.of(),
			List.of(
				facility(
					"facility-broken-low-old",
					AccessibilityFacilityStatus.BROKEN,
					LocalDate.of(2026, 5, 1),
					DataConfidenceLevel.LOW
				),
				facility(
					"facility-unknown-needs-verification",
					AccessibilityFacilityStatus.UNKNOWN,
					LocalDate.of(2026, 6, 12),
					DataConfidenceLevel.NEEDS_VERIFICATION
				),
				facility(
					"facility-construction-old",
					AccessibilityFacilityStatus.UNDER_CONSTRUCTION,
					LocalDate.of(2026, 5, 1),
					DataConfidenceLevel.HIGH
				),
				facility(
					"facility-normal-recent",
					AccessibilityFacilityStatus.NORMAL,
					LocalDate.of(2026, 6, 12),
					DataConfidenceLevel.HIGH
				)
			)
		), FIXED_CLOCK);

		var summary = service.summarizeDataQuality();

		assertThat(summary.accessibilityImprovementPriorities())
			.extracting(priority -> priority.facilityId() + ":" + priority.priorityScore())
			.containsExactly(
				"facility-broken-low-old:85",
				"facility-unknown-needs-verification:60",
				"facility-construction-old:55"
			);
		assertThat(summary.accessibilityImprovementPriorities().getFirst().reasons())
			.containsExactly("고장 상태", "낮은 신뢰도", "갱신 지연");
		assertThat(summary.accessibilityImprovementPriorities().get(1).reasons())
			.containsExactly("확인 필요 상태", "신뢰도 확인 필요");
	}

	@Test
	@DisplayName("지역별 역 품질 요약은 전체 데이터 품질 요약과 같은 역 포함 기준을 사용한다")
	void summarizeDataQualityCountsRegionStationQualityWithSameStationSet() {
		var service = new DataQualityService(new StubTransitMasterPort(
			List.of(
				station("station-active-level1", "수도권", DataQualityLevel.LEVEL_1, true),
				station("station-inactive-level4", "수도권", DataQualityLevel.LEVEL_4, false),
				station("station-busan-level2", "부산권", DataQualityLevel.LEVEL_2, true)
			),
			List.of(),
			List.of()
		));

		var summary = service.summarizeDataQuality();

		assertThat(summary.totalStations()).isEqualTo(3);
		assertThat(summary.regionSummaries()).hasSize(2);
		assertThat(summary.regionSummaries().getFirst().name()).isEqualTo("부산권");
		assertThat(summary.regionSummaries().getFirst().stationCount()).isEqualTo(1);
		assertThat(summary.regionSummaries().getFirst().stationQualityCounts())
			.containsEntry(DataQualityLevel.LEVEL_2, 1L);
		assertThat(summary.regionSummaries().getLast().name()).isEqualTo("수도권");
		assertThat(summary.regionSummaries().getLast().stationCount()).isEqualTo(2);
		assertThat(summary.regionSummaries().getLast().stationQualityCounts())
			.containsEntry(DataQualityLevel.LEVEL_1, 1L)
			.containsEntry(DataQualityLevel.LEVEL_4, 1L);
	}

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
		return station(id, "수도권", qualityLevel, true);
	}

	private static Station station(String id, String region, DataQualityLevel qualityLevel, boolean active) {
		return new Station(
			id,
			"상록수",
			"Sangnoksu",
			region,
			new BigDecimal("37.302795"),
			new BigDecimal("126.866489"),
			qualityLevel,
			DataSourceType.OFFICIAL_FILE,
			LocalDate.of(2026, 6, 12),
			active
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
			confidenceLevel,
			DataSourceType.OFFICIAL_FILE
		);
	}

	private static AccessibilityFacility facility(String id, DataConfidenceLevel confidenceLevel) {
		return facility(id, AccessibilityFacilityStatus.NORMAL, LocalDate.of(2026, 6, 12), confidenceLevel);
	}

	private static AccessibilityFacility facility(
		String id,
		AccessibilityFacilityStatus status,
		LocalDate lastUpdatedAt
	) {
		return facility(id, status, lastUpdatedAt, DataConfidenceLevel.HIGH);
	}

	private static AccessibilityFacility facility(
		String id,
		AccessibilityFacilityStatus status,
		LocalDate lastUpdatedAt,
		DataConfidenceLevel confidenceLevel
	) {
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
			status,
			confidenceLevel,
			DataSourceType.OFFICIAL_FILE,
			lastUpdatedAt
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
