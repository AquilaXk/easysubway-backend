package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.adapter.out.persistence.InMemoryRouteSearchRepository;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
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

	private RouteSearchResult routeSearch(
		String routeSearchId,
		MobilityType mobilityType,
		RouteSearchStatus status
	) {
		return routeSearch(
			routeSearchId,
			mobilityType,
			status,
			"station-sangnoksu",
			"상록수",
			"station-sadang",
			"사당"
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
			List.of(),
			List.of(),
			status == RouteSearchStatus.FOUND ? List.of() : List.of("계단 없는 역 접근 경로를 확인할 수 없습니다."),
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
