package com.easysubway.favorite.domain;

import java.time.LocalDateTime;

public record FavoriteFacility(
	String userId,
	String facilityId,
	LocalDateTime addedAt
) {

	public FavoriteFacility {
		if (userId == null || userId.isBlank()) {
			throw new InvalidFavoriteFacilityException("사용자 식별자가 필요합니다.");
		}
		if (facilityId == null || facilityId.isBlank()) {
			throw new InvalidFavoriteFacilityException("시설 식별자가 필요합니다.");
		}
		if (addedAt == null) {
			throw new InvalidFavoriteFacilityException("추가 시각이 필요합니다.");
		}
	}
}
