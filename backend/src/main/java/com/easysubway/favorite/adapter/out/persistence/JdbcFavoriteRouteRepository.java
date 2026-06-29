package com.easysubway.favorite.adapter.out.persistence;

import com.easysubway.favorite.application.port.out.DeleteFavoriteRoutePort;
import com.easysubway.favorite.application.port.out.LoadFavoriteRouteAlertTargetPort;
import com.easysubway.favorite.application.port.out.LoadFavoriteRoutePort;
import com.easysubway.favorite.application.port.out.SaveFavoriteRoutePort;
import com.easysubway.favorite.domain.FavoriteRoute;
import com.easysubway.favorite.domain.InvalidFavoriteRouteException;
import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteStep;
import com.easysubway.route.domain.RouteWarning;
import com.easysubway.user.application.port.out.DeleteUserFavoriteRoutePort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("prod | staging | release | prod-like")
public class JdbcFavoriteRouteRepository implements
	LoadFavoriteRoutePort,
	LoadFavoriteRouteAlertTargetPort,
	SaveFavoriteRoutePort,
	DeleteFavoriteRoutePort,
	DeleteUserFavoriteRoutePort {

	private static final TypeReference<List<RouteStep>> ROUTE_STEPS_TYPE = new TypeReference<>() {
	};
	private static final TypeReference<List<RouteWarning>> ROUTE_WARNINGS_TYPE = new TypeReference<>() {
	};
	private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
	};

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;
	private final DatabaseDialect databaseDialect;

	@Autowired
	public JdbcFavoriteRouteRepository(DataSource dataSource, ObjectMapper objectMapper) {
		this(new JdbcTemplate(dataSource), objectMapper);
	}

	JdbcFavoriteRouteRepository(JdbcTemplate jdbcTemplate) {
		this(jdbcTemplate, new ObjectMapper().findAndRegisterModules());
	}

	JdbcFavoriteRouteRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
		this.databaseDialect = detectDatabaseDialect(jdbcTemplate);
	}

	@Override
	public List<FavoriteRoute> loadFavoriteRoutes(String userId) {
		return jdbcTemplate.query(
			"""
				SELECT user_id,
					route_search_id,
					origin_station_id,
					origin_station_name,
					destination_station_id,
					destination_station_name,
					mobility_type,
					status,
					line_id,
					line_name,
					score,
					steps_json,
					warnings_json,
					blocked_reasons_json,
					route_created_at,
					added_at
				FROM favorite_routes
				WHERE user_id = ?
				ORDER BY added_at ASC, route_search_id ASC
				""",
			this::mapFavoriteRoute,
			userId
		);
	}

	@Override
	public Optional<FavoriteRoute> loadFavoriteRoute(String userId, String routeSearchId) {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject(
				"""
					SELECT user_id,
						route_search_id,
						origin_station_id,
						origin_station_name,
						destination_station_id,
						destination_station_name,
						mobility_type,
						status,
						line_id,
						line_name,
						score,
						steps_json,
						warnings_json,
						blocked_reasons_json,
						route_created_at,
						added_at
					FROM favorite_routes
					WHERE user_id = ? AND route_search_id = ?
					""",
				this::mapFavoriteRoute,
				userId,
				routeSearchId
			));
		} catch (EmptyResultDataAccessException exception) {
			return Optional.empty();
		}
	}

	@Override
	public List<String> loadUserIdsByRouteStationId(String stationId) {
		if (stationId == null || stationId.isBlank()) {
			throw new InvalidFavoriteRouteException("역 식별자가 필요합니다.");
		}
		return jdbcTemplate.queryForList(
			"""
				SELECT DISTINCT user_id
				FROM favorite_route_stations
				WHERE station_id = ?
				ORDER BY user_id ASC
				""",
			String.class,
			stationId
		);
	}

	@Override
	@Transactional
	public FavoriteRoute saveFavoriteRoute(FavoriteRoute favoriteRoute) {
		upsertFavoriteRoute(favoriteRoute);
		replaceRouteStations(favoriteRoute);
		return favoriteRoute;
	}

	@Override
	@Transactional
	public void deleteFavoriteRoute(String userId, String routeSearchId) {
		deleteRouteStations(userId, routeSearchId);
		jdbcTemplate.update(
			"""
				DELETE FROM favorite_routes
				WHERE user_id = ? AND route_search_id = ?
				""",
			userId,
			routeSearchId
		);
	}

	@Override
	@Transactional
	public int deleteFavoriteRoutesByUserId(String userId) {
		Integer favoriteRouteCount = jdbcTemplate.queryForObject(
			"""
				SELECT COUNT(*)
				FROM favorite_routes
				WHERE user_id = ?
				""",
			Integer.class,
			userId
		);
		jdbcTemplate.update(
			"""
				DELETE FROM favorite_route_stations
				WHERE user_id = ?
				""",
			userId
		);
		jdbcTemplate.update(
			"""
				DELETE FROM favorite_routes
				WHERE user_id = ?
				""",
			userId
		);
		return favoriteRouteCount == null ? 0 : favoriteRouteCount;
	}

	private void upsertFavoriteRoute(FavoriteRoute favoriteRoute) {
		if (databaseDialect == DatabaseDialect.H2) {
			upsertFavoriteRouteWithH2Merge(favoriteRoute);
			return;
		}
		upsertFavoriteRouteWithPostgresql(favoriteRoute);
	}

	private void upsertFavoriteRouteWithPostgresql(FavoriteRoute favoriteRoute) {
		// PostgreSQL은 중복 키 예외 뒤 같은 트랜잭션 재시도가 불가능하므로 단일 upsert로 저장한다.
		jdbcTemplate.update(
			"""
				INSERT INTO favorite_routes (
					user_id,
					route_search_id,
					origin_station_id,
					origin_station_name,
					destination_station_id,
					destination_station_name,
					mobility_type,
					status,
					line_id,
					line_name,
					score,
					steps_json,
					warnings_json,
					blocked_reasons_json,
					route_created_at,
					added_at
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (user_id, route_search_id) DO UPDATE
				SET origin_station_id = EXCLUDED.origin_station_id,
					origin_station_name = EXCLUDED.origin_station_name,
					destination_station_id = EXCLUDED.destination_station_id,
					destination_station_name = EXCLUDED.destination_station_name,
					mobility_type = EXCLUDED.mobility_type,
					status = EXCLUDED.status,
					line_id = EXCLUDED.line_id,
					line_name = EXCLUDED.line_name,
					score = EXCLUDED.score,
					steps_json = EXCLUDED.steps_json,
					warnings_json = EXCLUDED.warnings_json,
					blocked_reasons_json = EXCLUDED.blocked_reasons_json,
					route_created_at = EXCLUDED.route_created_at,
					added_at = EXCLUDED.added_at
				""",
			favoriteRouteParameters(favoriteRoute)
		);
	}

	private void upsertFavoriteRouteWithH2Merge(FavoriteRoute favoriteRoute) {
		jdbcTemplate.update(
			"""
				MERGE INTO favorite_routes (
					user_id,
					route_search_id,
					origin_station_id,
					origin_station_name,
					destination_station_id,
					destination_station_name,
					mobility_type,
					status,
					line_id,
					line_name,
					score,
					steps_json,
					warnings_json,
					blocked_reasons_json,
					route_created_at,
					added_at
				)
				KEY (user_id, route_search_id)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
			favoriteRouteParameters(favoriteRoute)
		);
	}

	private Object[] favoriteRouteParameters(FavoriteRoute favoriteRoute) {
		RouteSearchResult route = favoriteRoute.route();
		return new Object[] {
			favoriteRoute.userId(),
			favoriteRoute.routeSearchId(),
			route.originStationId(),
			route.originStationName(),
			route.destinationStationId(),
			route.destinationStationName(),
			route.mobilityType().name(),
			route.status().name(),
			route.lineId(),
			route.lineName(),
			route.score(),
			writeJson(route.steps()),
			writeJson(route.warnings()),
			writeJson(route.blockedReasons()),
			route.createdAt(),
			favoriteRoute.addedAt()
		};
	}

	private void replaceRouteStations(FavoriteRoute favoriteRoute) {
		deleteRouteStations(favoriteRoute.userId(), favoriteRoute.routeSearchId());
		// 알림 대상 조회는 경로 본문 JSON을 매번 파싱하지 않도록 역 식별자만 별도 인덱스로 유지한다.
		for (String stationId : routeStationIds(favoriteRoute.route())) {
			jdbcTemplate.update(
				"""
					INSERT INTO favorite_route_stations (
						user_id,
						route_search_id,
						station_id
					)
					VALUES (?, ?, ?)
					""",
				favoriteRoute.userId(),
				favoriteRoute.routeSearchId(),
				stationId
			);
		}
	}

	private void deleteRouteStations(String userId, String routeSearchId) {
		jdbcTemplate.update(
			"""
				DELETE FROM favorite_route_stations
				WHERE user_id = ? AND route_search_id = ?
				""",
			userId,
			routeSearchId
		);
	}

	private Set<String> routeStationIds(RouteSearchResult route) {
		Set<String> stationIds = new LinkedHashSet<>();
		addStationId(stationIds, route.originStationId());
		addStationId(stationIds, route.destinationStationId());
		for (RouteStep step : route.steps()) {
			addStationId(stationIds, step.fromStationId());
			addStationId(stationIds, step.toStationId());
		}
		return stationIds;
	}

	private void addStationId(Set<String> stationIds, String stationId) {
		if (stationId != null && !stationId.isBlank()) {
			stationIds.add(stationId);
		}
	}

	private FavoriteRoute mapFavoriteRoute(ResultSet resultSet, int rowNumber) throws SQLException {
		RouteSearchResult route = new RouteSearchResult(
			resultSet.getString("route_search_id"),
			resultSet.getString("origin_station_id"),
			resultSet.getString("origin_station_name"),
			resultSet.getString("destination_station_id"),
			resultSet.getString("destination_station_name"),
			MobilityType.valueOf(resultSet.getString("mobility_type")),
			RouteSearchStatus.valueOf(resultSet.getString("status")),
			resultSet.getString("line_id"),
			resultSet.getString("line_name"),
			resultSet.getInt("score"),
			readJson(resultSet.getString("steps_json"), ROUTE_STEPS_TYPE),
			readJson(resultSet.getString("warnings_json"), ROUTE_WARNINGS_TYPE),
			readJson(resultSet.getString("blocked_reasons_json"), STRING_LIST_TYPE),
			resultSet.getTimestamp("route_created_at").toLocalDateTime()
		);
		return new FavoriteRoute(
			resultSet.getString("user_id"),
			route,
			resultSet.getTimestamp("added_at").toLocalDateTime()
		);
	}

	private String writeJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("즐겨찾기 경로 JSON 저장값을 만들지 못했습니다.", exception);
		}
	}

	private <T> T readJson(String json, TypeReference<T> typeReference) {
		try {
			return objectMapper.readValue(json, typeReference);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("즐겨찾기 경로 JSON 저장값을 읽지 못했습니다.", exception);
		}
	}

	private DatabaseDialect detectDatabaseDialect(JdbcTemplate jdbcTemplate) {
		DatabaseDialect dialect = jdbcTemplate.execute((ConnectionCallback<DatabaseDialect>) connection -> {
			String productName = connection.getMetaData().getDatabaseProductName();
			return "H2".equalsIgnoreCase(productName) ? DatabaseDialect.H2 : DatabaseDialect.POSTGRESQL;
		});
		return dialect == null ? DatabaseDialect.POSTGRESQL : dialect;
	}

	private enum DatabaseDialect {
		POSTGRESQL,
		H2
	}
}
