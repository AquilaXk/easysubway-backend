package com.easysubway.route.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.domain.RouteFeedback;
import com.easysubway.route.domain.RouteFeedbackRating;
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

@DisplayName("JDBC 경로 검색 저장소")
class JdbcRouteSearchRepositoryTest {

	private JdbcRouteSearchRepository repository;
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:route-searches;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS route_feedbacks");
		jdbcTemplate.execute("DROP TABLE IF EXISTS route_search_results");
		jdbcTemplate.execute("""
			CREATE TABLE route_search_results (
				route_search_id VARCHAR(120) NOT NULL PRIMARY KEY,
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
				created_at TIMESTAMP NOT NULL,
				CONSTRAINT chk_route_search_results_status CHECK (status IN ('FOUND', 'BLOCKED', 'NEEDS_VERIFICATION')),
				CONSTRAINT chk_route_search_results_mobility_type CHECK (mobility_type IN ('SENIOR', 'STROLLER', 'WHEELCHAIR', 'PREGNANT', 'TEMPORARY_INJURY', 'LUGGAGE'))
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE route_feedbacks (
				feedback_id VARCHAR(120) NOT NULL PRIMARY KEY,
				route_search_id VARCHAR(120) NOT NULL,
				user_id VARCHAR(120) NOT NULL,
				rating VARCHAR(40) NOT NULL,
				comment VARCHAR(1000) NOT NULL,
				created_at TIMESTAMP NOT NULL,
				CONSTRAINT chk_route_feedbacks_rating CHECK (rating IN ('HELPFUL', 'NOT_HELPFUL', 'BLOCKED_BY_REAL_WORLD'))
			)
			""");
		repository = new JdbcRouteSearchRepository(jdbcTemplate);
	}

	@Test
	@DisplayName("경로 검색 결과를 저장하고 JSON 상세 값을 포함해 다시 조회한다")
	void saveRouteSearchAndLoadByRouteSearchId() {
		var route = transferRouteSearch("route-search-1");

		repository.saveRouteSearch(route);

		assertThat(repository.loadRouteSearch("route-search-1")).contains(route);
	}

	@Test
	@DisplayName("같은 경로 검색 식별자는 한 행만 갱신한다")
	void saveRouteSearchUpdatesExistingRow() {
		repository.saveRouteSearch(directRouteSearch("route-search-1", "상록수", "사당"));
		var updatedRoute = transferRouteSearch("route-search-1");

		repository.saveRouteSearch(updatedRoute);

		assertThat(repository.loadRouteSearch("route-search-1")).contains(updatedRoute);
		Integer rowCount = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM route_search_results WHERE route_search_id = ?",
			Integer.class,
			"route-search-1"
		);
		assertThat(rowCount).isEqualTo(1);
	}

	@Test
	@DisplayName("경로 피드백을 저장하고 사용자 데이터 삭제 시 작성자와 코멘트를 익명화한다")
	void saveRouteFeedbackAndAnonymizeByUserId() {
		var feedback = new RouteFeedback(
			"feedback-1",
			"route-search-1",
			"anonymous-user-1",
			RouteFeedbackRating.BLOCKED_BY_REAL_WORLD,
			"실제로는 엘리베이터가 막혀 있었습니다.",
			LocalDateTime.of(2026, 6, 17, 10, 0)
		);
		repository.saveRouteFeedback(feedback);

		int anonymizedCount = repository.anonymizeRouteFeedbacksByUserId("anonymous-user-1");
		int anonymizedAgainCount = repository.anonymizeRouteFeedbacksByUserId("anonymous-user-1");

		assertThat(anonymizedCount).isEqualTo(1);
		assertThat(anonymizedAgainCount).isZero();
		assertThat(jdbcTemplate.queryForObject(
			"SELECT user_id FROM route_feedbacks WHERE feedback_id = ?",
			String.class,
			"feedback-1"
		)).isEqualTo("deleted-user");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT comment FROM route_feedbacks WHERE feedback_id = ?",
			String.class,
			"feedback-1"
		)).isEqualTo("사용자 데이터 삭제로 경로 피드백 내용이 삭제되었습니다.");
	}

	private RouteSearchResult directRouteSearch(
		String routeSearchId,
		String originStationName,
		String destinationStationName
	) {
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
				new RouteStep(3, "환승역에서 B 노선 승강장으로 환승", "환승역의 엘리베이터를 확인합니다.", "line-b", "B 노선", "station-transfer", "station-transfer", 6, 260, false, true)
			),
			List.of(new RouteWarning(RouteWarningCode.STAIR_ONLY_ACCESS, "일부 구간은 도움을 요청해야 할 수 있습니다.")),
			List.of("엘리베이터 검증이 필요한 구간이 있습니다."),
			LocalDateTime.of(2026, 6, 13, 9, 0)
		);
	}

	private String originStationId(String stationName) {
		return switch (stationName) {
			case "상록수" -> "station-sangnoksu";
			default -> "station-origin";
		};
	}

	private String destinationStationId(String stationName) {
		return switch (stationName) {
			case "사당" -> "station-sadang";
			default -> "station-destination";
		};
	}
}
