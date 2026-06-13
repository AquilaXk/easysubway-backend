package com.easysubway.favorite.domain;

import com.easysubway.route.domain.RouteSearchResult;

public record FavoriteRouteWithDetails(
	FavoriteRoute favoriteRoute,
	RouteSearchResult route
) {
}
