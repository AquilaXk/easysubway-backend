package com.easysubway.favorite.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.favorite.adapter.out.persistence.InMemoryFavoriteRouteRepository;
import com.easysubway.favorite.application.port.in.ListFavoriteRoutesCommand;
import com.easysubway.favorite.application.port.in.RemoveFavoriteRouteCommand;
import com.easysubway.favorite.application.port.in.SaveFavoriteRouteCommand;
import com.easysubway.favorite.domain.FavoriteRoute;
import com.easysubway.favorite.domain.InvalidFavoriteRouteException;
import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.adapter.out.persistence.InMemoryRouteSearchRepository;
import com.easysubway.route.domain.RouteSearchNotFoundException;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("즐겨찾기 경로 서비스")
class FavoriteRouteServiceTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-13T00:00:00Z"), ZoneId.of("Asia/Seoul"));

	private final InMemoryFavoriteRouteRepository favoriteRouteRepository = new InMemoryFavoriteRouteRepository();
	private final InMemoryRouteSearchRepository routeSearchRepository = new InMemoryRouteSearchRepository();
	private final FavoriteRouteService service = new FavoriteRouteService(
		favoriteRouteRepository,
		favoriteRouteRepository,
		favoriteRouteRepository,
		routeSearchRepository,
		CLOCK
	);

	@Test
	@DisplayName("경로 검색 결과를 즐겨찾기 경로로 저장하고 중복 저장은 기존 항목을 반환한다")
	void saveFavoriteRouteStoresRouteSearchResultOnce() {
		routeSearchRepository.saveRouteSearch(routeSearch("route-search-1", "상록수", "사당"));

		var favorite = service.saveFavoriteRoute(new SaveFavoriteRouteCommand(
			"anonymous-user-1",
			"route-search-1"
		));
		var duplicated = service.saveFavoriteRoute(new SaveFavoriteRouteCommand(
			"anonymous-user-1",
			"route-search-1"
		));

		assertThat(favorite.favoriteRoute().userId()).isEqualTo("anonymous-user-1");
		assertThat(favorite.favoriteRoute().routeSearchId()).isEqualTo("route-search-1");
		assertThat(favorite.favoriteRoute().addedAt()).isEqualTo(LocalDateTime.of(2026, 6, 13, 9, 0));
		assertThat(favorite.route().originStationName()).isEqualTo("상록수");
		assertThat(favorite.route().destinationStationName()).isEqualTo("사당");
		assertThat(duplicated).isEqualTo(favorite);
	}

	@Test
	@DisplayName("즐겨찾기 경로 목록은 저장한 순서대로 조회한다")
	void listFavoriteRoutesReturnsSavedOrder() {
		routeSearchRepository.saveRouteSearch(routeSearch("route-search-2", "상록수", "사당"));
		routeSearchRepository.saveRouteSearch(routeSearch("route-search-3", "사당", "상록수"));

		service.saveFavoriteRoute(new SaveFavoriteRouteCommand("anonymous-user-1", "route-search-2"));
		service.saveFavoriteRoute(new SaveFavoriteRouteCommand("anonymous-user-1", "route-search-3"));

		assertThat(service.listFavoriteRoutes(new ListFavoriteRoutesCommand("anonymous-user-1")))
			.extracting(favorite -> favorite.favoriteRoute().routeSearchId())
			.containsExactly("route-search-2", "route-search-3");
	}

	@Test
	@DisplayName("즐겨찾기 경로 목록은 임시 경로 검색 캐시가 정리돼도 저장한 요약을 보여준다")
	void listFavoriteRoutesKeepsSavedRouteAfterRouteSearchEviction() {
		routeSearchRepository.saveRouteSearch(routeSearch("route-search-saved", "상록수", "사당"));
		service.saveFavoriteRoute(new SaveFavoriteRouteCommand("anonymous-user-1", "route-search-saved"));

		for (int index = 0; index <= 1_000; index++) {
			routeSearchRepository.saveRouteSearch(routeSearch("route-search-evict-" + index, "사당", "상록수"));
		}

		assertThat(service.listFavoriteRoutes(new ListFavoriteRoutesCommand("anonymous-user-1")))
			.singleElement()
			.satisfies(favorite -> {
				assertThat(favorite.favoriteRoute().routeSearchId()).isEqualTo("route-search-saved");
				assertThat(favorite.route().originStationName()).isEqualTo("상록수");
				assertThat(favorite.route().destinationStationName()).isEqualTo("사당");
			});
	}

	@Test
	@DisplayName("중복 저장은 임시 경로 검색 캐시가 정리돼도 기존 즐겨찾기를 반환한다")
	void duplicateSaveReturnsExistingFavoriteAfterRouteSearchEviction() {
		routeSearchRepository.saveRouteSearch(routeSearch("route-search-duplicate", "상록수", "사당"));
		var saved = service.saveFavoriteRoute(new SaveFavoriteRouteCommand(
			"anonymous-user-1",
			"route-search-duplicate"
		));

		for (int index = 0; index <= 1_000; index++) {
			routeSearchRepository.saveRouteSearch(routeSearch("route-search-overflow-" + index, "사당", "상록수"));
		}

		var duplicated = service.saveFavoriteRoute(new SaveFavoriteRouteCommand(
			"anonymous-user-1",
			"route-search-duplicate"
		));

		assertThat(duplicated).isEqualTo(saved);
		assertThat(duplicated.route().originStationName()).isEqualTo("상록수");
	}

	@Test
	@DisplayName("삭제 요청은 같은 사용자와 같은 경로의 즐겨찾기만 제거한다")
	void removeFavoriteRouteDeletesOnlyRequestedRoute() {
		routeSearchRepository.saveRouteSearch(routeSearch("route-search-4", "상록수", "사당"));
		routeSearchRepository.saveRouteSearch(routeSearch("route-search-5", "사당", "상록수"));
		service.saveFavoriteRoute(new SaveFavoriteRouteCommand("anonymous-user-1", "route-search-4"));
		service.saveFavoriteRoute(new SaveFavoriteRouteCommand("anonymous-user-1", "route-search-5"));

		service.removeFavoriteRoute(new RemoveFavoriteRouteCommand("anonymous-user-1", "route-search-4"));

		assertThat(service.listFavoriteRoutes(new ListFavoriteRoutesCommand("anonymous-user-1")))
			.extracting(favorite -> favorite.favoriteRoute().routeSearchId())
			.containsExactly("route-search-5");
	}

	@Test
	@DisplayName("존재하지 않는 경로 검색 결과는 즐겨찾기에 저장할 수 없다")
	void saveFavoriteRouteRequiresExistingRouteSearchResult() {
		assertThatThrownBy(() -> service.saveFavoriteRoute(new SaveFavoriteRouteCommand(
			"anonymous-user-1",
			"missing-route-search"
		)))
			.isInstanceOf(RouteSearchNotFoundException.class)
			.hasMessage("경로 검색 결과를 찾을 수 없습니다.");
	}

	@Test
	@DisplayName("차단된 경로 검색 결과는 즐겨찾기에 저장할 수 없다")
	void saveFavoriteRouteRejectsBlockedRouteSearchResult() {
		routeSearchRepository.saveRouteSearch(blockedRouteSearch("route-search-blocked"));

		assertThatThrownBy(() -> service.saveFavoriteRoute(new SaveFavoriteRouteCommand(
			"anonymous-user-1",
			"route-search-blocked"
		)))
			.isInstanceOf(InvalidFavoriteRouteException.class)
			.hasMessage("이동 가능한 경로만 즐겨찾기에 저장할 수 있습니다.");
	}

	@Test
	@DisplayName("즐겨찾기 경로 명령은 사용자와 경로 식별자를 요구한다")
	void favoriteRouteCommandsRequireUserAndRouteSearchId() {
		assertThatThrownBy(() -> service.listFavoriteRoutes(new ListFavoriteRoutesCommand("")))
			.isInstanceOf(InvalidFavoriteRouteException.class)
			.hasMessage("사용자 식별자가 필요합니다.");

		assertThatThrownBy(() -> service.saveFavoriteRoute(new SaveFavoriteRouteCommand(
			"anonymous-user-1",
			""
		)))
			.isInstanceOf(InvalidFavoriteRouteException.class)
			.hasMessage("경로 검색 식별자가 필요합니다.");
	}

	@Test
	@DisplayName("즐겨찾기 경로 도메인은 비어 있는 사용자와 경로 정보를 허용하지 않는다")
	void favoriteRouteDomainRejectsInvalidState() {
		assertThatThrownBy(() -> new FavoriteRoute("", routeSearch("route-search-1", "상록수", "사당"), LocalDateTime.now()))
			.isInstanceOf(InvalidFavoriteRouteException.class)
			.hasMessage("사용자 식별자가 필요합니다.");
		assertThatThrownBy(() -> new FavoriteRoute("anonymous-user-1", null, LocalDateTime.now()))
			.isInstanceOf(InvalidFavoriteRouteException.class)
			.hasMessage("경로 검색 결과가 필요합니다.");
		assertThatThrownBy(() -> new FavoriteRoute("anonymous-user-1", routeSearch("", "상록수", "사당"), LocalDateTime.now()))
			.isInstanceOf(InvalidFavoriteRouteException.class)
			.hasMessage("경로 검색 식별자가 필요합니다.");
		assertThatThrownBy(() -> new FavoriteRoute(
			"anonymous-user-1",
			routeSearch("route-search-1", "상록수", "사당"),
			null
		))
			.isInstanceOf(InvalidFavoriteRouteException.class)
			.hasMessage("추가 시각이 필요합니다.");
	}

	private RouteSearchResult routeSearch(String routeSearchId, String originStationName, String destinationStationName) {
		return new RouteSearchResult(
			routeSearchId,
			"station-sangnoksu",
			originStationName,
			"station-sadang",
			destinationStationName,
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

	private RouteSearchResult blockedRouteSearch(String routeSearchId) {
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
			List.of("계단 없는 역 접근 경로를 확인할 수 없습니다."),
			LocalDateTime.of(2026, 6, 13, 9, 0)
		);
	}
}
