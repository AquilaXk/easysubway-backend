package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.adapter.out.persistence.InMemoryRouteSearchRepository;
import com.easysubway.route.domain.EtaSource;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteStep;
import com.easysubway.route.domain.RouteWarning;
import com.easysubway.route.domain.RouteWarningCode;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("경로 검색 현황 서비스")
class RouteSearchDashboardServiceTest {

	@Test
	@DisplayName("전체 경로 검색을 상태와 이동 프로필별로 집계한다")
	void summarizeRouteSearchesByStatusAndMobilityType() {
		var repository = new InMemoryRouteSearchRepository();
		repository.saveRouteSearch(routeSearch("route-search-1", MobilityType.SENIOR, RouteSearchStatus.FOUND));
		repository.saveRouteSearch(routeSearch("route-search-2", MobilityType.WHEELCHAIR, RouteSearchStatus.FOUND));
		repository.saveRouteSearch(routeSearch("route-search-3", MobilityType.WHEELCHAIR, RouteSearchStatus.BLOCKED));
		var service = new RouteSearchDashboardService(repository, new FakeTransitMasterPort());

		var summary = service.summarizeRouteSearches();

		assertThat(summary.totalCount()).isEqualTo(3);
		assertThat(summary.foundCount()).isEqualTo(2);
		assertThat(summary.blockedCount()).isEqualTo(1);
		assertThat(summary.mobilityTypeCounts())
			.extracting("mobilityType", "count")
			.containsExactly(
				tuple(MobilityType.SENIOR, 1L),
				tuple(MobilityType.WHEELCHAIR, 2L)
			);
	}

	@Test
	@DisplayName("출발역과 도착역의 지역 기준으로 검색 사용량을 집계한다")
	void summarizeRouteSearchesByOriginAndDestinationRegion() {
		var repository = new InMemoryRouteSearchRepository();
		repository.saveRouteSearch(routeSearch(
			"route-search-1",
			MobilityType.SENIOR,
			RouteSearchStatus.FOUND,
			"station-sangnoksu",
			"상록수",
			"station-sadang",
			"사당"
		));
		repository.saveRouteSearch(routeSearch(
			"route-search-2",
			MobilityType.WHEELCHAIR,
			RouteSearchStatus.FOUND,
			"station-busan",
			"부산",
			"station-sangnoksu",
			"상록수"
		));
		repository.saveRouteSearch(routeSearch(
			"route-search-3",
			MobilityType.PREGNANT,
			RouteSearchStatus.BLOCKED,
			"station-null-region",
			"미확인",
			"station-busan",
			"부산"
		));
		var service = new RouteSearchDashboardService(repository, new FakeTransitMasterPort());

		var summary = service.summarizeRouteSearches();

		assertThat(summary.regionUsageCounts())
			.extracting("region", "originCount", "destinationCount")
			.containsExactly(
				tuple("수도권", 1L, 2L),
				tuple("부산권", 1L, 1L)
			);
	}

	@Test
	@DisplayName("경로 검색 차단 사유를 빈도순으로 집계한다")
	void summarizeRouteSearchesByBlockedReason() {
		var repository = new InMemoryRouteSearchRepository();
		repository.saveRouteSearch(blockedRouteSearch(
			"route-search-1",
			"엘리베이터 없는 출입구만 확인됩니다.",
			"휠체어로 이동 가능한 환승 동선을 확인할 수 없습니다."
		));
		repository.saveRouteSearch(blockedRouteSearch(
			"route-search-2",
			"엘리베이터 없는 출입구만 확인됩니다.",
			" "
		));
		repository.saveRouteSearch(routeSearch("route-search-3", MobilityType.SENIOR, RouteSearchStatus.FOUND));
		var service = new RouteSearchDashboardService(repository, new FakeTransitMasterPort());

		var summary = service.summarizeRouteSearches();

		assertThat(summary.blockedReasonCounts())
			.extracting("reason", "count")
			.containsExactly(
				tuple("엘리베이터 없는 출입구만 확인됩니다.", 2L),
				tuple("휠체어로 이동 가능한 환승 동선을 확인할 수 없습니다.", 1L)
			);
	}

	@Test
	@DisplayName("route quality 운영 신호를 낮은 cardinality 값으로 집계한다")
	void summarizeRouteQualitySignalsForOperations() {
		var repository = new InMemoryRouteSearchRepository();
		repository.saveRouteSearch(routeSearch(
			"route-search-1",
			MobilityType.SENIOR,
			RouteSearchStatus.FOUND,
			List.of(routeStep(EtaSource.FALLBACK)),
			List.of(new RouteWarning(RouteWarningCode.LOW_DATA_CONFIDENCE))
		));
		repository.saveRouteSearch(routeSearch(
			"route-search-2",
			MobilityType.WHEELCHAIR,
			RouteSearchStatus.BLOCKED,
			List.of(),
			List.of(new RouteWarning(RouteWarningCode.STAIR_ONLY_ACCESS))
		));
		var service = new RouteSearchDashboardService(repository, new FakeTransitMasterPort());

		var summary = service.summarizeRouteSearches();

		assertThat(summary.etaSourceCounts())
			.extracting("etaSource", "count")
			.containsExactly(tuple(EtaSource.FALLBACK, 1L));
		assertThat(summary.fallbackReasonCounts())
			.extracting("reason", "count")
			.contains(
				tuple("PROVIDER_OUTAGE_OR_STALE_REALTIME", 1L),
				tuple("ROUTE_GRAPH_OR_STRICT_ACCESSIBILITY_BLOCK", 1L),
				tuple("LOW_DATA_CONFIDENCE", 1L),
				tuple("STRICT_STAIR_ONLY_ACCESS", 1L)
			);
		assertThat(summary.routeQualitySignalCounts())
			.extracting("signal", "count")
			.contains(
				tuple("PROVIDER_OUTAGE", 1L),
				tuple("ROUTE_GRAPH_DATA_QUALITY", 2L),
				tuple("STRICT_ACCESSIBILITY_BLOCK", 1L)
			);
	}

	private RouteSearchResult routeSearch(
		String routeSearchId,
		MobilityType mobilityType,
		RouteSearchStatus status
	) {
		return routeSearch(routeSearchId, mobilityType, status, List.of(), List.of());
	}

	private RouteSearchResult routeSearch(
		String routeSearchId,
		MobilityType mobilityType,
		RouteSearchStatus status,
		List<RouteStep> steps,
		List<RouteWarning> warnings
	) {
		return routeSearch(
			routeSearchId,
			mobilityType,
			status,
			"station-sangnoksu",
			"상록수",
			"station-sadang",
			"사당",
			steps,
			warnings
		);
	}

	private RouteSearchResult routeSearch(
		String routeSearchId,
		MobilityType mobilityType,
		RouteSearchStatus status,
		String originStationId,
		String originStationName,
		String destinationStationId,
		String destinationStationName
	) {
		return routeSearch(
			routeSearchId,
			mobilityType,
			status,
			originStationId,
			originStationName,
			destinationStationId,
			destinationStationName,
			List.of(),
			List.of()
		);
	}

	private RouteSearchResult routeSearch(
		String routeSearchId,
		MobilityType mobilityType,
		RouteSearchStatus status,
		String originStationId,
		String originStationName,
		String destinationStationId,
		String destinationStationName,
		List<RouteStep> steps,
		List<RouteWarning> warnings
	) {
		return new RouteSearchResult(
			routeSearchId,
			originStationId,
			originStationName,
			destinationStationId,
			destinationStationName,
			mobilityType,
			status,
			"line-4",
			"수도권 4호선",
			status == RouteSearchStatus.FOUND ? 90 : 0,
			steps,
			warnings,
			status == RouteSearchStatus.FOUND ? List.of() : List.of("계단 없는 역 접근 경로를 확인할 수 없습니다."),
			LocalDateTime.of(2026, 6, 17, 10, 0)
		);
	}

	private RouteStep routeStep(EtaSource etaSource) {
		return new RouteStep(
			1,
			"ride",
			"상록수에서 사당까지 이동",
			"수도권 4호선을 이용합니다.",
			"line-4",
			"수도권 4호선",
			"station-sangnoksu",
			"station-sadang",
			24,
			15000,
			false,
			"VERIFIED_STEP_FREE",
			false,
			etaSource.name(),
			"ESTIMATED_CONSTANT",
			"낮음"
		);
	}

	private RouteSearchResult blockedRouteSearch(String routeSearchId, String... blockedReasons) {
		return new RouteSearchResult(
			routeSearchId,
			"station-sangnoksu",
			"상록수",
			"station-sadang",
			"사당",
			MobilityType.WHEELCHAIR,
			RouteSearchStatus.BLOCKED,
			"line-4",
			"수도권 4호선",
			0,
			List.of(),
			List.of(),
			List.of(blockedReasons),
			LocalDateTime.of(2026, 6, 17, 10, 0)
		);
	}

	private static final class FakeTransitMasterPort implements LoadTransitMasterPort {

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
			return List.of(
				station("station-sangnoksu", "상록수", "수도권"),
				station("station-sadang", "사당", "수도권"),
				station("station-busan", "부산", "부산권"),
				station("station-null-region", "미확인", null)
			);
		}

		@Override
		public List<StationLine> loadStationLines() {
			return List.of();
		}

		@Override
		public List<StationExit> loadStationExits() {
			return List.of();
		}

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of();
		}

		private Station station(String id, String name, String region) {
			return new Station(
				id,
				name,
				name,
				region,
				BigDecimal.ZERO,
				BigDecimal.ZERO,
				DataQualityLevel.LEVEL_1,
				DataSourceType.OFFICIAL_FILE,
				LocalDate.of(2026, 6, 17),
				true
			);
		}
	}
}
