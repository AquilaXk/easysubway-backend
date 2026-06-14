package com.easysubway.favorite.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.favorite.domain.FavoriteRoute;
import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteStep;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("즐겨찾기 경로 인메모리 저장소")
class InMemoryFavoriteRouteRepositoryTest {

	@Test
	@DisplayName("사용자별 즐겨찾기 경로는 보관 한도를 넘기면 가장 오래된 항목부터 정리한다")
	void saveFavoriteRouteEvictsOldestRouteWhenLimitExceeded() {
		var repository = new InMemoryFavoriteRouteRepository();

		for (int index = 0; index <= InMemoryFavoriteRouteRepository.MAX_FAVORITE_ROUTES_PER_USER; index++) {
			repository.saveFavoriteRoute(new FavoriteRoute(
				"anonymous-user-1",
				routeSearch("route-search-" + index),
				LocalDateTime.of(2026, 6, 13, 9, 0).plusMinutes(index)
			));
		}

		assertThat(repository.loadFavoriteRoutes("anonymous-user-1"))
			.hasSize(InMemoryFavoriteRouteRepository.MAX_FAVORITE_ROUTES_PER_USER)
			.extracting(FavoriteRoute::routeSearchId)
			.doesNotContain("route-search-0")
			.contains("route-search-1", "route-search-" + InMemoryFavoriteRouteRepository.MAX_FAVORITE_ROUTES_PER_USER);
	}

	@Test
	@DisplayName("경로 알림 대상 조회는 환승 단계에 포함된 역도 매칭한다")
	void loadUserIdsByRouteStationIdMatchesTransferStepStation() {
		var repository = new InMemoryFavoriteRouteRepository();
		repository.saveFavoriteRoute(new FavoriteRoute(
			"route-user",
			transferRouteSearch(),
			LocalDateTime.of(2026, 6, 13, 9, 0)
		));

		assertThat(repository.loadUserIdsByRouteStationId("station-transfer"))
			.containsExactly("route-user");
	}

	private RouteSearchResult routeSearch(String routeSearchId) {
		return new RouteSearchResult(
			routeSearchId,
			"station-sangnoksu",
			"상록수",
			"station-sadang",
			"사당",
			MobilityType.SENIOR,
			RouteSearchStatus.FOUND,
			"line-4",
			"수도권 4호선",
			90,
			List.of(),
			List.of(),
			List.of(),
			LocalDateTime.of(2026, 6, 13, 9, 0)
		);
	}

	private RouteSearchResult transferRouteSearch() {
		return new RouteSearchResult(
			"route-search-transfer",
			"station-origin",
			"출발역",
			"station-destination",
			"도착역",
			MobilityType.WHEELCHAIR,
			RouteSearchStatus.FOUND,
			"line-a/line-b",
			"A 노선 / B 노선",
			45,
			List.of(
				new RouteStep(1, "출발역에서 A 노선 승강장으로 이동", "엘리베이터를 확인합니다.", "line-a", "A 노선", "station-origin", "station-origin"),
				new RouteStep(2, "A 노선으로 환승역까지 이동", "2개 역을 이동한 뒤 환승합니다.", "line-a", "A 노선", "station-origin", "station-transfer"),
				new RouteStep(3, "환승역에서 B 노선 승강장으로 환승", "환승역의 엘리베이터를 확인합니다.", "line-b", "B 노선", "station-transfer", "station-transfer"),
				new RouteStep(4, "B 노선으로 도착역까지 이동", "2개 역을 이동합니다.", "line-b", "B 노선", "station-transfer", "station-destination")
			),
			List.of(),
			List.of(),
			LocalDateTime.of(2026, 6, 13, 9, 0)
		);
	}
}
