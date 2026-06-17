package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.adapter.out.persistence.InMemoryRouteSearchRepository;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
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
		var service = new RouteSearchDashboardService(repository);

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

	private RouteSearchResult routeSearch(
		String routeSearchId,
		MobilityType mobilityType,
		RouteSearchStatus status
	) {
		return new RouteSearchResult(
			routeSearchId,
			"station-sangnoksu",
			"상록수",
			"station-sadang",
			"사당",
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
}
