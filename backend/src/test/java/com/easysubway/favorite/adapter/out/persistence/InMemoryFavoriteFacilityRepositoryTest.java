package com.easysubway.favorite.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.favorite.domain.FavoriteFacility;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("즐겨찾기 시설 인메모리 저장소")
class InMemoryFavoriteFacilityRepositoryTest {

	@Test
	@DisplayName("사용자별 시설 즐겨찾기는 시설 식별자를 기준으로 저장하고 삭제한다")
	void favoriteFacilityRepositoryStoresAndDeletesByUserAndFacility() {
		var repository = new InMemoryFavoriteFacilityRepository();
		var favorite = new FavoriteFacility(
			"anonymous-user-1",
			"facility-sangnoksu-elevator-1",
			LocalDateTime.of(2026, 6, 12, 9, 0)
		);

		repository.saveFavoriteFacility(favorite);

		assertThat(repository.loadFavoriteFacility("anonymous-user-1", "facility-sangnoksu-elevator-1"))
			.contains(favorite);
		assertThat(repository.loadFavoriteFacilities("anonymous-user-1"))
			.containsExactly(favorite);

		repository.deleteFavoriteFacility("anonymous-user-1", "facility-sangnoksu-elevator-1");

		assertThat(repository.loadFavoriteFacilities("anonymous-user-1")).isEmpty();
	}
}
