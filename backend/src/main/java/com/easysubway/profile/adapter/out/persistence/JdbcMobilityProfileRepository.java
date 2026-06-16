package com.easysubway.profile.adapter.out.persistence;

import com.easysubway.profile.application.port.out.LoadMobilityProfilePort;
import com.easysubway.profile.application.port.out.SaveMobilityProfilePort;
import com.easysubway.profile.domain.MobilityProfile;
import com.easysubway.profile.domain.MobilityType;
import com.easysubway.user.application.port.out.DeleteUserMobilityProfilePort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("prod")
public class JdbcMobilityProfileRepository implements
	LoadMobilityProfilePort,
	SaveMobilityProfilePort,
	DeleteUserMobilityProfilePort {

	private final JdbcTemplate jdbcTemplate;

	public JdbcMobilityProfileRepository(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	JdbcMobilityProfileRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public Optional<MobilityProfile> loadProfile(String userId) {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject(
				"""
					SELECT user_id, mobility_type, avoid_stairs, require_elevator, allow_escalator,
					       minimize_transfers, avoid_long_walks, large_text, high_contrast, simple_view, updated_at
					FROM mobility_profiles
					WHERE user_id = ?
					""",
				this::mapProfile,
				userId
			));
		} catch (EmptyResultDataAccessException exception) {
			return Optional.empty();
		}
	}

	@Override
	@Transactional
	public MobilityProfile saveProfile(MobilityProfile profile) {
		if (updateProfile(profile) == 0) {
			try {
				insertProfile(profile);
			} catch (DuplicateKeyException exception) {
				// 같은 사용자의 최초 저장이 동시에 들어오면 삽입 충돌 후 최신 값으로 수렴시킨다.
				updateProfile(profile);
			}
		}
		return profile;
	}

	private int updateProfile(MobilityProfile profile) {
		return jdbcTemplate.update("""
			UPDATE mobility_profiles
			SET mobility_type = ?,
			    avoid_stairs = ?,
			    require_elevator = ?,
			    allow_escalator = ?,
			    minimize_transfers = ?,
			    avoid_long_walks = ?,
			    large_text = ?,
			    high_contrast = ?,
			    simple_view = ?,
			    updated_at = ?
			WHERE user_id = ?
			""",
			profile.mobilityType().name(),
			profile.avoidStairs(),
			profile.requireElevator(),
			profile.allowEscalator(),
			profile.minimizeTransfers(),
			profile.avoidLongWalks(),
			profile.largeText(),
			profile.highContrast(),
			profile.simpleView(),
			profile.updatedAt(),
			profile.userId()
		);
	}

	private void insertProfile(MobilityProfile profile) {
		jdbcTemplate.update("""
			INSERT INTO mobility_profiles (
				user_id,
				mobility_type,
				avoid_stairs,
				require_elevator,
				allow_escalator,
				minimize_transfers,
				avoid_long_walks,
				large_text,
				high_contrast,
				simple_view,
				updated_at
			)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""",
			profile.userId(),
			profile.mobilityType().name(),
			profile.avoidStairs(),
			profile.requireElevator(),
			profile.allowEscalator(),
			profile.minimizeTransfers(),
			profile.avoidLongWalks(),
			profile.largeText(),
			profile.highContrast(),
			profile.simpleView(),
			profile.updatedAt()
		);
	}

	@Override
	public boolean deleteMobilityProfile(String userId) {
		return jdbcTemplate.update(
			"""
				DELETE FROM mobility_profiles
				WHERE user_id = ?
				""",
			userId
		) > 0;
	}

	private MobilityProfile mapProfile(ResultSet resultSet, int rowNumber) throws SQLException {
		return new MobilityProfile(
			resultSet.getString("user_id"),
			MobilityType.valueOf(resultSet.getString("mobility_type")),
			resultSet.getBoolean("avoid_stairs"),
			resultSet.getBoolean("require_elevator"),
			resultSet.getBoolean("allow_escalator"),
			resultSet.getBoolean("minimize_transfers"),
			resultSet.getBoolean("avoid_long_walks"),
			resultSet.getBoolean("large_text"),
			resultSet.getBoolean("high_contrast"),
			resultSet.getBoolean("simple_view"),
			resultSet.getTimestamp("updated_at").toLocalDateTime()
		);
	}
}
