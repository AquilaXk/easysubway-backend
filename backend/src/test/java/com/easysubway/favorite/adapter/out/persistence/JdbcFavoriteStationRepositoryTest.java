package com.easysubway.favorite.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.favorite.domain.FavoriteStation;
import com.easysubway.favorite.domain.InvalidFavoriteStationException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("JDBC 즐겨찾기 역 저장소")
class JdbcFavoriteStationRepositoryTest {

	private JdbcFavoriteStationRepository repository;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:favorite-stations;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		var jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS favorite_stations");
		jdbcTemplate.execute("""
			CREATE TABLE favorite_stations (
				user_id VARCHAR(120) NOT NULL,
				station_id VARCHAR(120) NOT NULL,
				added_at TIMESTAMP NOT NULL,
				PRIMARY KEY (user_id, station_id)
			)
			""");
		repository = new JdbcFavoriteStationRepository(jdbcTemplate);
	}

	@Test
	@DisplayName("즐겨찾기 역을 저장하고 사용자 식별자와 역 식별자로 조회한다")
	void saveFavoriteStationAndLoadByUserIdAndStationId() {
		var favorite = favorite("anonymous-user-1", "station-sangnoksu", 9);

		repository.saveFavoriteStation(favorite);

		assertThat(repository.loadFavoriteStation("anonymous-user-1", "station-sangnoksu")).contains(favorite);
		assertThat(repository.loadFavoriteStations("anonymous-user-1")).containsExactly(favorite);
	}

	@Test
	@DisplayName("같은 사용자와 역 즐겨찾기는 마지막 저장 값으로 갱신한다")
	void saveFavoriteStationUpdatesExistingFavorite() {
		repository.saveFavoriteStation(favorite("anonymous-user-1", "station-sangnoksu", 9));
		var updatedFavorite = favorite("anonymous-user-1", "station-sangnoksu", 10);

		repository.saveFavoriteStation(updatedFavorite);

		assertThat(repository.loadFavoriteStations("anonymous-user-1")).containsExactly(updatedFavorite);
	}

	@Test
	@DisplayName("사용자별 즐겨찾기 역 목록은 추가 시각과 역 식별자 순서로 조회한다")
	void loadFavoriteStationsOrdersByAddedAtAndStationId() {
		var laterFavorite = favorite("anonymous-user-1", "station-sadang", 10);
		var firstFavorite = favorite("anonymous-user-1", "station-sangnoksu", 9);
		var secondFavorite = favorite("anonymous-user-1", "station-banwol", 9);
		repository.saveFavoriteStation(laterFavorite);
		repository.saveFavoriteStation(firstFavorite);
		repository.saveFavoriteStation(secondFavorite);
		repository.saveFavoriteStation(favorite("anonymous-user-2", "station-sangnoksu", 8));

		assertThat(repository.loadFavoriteStations("anonymous-user-1"))
			.containsExactly(secondFavorite, firstFavorite, laterFavorite);
	}

	@Test
	@DisplayName("역 시설 알림 대상 사용자는 사용자 식별자 순서로 조회한다")
	void loadUserIdsByFavoriteStationIdReturnsSortedUserIds() {
		repository.saveFavoriteStation(favorite("anonymous-user-2", "station-sangnoksu", 9));
		repository.saveFavoriteStation(favorite("anonymous-user-1", "station-sangnoksu", 9));
		repository.saveFavoriteStation(favorite("anonymous-user-3", "station-sadang", 9));

		assertThat(repository.loadUserIdsByFavoriteStationId("station-sangnoksu"))
			.containsExactly("anonymous-user-1", "anonymous-user-2");
	}

	@Test
	@DisplayName("역 시설 알림 대상 조회는 빈 역 식별자를 거부한다")
	void loadUserIdsByFavoriteStationIdRejectsBlankStationId() {
		assertThatThrownBy(() -> repository.loadUserIdsByFavoriteStationId(""))
			.isInstanceOf(InvalidFavoriteStationException.class)
			.hasMessage("역 식별자가 필요합니다.");
	}

	@Test
	@DisplayName("사용자 데이터 삭제 요청은 해당 사용자의 즐겨찾기 역 개수를 반환한다")
	void deleteFavoriteStationsByUserIdReturnsDeletedCount() {
		repository.saveFavoriteStation(favorite("anonymous-user-1", "station-sangnoksu", 9));
		repository.saveFavoriteStation(favorite("anonymous-user-1", "station-sadang", 10));
		repository.saveFavoriteStation(favorite("anonymous-user-2", "station-sangnoksu", 9));

		int deletedCount = repository.deleteFavoriteStationsByUserId("anonymous-user-1");
		int deletedAgainCount = repository.deleteFavoriteStationsByUserId("anonymous-user-1");

		assertThat(deletedCount).isEqualTo(2);
		assertThat(deletedAgainCount).isZero();
		assertThat(repository.loadFavoriteStations("anonymous-user-1")).isEmpty();
		assertThat(repository.loadFavoriteStations("anonymous-user-2"))
			.containsExactly(favorite("anonymous-user-2", "station-sangnoksu", 9));
	}

	private FavoriteStation favorite(String userId, String stationId, int hour) {
		return new FavoriteStation(
			userId,
			stationId,
			LocalDateTime.of(2026, 6, 17, hour, 0)
		);
	}
}
