package com.easysubway.route.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("인메모리 경로 검색 저장소")
class InMemoryRouteSearchRepositoryTest {

	@Test
	@DisplayName("공개 경로 검색 결과는 최대 보관 개수를 넘으면 오래된 항목부터 제거한다")
	void saveRouteSearchEvictsOldestResultWhenLimitIsExceeded() {
		var repository = new InMemoryRouteSearchRepository();

		for (int index = 0; index <= InMemoryRouteSearchRepository.MAX_STORED_ROUTE_SEARCHES; index++) {
			repository.saveRouteSearch(routeSearchResult("route-" + index));
		}

		assertThat(repository.loadRouteSearch("route-0")).isEmpty();
		assertThat(repository.loadRouteSearch("route-" + InMemoryRouteSearchRepository.MAX_STORED_ROUTE_SEARCHES))
			.isPresent();
	}

	private RouteSearchResult routeSearchResult(String routeSearchId) {
		return new RouteSearchResult(
			routeSearchId,
			"station-a",
			"출발역",
			"station-b",
			"도착역",
			MobilityType.SENIOR,
			RouteSearchStatus.FOUND,
			"line-a",
			"테스트 노선",
			10,
			List.of(),
			List.of(),
			List.of(),
			LocalDateTime.of(2026, 6, 13, 9, 0)
		);
	}
}
