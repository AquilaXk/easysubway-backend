package com.easysubway.route.adapter.out.persistence;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.out.LoadRouteSearchPort;
import com.easysubway.route.application.port.out.SaveRouteFeedbackPort;
import com.easysubway.route.application.port.out.SaveRouteSearchPort;
import com.easysubway.route.domain.RouteFeedback;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteStep;
import com.easysubway.route.domain.RouteWarning;
import com.easysubway.user.application.port.out.AnonymizeUserRouteFeedbackPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("prod")
public class JdbcRouteSearchRepository
	implements LoadRouteSearchPort, SaveRouteSearchPort, SaveRouteFeedbackPort, AnonymizeUserRouteFeedbackPort {

	private static final TypeReference<List<RouteStep>> ROUTE_STEPS_TYPE = new TypeReference<>() {
	};
	private static final TypeReference<List<RouteWarning>> ROUTE_WARNINGS_TYPE = new TypeReference<>() {
	};
	private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
	};
	private static final String DELETED_USER_ID = "deleted-user";
	private static final String DELETED_COMMENT = "사용자 데이터 삭제로 경로 피드백 내용이 삭제되었습니다.";

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;

	@Autowired
	public JdbcRouteSearchRepository(DataSource dataSource, ObjectMapper objectMapper) {
		this(new JdbcTemplate(dataSource), objectMapper);
	}

	JdbcRouteSearchRepository(JdbcTemplate jdbcTemplate) {
		this(jdbcTemplate, new ObjectMapper().findAndRegisterModules());
	}

	JdbcRouteSearchRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
	}

	@Override
	public Optional<RouteSearchResult> loadRouteSearch(String routeSearchId) {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject(
				"""
					SELECT route_search_id,
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
						created_at
					FROM route_search_results
					WHERE route_search_id = ?
					""",
				this::mapRouteSearchResult,
				routeSearchId
			));
		} catch (EmptyResultDataAccessException exception) {
			return Optional.empty();
		}
	}

	@Override
	public RouteSearchResult saveRouteSearch(RouteSearchResult routeSearchResult) {
		// 경로 재계산 결과는 같은 식별자를 다시 받을 수 있어 기존 행을 먼저 갱신한다.
		if (updateRouteSearch(routeSearchResult) == 0) {
			insertRouteSearch(routeSearchResult);
		}
		return routeSearchResult;
	}

	@Override
	public RouteFeedback saveRouteFeedback(RouteFeedback feedback) {
		if (updateRouteFeedback(feedback) == 0) {
			insertRouteFeedback(feedback);
		}
		return feedback;
	}

	@Override
	public int anonymizeRouteFeedbacksByUserId(String userId) {
		return jdbcTemplate.update(
			"""
				UPDATE route_feedbacks
				SET user_id = ?,
					comment = ?
				WHERE user_id = ?
				""",
			DELETED_USER_ID,
			DELETED_COMMENT,
			userId
		);
	}

	private int updateRouteSearch(RouteSearchResult route) {
		return jdbcTemplate.update(
			"""
				UPDATE route_search_results
				SET origin_station_id = ?,
					origin_station_name = ?,
					destination_station_id = ?,
					destination_station_name = ?,
					mobility_type = ?,
					status = ?,
					line_id = ?,
					line_name = ?,
					score = ?,
					steps_json = ?,
					warnings_json = ?,
					blocked_reasons_json = ?,
					created_at = ?
				WHERE route_search_id = ?
				""",
			route.originStationId(),
			route.originStationName(),
			route.destinationStationId(),
			route.destinationStationName(),
			route.mobilityType().name(),
			route.status().name(),
			route.lineId(),
			route.lineName(),
			route.score(),
			// PostgreSQL 운영 스키마에서는 경로 단계와 경고처럼 구조가 자주 바뀌는 값을 JSON으로 보관한다.
			writeJson(route.steps()),
			writeJson(route.warnings()),
			writeJson(route.blockedReasons()),
			route.createdAt(),
			route.routeSearchId()
		);
	}

	private void insertRouteSearch(RouteSearchResult route) {
		jdbcTemplate.update(
			"""
				INSERT INTO route_search_results (
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
					created_at
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
			route.routeSearchId(),
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
			route.createdAt()
		);
	}

	private int updateRouteFeedback(RouteFeedback feedback) {
		return jdbcTemplate.update(
			"""
				UPDATE route_feedbacks
				SET route_search_id = ?,
					user_id = ?,
					rating = ?,
					comment = ?,
					created_at = ?
				WHERE feedback_id = ?
				""",
			feedback.routeSearchId(),
			feedback.userId(),
			feedback.rating().name(),
			feedback.comment(),
			feedback.createdAt(),
			feedback.feedbackId()
		);
	}

	private void insertRouteFeedback(RouteFeedback feedback) {
		jdbcTemplate.update(
			"""
				INSERT INTO route_feedbacks (
					feedback_id,
					route_search_id,
					user_id,
					rating,
					comment,
					created_at
				)
				VALUES (?, ?, ?, ?, ?, ?)
				""",
			feedback.feedbackId(),
			feedback.routeSearchId(),
			feedback.userId(),
			feedback.rating().name(),
			feedback.comment(),
			feedback.createdAt()
		);
	}

	private RouteSearchResult mapRouteSearchResult(ResultSet resultSet, int rowNumber) throws SQLException {
		return new RouteSearchResult(
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
			resultSet.getTimestamp("created_at").toLocalDateTime()
		);
	}

	private String writeJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("경로 검색 JSON 저장값을 만들지 못했습니다.", exception);
		}
	}

	private <T> T readJson(String json, TypeReference<T> typeReference) {
		try {
			return objectMapper.readValue(json, typeReference);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("경로 검색 JSON 저장값을 읽지 못했습니다.", exception);
		}
	}
}
