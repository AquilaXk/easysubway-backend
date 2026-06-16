package com.easysubway.favorite.adapter.out.persistence;

import com.easysubway.favorite.application.port.out.DeleteFavoriteFacilityPort;
import com.easysubway.favorite.application.port.out.LoadFavoriteFacilityAlertTargetPort;
import com.easysubway.favorite.application.port.out.LoadFavoriteFacilityPort;
import com.easysubway.favorite.application.port.out.SaveFavoriteFacilityPort;
import com.easysubway.favorite.domain.FavoriteFacility;
import com.easysubway.favorite.domain.InvalidFavoriteFacilityException;
import com.easysubway.user.application.port.out.DeleteUserFavoriteFacilityPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
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
public class JdbcFavoriteFacilityRepository implements
	LoadFavoriteFacilityPort,
	LoadFavoriteFacilityAlertTargetPort,
	SaveFavoriteFacilityPort,
	DeleteFavoriteFacilityPort,
	DeleteUserFavoriteFacilityPort {

	private final JdbcTemplate jdbcTemplate;

	public JdbcFavoriteFacilityRepository(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	JdbcFavoriteFacilityRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public List<FavoriteFacility> loadFavoriteFacilities(String userId) {
		return jdbcTemplate.query(
			"""
				SELECT user_id, facility_id, added_at
				FROM favorite_facilities
				WHERE user_id = ?
				ORDER BY added_at ASC, facility_id ASC
				""",
			this::mapFavoriteFacility,
			userId
		);
	}

	@Override
	public Optional<FavoriteFacility> loadFavoriteFacility(String userId, String facilityId) {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject(
				"""
					SELECT user_id, facility_id, added_at
					FROM favorite_facilities
					WHERE user_id = ? AND facility_id = ?
					""",
				this::mapFavoriteFacility,
				userId,
				facilityId
			));
		} catch (EmptyResultDataAccessException exception) {
			return Optional.empty();
		}
	}

	@Override
	public List<String> loadUserIdsByFavoriteFacilityId(String facilityId) {
		if (facilityId == null || facilityId.isBlank()) {
			throw new InvalidFavoriteFacilityException("시설 식별자가 필요합니다.");
		}
		return jdbcTemplate.queryForList(
			"""
				SELECT user_id
				FROM favorite_facilities
				WHERE facility_id = ?
				ORDER BY user_id ASC
				""",
			String.class,
			facilityId
		);
	}

	@Override
	@Transactional
	public FavoriteFacility saveFavoriteFacility(FavoriteFacility favoriteFacility) {
		if (updateFavoriteFacility(favoriteFacility) == 0) {
			try {
				insertFavoriteFacility(favoriteFacility);
			} catch (DuplicateKeyException exception) {
				// 같은 사용자가 같은 시설을 동시에 저장하면 삽입 충돌 후 최신 추가 시각으로 맞춘다.
				updateFavoriteFacility(favoriteFacility);
			}
		}
		return favoriteFacility;
	}

	@Override
	public void deleteFavoriteFacility(String userId, String facilityId) {
		jdbcTemplate.update(
			"""
				DELETE FROM favorite_facilities
				WHERE user_id = ? AND facility_id = ?
				""",
			userId,
			facilityId
		);
	}

	@Override
	public int deleteFavoriteFacilitiesByUserId(String userId) {
		return jdbcTemplate.update(
			"""
				DELETE FROM favorite_facilities
				WHERE user_id = ?
				""",
			userId
		);
	}

	private int updateFavoriteFacility(FavoriteFacility favoriteFacility) {
		return jdbcTemplate.update(
			"""
				UPDATE favorite_facilities
				SET added_at = ?
				WHERE user_id = ? AND facility_id = ?
				""",
			favoriteFacility.addedAt(),
			favoriteFacility.userId(),
			favoriteFacility.facilityId()
		);
	}

	private void insertFavoriteFacility(FavoriteFacility favoriteFacility) {
		jdbcTemplate.update(
			"""
				INSERT INTO favorite_facilities (
					user_id,
					facility_id,
					added_at
				)
				VALUES (?, ?, ?)
				""",
			favoriteFacility.userId(),
			favoriteFacility.facilityId(),
			favoriteFacility.addedAt()
		);
	}

	private FavoriteFacility mapFavoriteFacility(ResultSet resultSet, int rowNumber) throws SQLException {
		return new FavoriteFacility(
			resultSet.getString("user_id"),
			resultSet.getString("facility_id"),
			resultSet.getTimestamp("added_at").toLocalDateTime()
		);
	}
}
