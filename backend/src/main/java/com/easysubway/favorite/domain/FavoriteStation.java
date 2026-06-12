package com.easysubway.favorite.domain;

import java.time.LocalDateTime;

public record FavoriteStation(
	String userId,
	String stationId,
	LocalDateTime addedAt
) {

	public FavoriteStation {
		if (userId == null || userId.isBlank()) {
			throw new InvalidFavoriteStationException("사용자 식별자가 필요합니다.");
		}
		if (stationId == null || stationId.isBlank()) {
			throw new InvalidFavoriteStationException("역 식별자가 필요합니다.");
		}
		if (addedAt == null) {
			throw new InvalidFavoriteStationException("추가 시각이 필요합니다.");
		}
	}
}
