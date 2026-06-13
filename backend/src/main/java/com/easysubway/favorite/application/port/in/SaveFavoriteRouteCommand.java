package com.easysubway.favorite.application.port.in;

public record SaveFavoriteRouteCommand(
	String userId,
	String routeSearchId
) {
}
