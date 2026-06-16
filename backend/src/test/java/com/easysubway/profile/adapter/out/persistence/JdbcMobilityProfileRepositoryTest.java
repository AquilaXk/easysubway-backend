package com.easysubway.profile.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.profile.domain.MobilityProfile;
import com.easysubway.profile.domain.MobilityType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("JDBC 이동 프로필 저장소")
class JdbcMobilityProfileRepositoryTest {

	private JdbcMobilityProfileRepository repository;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:mobility-profile;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		var jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS mobility_profiles");
		jdbcTemplate.execute("""
			CREATE TABLE mobility_profiles (
				user_id VARCHAR(120) PRIMARY KEY,
				mobility_type VARCHAR(40) NOT NULL,
				avoid_stairs BOOLEAN NOT NULL,
				require_elevator BOOLEAN NOT NULL,
				allow_escalator BOOLEAN NOT NULL,
				minimize_transfers BOOLEAN NOT NULL,
				avoid_long_walks BOOLEAN NOT NULL,
				large_text BOOLEAN NOT NULL,
				high_contrast BOOLEAN NOT NULL,
				simple_view BOOLEAN NOT NULL,
				updated_at TIMESTAMP NOT NULL
			)
			""");
		repository = new JdbcMobilityProfileRepository(jdbcTemplate);
	}

	@Test
	@DisplayName("이동 프로필을 저장하고 사용자 식별자로 조회한다")
	void saveProfileAndLoadProfileByUserId() {
		var profile = profile("anonymous-user-1", MobilityType.SENIOR, true, false);

		repository.saveProfile(profile);

		assertThat(repository.loadProfile("anonymous-user-1")).contains(profile);
	}

	@Test
	@DisplayName("같은 사용자 이동 프로필은 마지막 저장 값으로 갱신한다")
	void saveProfileUpdatesExistingProfileForSameUser() {
		repository.saveProfile(profile("anonymous-user-1", MobilityType.SENIOR, true, false));
		var updatedProfile = profile("anonymous-user-1", MobilityType.WHEELCHAIR, true, true);

		repository.saveProfile(updatedProfile);

		assertThat(repository.loadProfile("anonymous-user-1")).contains(updatedProfile);
	}

	@Test
	@DisplayName("없는 사용자 이동 프로필은 빈 결과로 조회한다")
	void loadProfileReturnsEmptyWhenProfileDoesNotExist() {
		assertThat(repository.loadProfile("missing-user")).isEmpty();
	}

	@Test
	@DisplayName("사용자 데이터 삭제 요청은 이동 프로필을 제거한다")
	void deleteMobilityProfileRemovesProfileByUserId() {
		repository.saveProfile(profile("anonymous-user-1", MobilityType.STROLLER, true, true));

		boolean deleted = repository.deleteMobilityProfile("anonymous-user-1");
		boolean deletedAgain = repository.deleteMobilityProfile("anonymous-user-1");

		assertThat(deleted).isTrue();
		assertThat(deletedAgain).isFalse();
		assertThat(repository.loadProfile("anonymous-user-1")).isEmpty();
	}

	private MobilityProfile profile(
		String userId,
		MobilityType mobilityType,
		boolean avoidStairs,
		boolean requireElevator
	) {
		return new MobilityProfile(
			userId,
			mobilityType,
			avoidStairs,
			requireElevator,
			true,
			true,
			true,
			false,
			true,
			false,
			LocalDateTime.of(2026, 6, 17, 9, 30)
		);
	}
}
