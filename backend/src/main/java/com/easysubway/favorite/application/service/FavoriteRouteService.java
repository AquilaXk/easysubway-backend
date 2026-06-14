package com.easysubway.favorite.application.service;

import com.easysubway.favorite.application.port.in.FavoriteRouteUseCase;
import com.easysubway.favorite.application.port.in.ListFavoriteRoutesCommand;
import com.easysubway.favorite.application.port.in.RemoveFavoriteRouteCommand;
import com.easysubway.favorite.application.port.in.SaveFavoriteRouteCommand;
import com.easysubway.favorite.application.port.out.DeleteFavoriteRoutePort;
import com.easysubway.favorite.application.port.out.LoadFavoriteRoutePort;
import com.easysubway.favorite.application.port.out.SaveFavoriteRoutePort;
import com.easysubway.favorite.domain.FavoriteRoute;
import com.easysubway.favorite.domain.FavoriteRouteWithDetails;
import com.easysubway.favorite.domain.InvalidFavoriteRouteException;
import com.easysubway.route.application.port.out.LoadRouteSearchPort;
import com.easysubway.route.domain.RouteSearchNotFoundException;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class FavoriteRouteService implements FavoriteRouteUseCase {

	private final LoadFavoriteRoutePort loadFavoriteRoutePort;
	private final SaveFavoriteRoutePort saveFavoriteRoutePort;
	private final DeleteFavoriteRoutePort deleteFavoriteRoutePort;
	private final LoadRouteSearchPort loadRouteSearchPort;
	private final Clock clock;

	@Autowired
	public FavoriteRouteService(
		LoadFavoriteRoutePort loadFavoriteRoutePort,
		SaveFavoriteRoutePort saveFavoriteRoutePort,
		DeleteFavoriteRoutePort deleteFavoriteRoutePort,
		LoadRouteSearchPort loadRouteSearchPort
	) {
		this(
			loadFavoriteRoutePort,
			saveFavoriteRoutePort,
			deleteFavoriteRoutePort,
			loadRouteSearchPort,
			Clock.systemDefaultZone()
		);
	}

	public FavoriteRouteService(
		LoadFavoriteRoutePort loadFavoriteRoutePort,
		SaveFavoriteRoutePort saveFavoriteRoutePort,
		DeleteFavoriteRoutePort deleteFavoriteRoutePort,
		LoadRouteSearchPort loadRouteSearchPort,
		Clock clock
	) {
		this.loadFavoriteRoutePort = loadFavoriteRoutePort;
		this.saveFavoriteRoutePort = saveFavoriteRoutePort;
		this.deleteFavoriteRoutePort = deleteFavoriteRoutePort;
		this.loadRouteSearchPort = loadRouteSearchPort;
		this.clock = clock;
	}

	@Override
	public List<FavoriteRouteWithDetails> listFavoriteRoutes(ListFavoriteRoutesCommand command) {
		requireUserId(command.userId());
		return loadFavoriteRoutePort.loadFavoriteRoutes(command.userId())
			.stream()
			.sorted(Comparator.comparing(FavoriteRoute::addedAt))
			.map(this::withDetails)
			.toList();
	}

	@Override
	public FavoriteRouteWithDetails saveFavoriteRoute(SaveFavoriteRouteCommand command) {
		requireUserId(command.userId());
		requireRouteSearchId(command.routeSearchId());
		FavoriteRoute existingFavoriteRoute = loadFavoriteRoutePort
			.loadFavoriteRoute(command.userId(), command.routeSearchId())
			.orElse(null);
		if (existingFavoriteRoute != null) {
			return withDetails(existingFavoriteRoute);
		}

		RouteSearchResult route = loadRouteSearch(command.routeSearchId());
		requireSavableRoute(route);

		FavoriteRoute favoriteRoute = saveFavoriteRoutePort.saveFavoriteRoute(new FavoriteRoute(
			command.userId(),
			route,
			LocalDateTime.now(clock)
		));

		return new FavoriteRouteWithDetails(favoriteRoute, route);
	}

	@Override
	public void removeFavoriteRoute(RemoveFavoriteRouteCommand command) {
		requireUserId(command.userId());
		requireRouteSearchId(command.favoriteRouteId());
		deleteFavoriteRoutePort.deleteFavoriteRoute(command.userId(), command.favoriteRouteId());
	}

	private FavoriteRouteWithDetails withDetails(FavoriteRoute favoriteRoute) {
		return new FavoriteRouteWithDetails(favoriteRoute, favoriteRoute.route());
	}

	private RouteSearchResult loadRouteSearch(String routeSearchId) {
		// 즐겨찾기 경로는 사용자가 방금 확인한 경로 검색 결과만 저장해 오래된 임의 ID 저장을 막는다.
		return loadRouteSearchPort.loadRouteSearch(routeSearchId)
			.orElseThrow(RouteSearchNotFoundException::new);
	}

	private void requireSavableRoute(RouteSearchResult route) {
		// 실제 이동 후보가 아닌 차단 경로는 즐겨찾기와 시설 알림 대상에서 제외한다.
		if (route.status() != RouteSearchStatus.FOUND) {
			throw new InvalidFavoriteRouteException("이동 가능한 경로만 즐겨찾기에 저장할 수 있습니다.");
		}
	}

	private void requireUserId(String userId) {
		if (userId == null || userId.isBlank()) {
			throw new InvalidFavoriteRouteException("사용자 식별자가 필요합니다.");
		}
	}

	private void requireRouteSearchId(String routeSearchId) {
		if (routeSearchId == null || routeSearchId.isBlank()) {
			throw new InvalidFavoriteRouteException("경로 검색 식별자가 필요합니다.");
		}
	}
}
