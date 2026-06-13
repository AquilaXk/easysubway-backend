package com.easysubway.favorite.application.port.in;

public record RemoveFavoriteRouteCommand(
	String userId,
	String favoriteRouteId
) {
}
