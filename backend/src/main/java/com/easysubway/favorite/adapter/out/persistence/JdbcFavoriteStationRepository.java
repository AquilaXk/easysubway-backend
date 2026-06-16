package com.easysubway.favorite.adapter.out.persistence;

import com.easysubway.favorite.application.port.out.DeleteFavoriteStationPort;
import com.easysubway.favorite.application.port.out.LoadFavoriteStationAlertTargetPort;
import com.easysubway.favorite.application.port.out.LoadFavoriteStationPort;
import com.easysubway.favorite.application.port.out.SaveFavoriteStationPort;
import com.easysubway.favorite.domain.FavoriteStation;
import com.easysubway.favorite.domain.InvalidFavoriteStationException;
import com.easysubway.user.application.port.out.DeleteUserFavoriteStationPort;
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
public class JdbcFavoriteStationRepository implements
	LoadFavoriteStationPort,
	LoadFavoriteStationAlertTargetPort,
	SaveFavoriteStationPort,
	DeleteFavoriteStationPort,
	DeleteUserFavoriteStationPort {

	private final JdbcTemplate jdbcTemplate;

	public JdbcFavoriteStationRepository(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	JdbcFavoriteStationRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public List<FavoriteStation> loadFavoriteStations(String userId) {
		return jdbcTemplate.query(
			"""
				SELECT user_id, station_id, added_at
				FROM favorite_stations
				WHERE user_id = ?
				ORDER BY added_at ASC, station_id ASC
				""",
			this::mapFavoriteStation,
			userId
		);
	}

	@Override
	public Optional<FavoriteStation> loadFavoriteStation(String userId, String stationId) {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject(
				"""
					SELECT user_id, station_id, added_at
					FROM favorite_stations
					WHERE user_id = ? AND station_id = ?
					""",
				this::mapFavoriteStation,
				userId,
				stationId
			));
		} catch (EmptyResultDataAccessException exception) {
			return Optional.empty();
		}
	}

	@Override
	public List<String> loadUserIdsByFavoriteStationId(String stationId) {
		if (stationId == null || stationId.isBlank()) {
			throw new InvalidFavoriteStationException("역 식별자가 필요합니다.");
		}
		return jdbcTemplate.queryForList(
			"""
				SELECT user_id
				FROM favorite_stations
				WHERE station_id = ?
				ORDER BY user_id ASC
				""",
			String.class,
			stationId
		);
	}

	@Override
	@Transactional
	public FavoriteStation saveFavoriteStation(FavoriteStation favoriteStation) {
		if (updateFavoriteStation(favoriteStation) == 0) {
			try {
				insertFavoriteStation(favoriteStation);
			} catch (DuplicateKeyException exception) {
				// 같은 사용자가 같은 역을 동시에 저장하면 삽입 충돌 후 최신 추가 시각으로 맞춘다.
				updateFavoriteStation(favoriteStation);
			}
		}
		return favoriteStation;
	}

	@Override
	public void deleteFavoriteStation(String userId, String stationId) {
		jdbcTemplate.update(
			"""
				DELETE FROM favorite_stations
				WHERE user_id = ? AND station_id = ?
				""",
			userId,
			stationId
		);
	}

	@Override
	public int deleteFavoriteStationsByUserId(String userId) {
		return jdbcTemplate.update(
			"""
				DELETE FROM favorite_stations
				WHERE user_id = ?
				""",
			userId
		);
	}

	private int updateFavoriteStation(FavoriteStation favoriteStation) {
		return jdbcTemplate.update(
			"""
				UPDATE favorite_stations
				SET added_at = ?
				WHERE user_id = ? AND station_id = ?
				""",
			favoriteStation.addedAt(),
			favoriteStation.userId(),
			favoriteStation.stationId()
		);
	}

	private void insertFavoriteStation(FavoriteStation favoriteStation) {
		jdbcTemplate.update(
			"""
				INSERT INTO favorite_stations (
					user_id,
					station_id,
					added_at
				)
				VALUES (?, ?, ?)
				""",
			favoriteStation.userId(),
			favoriteStation.stationId(),
			favoriteStation.addedAt()
		);
	}

	private FavoriteStation mapFavoriteStation(ResultSet resultSet, int rowNumber) throws SQLException {
		return new FavoriteStation(
			resultSet.getString("user_id"),
			resultSet.getString("station_id"),
			resultSet.getTimestamp("added_at").toLocalDateTime()
		);
	}
}
