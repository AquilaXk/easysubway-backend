package com.easysubway.favorite.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.favorite.domain.FavoriteFacility;
import com.easysubway.favorite.domain.InvalidFavoriteFacilityException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("JDBC 즐겨찾기 시설 저장소")
class JdbcFavoriteFacilityRepositoryTest {

	private JdbcFavoriteFacilityRepository repository;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:favorite-facilities;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		var jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS favorite_facilities");
		jdbcTemplate.execute("""
			CREATE TABLE favorite_facilities (
				user_id VARCHAR(120) NOT NULL,
				facility_id VARCHAR(120) NOT NULL,
				added_at TIMESTAMP NOT NULL,
				PRIMARY KEY (user_id, facility_id)
			)
			""");
		repository = new JdbcFavoriteFacilityRepository(jdbcTemplate);
	}

	@Test
	@DisplayName("즐겨찾기 시설을 저장하고 사용자 식별자와 시설 식별자로 조회한다")
	void saveFavoriteFacilityAndLoadByUserIdAndFacilityId() {
		var favorite = favorite("anonymous-user-1", "facility-elevator-1", 9);

		repository.saveFavoriteFacility(favorite);

		assertThat(repository.loadFavoriteFacility("anonymous-user-1", "facility-elevator-1")).contains(favorite);
		assertThat(repository.loadFavoriteFacilities("anonymous-user-1")).containsExactly(favorite);
	}

	@Test
	@DisplayName("같은 사용자와 시설 즐겨찾기는 마지막 저장 값으로 갱신한다")
	void saveFavoriteFacilityUpdatesExistingFavorite() {
		repository.saveFavoriteFacility(favorite("anonymous-user-1", "facility-elevator-1", 9));
		var updatedFavorite = favorite("anonymous-user-1", "facility-elevator-1", 10);

		repository.saveFavoriteFacility(updatedFavorite);

		assertThat(repository.loadFavoriteFacilities("anonymous-user-1")).containsExactly(updatedFavorite);
	}

	@Test
	@DisplayName("동시 저장 충돌 후 행이 사라지면 다시 삽입한다")
	void saveFavoriteFacilityRetriesInsertWhenDuplicateRetryUpdateMissesRow() {
		var jdbcTemplate = new DuplicateOnceJdbcTemplate(repositoryJdbcTemplate());
		var retryRepository = new JdbcFavoriteFacilityRepository(jdbcTemplate);
		var favorite = favorite("anonymous-user-1", "facility-elevator-1", 9);

		retryRepository.saveFavoriteFacility(favorite);

		assertThat(retryRepository.loadFavoriteFacility("anonymous-user-1", "facility-elevator-1")).contains(favorite);
	}

	@Test
	@DisplayName("사용자별 즐겨찾기 시설 목록은 추가 시각과 시설 식별자 순서로 조회한다")
	void loadFavoriteFacilitiesOrdersByAddedAtAndFacilityId() {
		var laterFavorite = favorite("anonymous-user-1", "facility-escalator-2", 10);
		var firstFavorite = favorite("anonymous-user-1", "facility-elevator-2", 9);
		var secondFavorite = favorite("anonymous-user-1", "facility-elevator-1", 9);
		repository.saveFavoriteFacility(laterFavorite);
		repository.saveFavoriteFacility(firstFavorite);
		repository.saveFavoriteFacility(secondFavorite);
		repository.saveFavoriteFacility(favorite("anonymous-user-2", "facility-elevator-1", 8));

		assertThat(repository.loadFavoriteFacilities("anonymous-user-1"))
			.containsExactly(secondFavorite, firstFavorite, laterFavorite);
	}

	@Test
	@DisplayName("시설 상태 알림 대상 사용자는 사용자 식별자 순서로 조회한다")
	void loadUserIdsByFavoriteFacilityIdReturnsSortedUserIds() {
		repository.saveFavoriteFacility(favorite("anonymous-user-2", "facility-elevator-1", 9));
		repository.saveFavoriteFacility(favorite("anonymous-user-1", "facility-elevator-1", 9));
		repository.saveFavoriteFacility(favorite("anonymous-user-3", "facility-escalator-2", 9));

		assertThat(repository.loadUserIdsByFavoriteFacilityId("facility-elevator-1"))
			.containsExactly("anonymous-user-1", "anonymous-user-2");
	}

	@Test
	@DisplayName("시설 상태 알림 대상 조회는 빈 시설 식별자를 거부한다")
	void loadUserIdsByFavoriteFacilityIdRejectsBlankFacilityId() {
		assertThatThrownBy(() -> repository.loadUserIdsByFavoriteFacilityId(""))
			.isInstanceOf(InvalidFavoriteFacilityException.class)
			.hasMessage("시설 식별자가 필요합니다.");
	}

	@Test
	@DisplayName("사용자 데이터 삭제 요청은 해당 사용자의 즐겨찾기 시설 개수를 반환한다")
	void deleteFavoriteFacilitiesByUserIdReturnsDeletedCount() {
		repository.saveFavoriteFacility(favorite("anonymous-user-1", "facility-elevator-1", 9));
		repository.saveFavoriteFacility(favorite("anonymous-user-1", "facility-escalator-2", 10));
		repository.saveFavoriteFacility(favorite("anonymous-user-2", "facility-elevator-1", 9));

		int deletedCount = repository.deleteFavoriteFacilitiesByUserId("anonymous-user-1");
		int deletedAgainCount = repository.deleteFavoriteFacilitiesByUserId("anonymous-user-1");

		assertThat(deletedCount).isEqualTo(2);
		assertThat(deletedAgainCount).isZero();
		assertThat(repository.loadFavoriteFacilities("anonymous-user-1")).isEmpty();
		assertThat(repository.loadFavoriteFacilities("anonymous-user-2"))
			.containsExactly(favorite("anonymous-user-2", "facility-elevator-1", 9));
	}

	private FavoriteFacility favorite(String userId, String facilityId, int hour) {
		return new FavoriteFacility(
			userId,
			facilityId,
			LocalDateTime.of(2026, 6, 17, hour, 0)
		);
	}

	private JdbcTemplate repositoryJdbcTemplate() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:favorite-facilities-retry;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		var jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS favorite_facilities");
		jdbcTemplate.execute("""
			CREATE TABLE favorite_facilities (
				user_id VARCHAR(120) NOT NULL,
				facility_id VARCHAR(120) NOT NULL,
				added_at TIMESTAMP NOT NULL,
				PRIMARY KEY (user_id, facility_id)
			)
			""");
		return jdbcTemplate;
	}

	private static final class DuplicateOnceJdbcTemplate extends JdbcTemplate {

		private boolean duplicateRaised;

		private DuplicateOnceJdbcTemplate(JdbcTemplate delegate) {
			super(delegate.getDataSource());
		}

		@Override
		public int update(String sql, Object... args) {
			if (!duplicateRaised && sql.contains("INSERT INTO favorite_facilities")) {
				duplicateRaised = true;
				throw new DuplicateKeyException("stale duplicate");
			}
			return super.update(sql, args);
		}
	}
}
