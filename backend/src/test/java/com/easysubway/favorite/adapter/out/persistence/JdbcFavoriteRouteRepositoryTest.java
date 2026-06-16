package com.easysubway.favorite.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.favorite.domain.FavoriteRoute;
import com.easysubway.favorite.domain.InvalidFavoriteRouteException;
import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteStep;
import com.easysubway.route.domain.RouteWarning;
import com.easysubway.route.domain.RouteWarningCode;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("JDBC 즐겨찾기 경로 저장소")
class JdbcFavoriteRouteRepositoryTest {

	private JdbcFavoriteRouteRepository repository;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:favorite-routes;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		var jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS favorite_route_stations");
		jdbcTemplate.execute("DROP TABLE IF EXISTS favorite_routes");
		jdbcTemplate.execute("""
			CREATE TABLE favorite_routes (
				user_id VARCHAR(120) NOT NULL,
				route_search_id VARCHAR(120) NOT NULL,
				origin_station_id VARCHAR(120) NOT NULL,
				origin_station_name VARCHAR(120) NOT NULL,
				destination_station_id VARCHAR(120) NOT NULL,
				destination_station_name VARCHAR(120) NOT NULL,
				mobility_type VARCHAR(40) NOT NULL,
				status VARCHAR(40) NOT NULL,
				line_id VARCHAR(120) NOT NULL,
				line_name VARCHAR(120) NOT NULL,
				score INTEGER NOT NULL,
				steps_json TEXT NOT NULL,
				warnings_json TEXT NOT NULL,
				blocked_reasons_json TEXT NOT NULL,
				route_created_at TIMESTAMP NOT NULL,
				added_at TIMESTAMP NOT NULL,
				PRIMARY KEY (user_id, route_search_id)
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE favorite_route_stations (
				user_id VARCHAR(120) NOT NULL,
				route_search_id VARCHAR(120) NOT NULL,
				station_id VARCHAR(120) NOT NULL,
				PRIMARY KEY (user_id, route_search_id, station_id)
			)
			""");
		repository = new JdbcFavoriteRouteRepository(jdbcTemplate);
	}

	@Test
	@DisplayName("즐겨찾기 경로를 저장하고 사용자 식별자와 경로 검색 식별자로 조회한다")
	void saveFavoriteRouteAndLoadByUserIdAndRouteSearchId() {
		var favorite = favorite("anonymous-user-1", transferRouteSearch("route-search-1"), 9);

		repository.saveFavoriteRoute(favorite);

		assertThat(repository.loadFavoriteRoute("anonymous-user-1", "route-search-1")).contains(favorite);
		assertThat(repository.loadFavoriteRoutes("anonymous-user-1")).containsExactly(favorite);
	}

	@Test
	@DisplayName("사용자별 즐겨찾기 경로 목록은 추가 시각과 경로 검색 식별자 순서로 조회한다")
	void loadFavoriteRoutesOrdersByAddedAtAndRouteSearchId() {
		var laterFavorite = favorite("anonymous-user-1", directRouteSearch("route-search-3", "사당", "상록수"), 10);
		var secondFavorite = favorite("anonymous-user-1", directRouteSearch("route-search-2", "중앙", "사당"), 9);
		var firstFavorite = favorite("anonymous-user-1", transferRouteSearch("route-search-1"), 9);
		repository.saveFavoriteRoute(laterFavorite);
		repository.saveFavoriteRoute(secondFavorite);
		repository.saveFavoriteRoute(firstFavorite);
		repository.saveFavoriteRoute(favorite("anonymous-user-2", directRouteSearch("route-search-1", "상록수", "사당"), 8));

		assertThat(repository.loadFavoriteRoutes("anonymous-user-1"))
			.containsExactly(firstFavorite, secondFavorite, laterFavorite);
	}

	@Test
	@DisplayName("경로 상태 알림 대상 사용자는 출발역과 도착역과 환승역 기준으로 조회한다")
	void loadUserIdsByRouteStationIdMatchesRouteStations() {
		repository.saveFavoriteRoute(favorite("anonymous-user-2", directRouteSearch("route-search-2", "상록수", "사당"), 9));
		repository.saveFavoriteRoute(favorite("anonymous-user-1", transferRouteSearch("route-search-1"), 9));
		repository.saveFavoriteRoute(favorite("anonymous-user-3", directRouteSearch("route-search-3", "중앙", "사당"), 9));

		assertThat(repository.loadUserIdsByRouteStationId("station-transfer"))
			.containsExactly("anonymous-user-1");
		assertThat(repository.loadUserIdsByRouteStationId("station-sadang"))
			.containsExactly("anonymous-user-2", "anonymous-user-3");
	}

	@Test
	@DisplayName("경로 상태 알림 대상 조회는 빈 역 식별자를 거부한다")
	void loadUserIdsByRouteStationIdRejectsBlankStationId() {
		assertThatThrownBy(() -> repository.loadUserIdsByRouteStationId(""))
			.isInstanceOf(InvalidFavoriteRouteException.class)
			.hasMessage("역 식별자가 필요합니다.");
	}

	@Test
	@DisplayName("같은 사용자와 경로 즐겨찾기는 마지막 저장 값으로 갱신한다")
	void saveFavoriteRouteUpdatesExistingFavorite() {
		repository.saveFavoriteRoute(favorite("anonymous-user-1", directRouteSearch("route-search-1", "상록수", "사당"), 9));
		var updatedFavorite = favorite("anonymous-user-1", transferRouteSearch("route-search-1"), 10);

		repository.saveFavoriteRoute(updatedFavorite);

		assertThat(repository.loadFavoriteRoutes("anonymous-user-1")).containsExactly(updatedFavorite);
		assertThat(repository.loadUserIdsByRouteStationId("station-transfer")).containsExactly("anonymous-user-1");
	}

	@Test
	@DisplayName("사용자 데이터 삭제 요청은 해당 사용자의 즐겨찾기 경로 개수를 반환한다")
	void deleteFavoriteRoutesByUserIdReturnsDeletedCount() {
		repository.saveFavoriteRoute(favorite("anonymous-user-1", directRouteSearch("route-search-1", "상록수", "사당"), 9));
		repository.saveFavoriteRoute(favorite("anonymous-user-1", transferRouteSearch("route-search-2"), 10));
		repository.saveFavoriteRoute(favorite("anonymous-user-2", directRouteSearch("route-search-3", "중앙", "사당"), 9));

		int deletedCount = repository.deleteFavoriteRoutesByUserId("anonymous-user-1");
		int deletedAgainCount = repository.deleteFavoriteRoutesByUserId("anonymous-user-1");

		assertThat(deletedCount).isEqualTo(2);
		assertThat(deletedAgainCount).isZero();
		assertThat(repository.loadFavoriteRoutes("anonymous-user-1")).isEmpty();
		assertThat(repository.loadFavoriteRoutes("anonymous-user-2"))
			.containsExactly(favorite("anonymous-user-2", directRouteSearch("route-search-3", "중앙", "사당"), 9));
		assertThat(repository.loadUserIdsByRouteStationId("station-transfer")).isEmpty();
	}

	private FavoriteRoute favorite(String userId, RouteSearchResult route, int hour) {
		return new FavoriteRoute(
			userId,
			route,
			LocalDateTime.of(2026, 6, 17, hour, 0)
		);
	}

	private RouteSearchResult directRouteSearch(String routeSearchId, String originStationName, String destinationStationName) {
		return new RouteSearchResult(
			routeSearchId,
			originStationId(originStationName),
			originStationName,
			destinationStationId(destinationStationName),
			destinationStationName,
			MobilityType.SENIOR,
			RouteSearchStatus.FOUND,
			"line-4",
			"수도권 4호선",
			90,
			List.of(new RouteStep(1, "4호선 이동", "열차로 이동합니다.", "line-4", "수도권 4호선", originStationId(originStationName), destinationStationId(destinationStationName), 12, 5200, false, false)),
			List.of(new RouteWarning(RouteWarningCode.LOW_DATA_CONFIDENCE, "시설 정보를 한 번 확인해 주세요.")),
			List.of(),
			LocalDateTime.of(2026, 6, 13, 9, 0)
		);
	}

	private RouteSearchResult transferRouteSearch(String routeSearchId) {
		return new RouteSearchResult(
			routeSearchId,
			"station-origin",
			"출발역",
			"station-destination",
			"도착역",
			MobilityType.WHEELCHAIR,
			RouteSearchStatus.FOUND,
			"line-a/line-b",
			"A 노선 / B 노선",
			45,
			List.of(
				new RouteStep(1, "출발역에서 A 노선 승강장으로 이동", "엘리베이터를 확인합니다.", "line-a", "A 노선", "station-origin", "station-origin", 4, 180, false, true),
				new RouteStep(2, "A 노선으로 환승역까지 이동", "2개 역을 이동한 뒤 환승합니다.", "line-a", "A 노선", "station-origin", "station-transfer", 4, 1800, false, false),
				new RouteStep(3, "환승역에서 B 노선 승강장으로 환승", "환승역의 엘리베이터를 확인합니다.", "line-b", "B 노선", "station-transfer", "station-transfer", 6, 260, false, true),
				new RouteStep(4, "B 노선으로 도착역까지 이동", "2개 역을 이동합니다.", "line-b", "B 노선", "station-transfer", "station-destination", 4, 1800, false, false)
			),
			List.of(new RouteWarning(RouteWarningCode.STAIR_ONLY_ACCESS, "일부 구간은 도움을 요청해야 할 수 있습니다.")),
			List.of(),
			LocalDateTime.of(2026, 6, 13, 9, 0)
		);
	}

	private String originStationId(String stationName) {
		return switch (stationName) {
			case "상록수" -> "station-sangnoksu";
			case "중앙" -> "station-jungang";
			default -> "station-origin";
		};
	}

	private String destinationStationId(String stationName) {
		return switch (stationName) {
			case "상록수" -> "station-sangnoksu";
			case "사당" -> "station-sadang";
			default -> "station-destination";
		};
	}
}
