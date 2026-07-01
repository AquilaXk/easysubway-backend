package com.easysubway.route.adapter.out.persistence;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.out.LoadRouteSearchPort;
import com.easysubway.route.application.port.out.SaveRouteFeedbackPort;
import com.easysubway.route.application.port.out.SaveRouteSearchPort;
import com.easysubway.route.application.port.out.SummarizeRouteFeedbackPort;
import com.easysubway.route.application.port.out.SummarizeRouteSearchPort;
import com.easysubway.route.application.port.out.SummarizeRouteSearchPort.RouteSearchBlockedReasons;
import com.easysubway.route.application.port.out.SummarizeRouteSearchPort.RouteSearchQualitySignals;
import com.easysubway.route.application.port.out.SummarizeRouteSearchPort.RouteSearchStationPair;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.EtaSource;
import com.easysubway.route.domain.RouteEtaOffsetBucket;
import com.easysubway.route.domain.RouteFeedback;
import com.easysubway.route.domain.RouteFeedbackDashboardSummary;
import com.easysubway.route.domain.RouteFeedbackDashboardSummary.EtaCalibrationBucket;
import com.easysubway.route.domain.RouteFeedbackDashboardSummary.RecentBlockedFeedback;
import com.easysubway.route.domain.RouteSearchDashboardSummary;
import com.easysubway.route.domain.RouteSearchDashboardSummary.MobilityTypeCount;
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
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("prod | staging | release | prod-like")
public class JdbcRouteSearchRepository
	implements LoadRouteSearchPort, SaveRouteSearchPort, SaveRouteFeedbackPort, SummarizeRouteFeedbackPort,
	SummarizeRouteSearchPort, AnonymizeUserRouteFeedbackPort {

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
	private final DatabaseDialect databaseDialect;

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
		this.databaseDialect = detectDatabaseDialect(jdbcTemplate);
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
		upsertRouteSearch(routeSearchResult);
		return routeSearchResult;
	}

	@Override
	public RouteFeedback saveRouteFeedback(RouteFeedback feedback) {
		upsertRouteFeedback(feedback);
		return feedback;
	}

	@Override
	public RouteFeedbackDashboardSummary summarizeRouteFeedbacks() {
		RouteFeedbackDashboardSummary countSummary = jdbcTemplate.queryForObject(
			"""
				SELECT COUNT(*) AS total_count,
					SUM(CASE WHEN rating = 'HELPFUL' THEN 1 ELSE 0 END) AS helpful_count,
					SUM(CASE WHEN rating = 'NOT_HELPFUL' THEN 1 ELSE 0 END) AS not_helpful_count,
					SUM(CASE WHEN rating = 'BLOCKED_BY_REAL_WORLD' THEN 1 ELSE 0 END) AS blocked_by_real_world_count
				FROM route_feedbacks
				""",
			(resultSet, rowNumber) -> new RouteFeedbackDashboardSummary(
				resultSet.getLong("total_count"),
				resultSet.getLong("helpful_count"),
				resultSet.getLong("not_helpful_count"),
				resultSet.getLong("blocked_by_real_world_count"),
				List.of()
			)
		);
		return new RouteFeedbackDashboardSummary(
			countSummary.totalCount(),
			countSummary.helpfulCount(),
			countSummary.notHelpfulCount(),
			countSummary.blockedByRealWorldCount(),
			loadRecentBlockedFeedbacks(),
			loadEtaCalibrationBuckets()
		);
	}

	private List<EtaCalibrationBucket> loadEtaCalibrationBuckets() {
		return jdbcTemplate.query(
			"""
				SELECT mobility_type,
					constraint_mode,
					eta_source,
					eta_offset_bucket,
					COUNT(*) AS count
				FROM route_feedbacks
				WHERE eta_feedback_opted_in = TRUE
					AND mobility_type IS NOT NULL
					AND constraint_mode IS NOT NULL
					AND eta_source IS NOT NULL
					AND eta_offset_bucket IS NOT NULL
				GROUP BY mobility_type, constraint_mode, eta_source, eta_offset_bucket
				ORDER BY mobility_type, constraint_mode, eta_source, eta_offset_bucket
				""",
			(resultSet, rowNumber) -> new EtaCalibrationBucket(
				MobilityType.valueOf(resultSet.getString("mobility_type")),
				ConstraintMode.valueOf(resultSet.getString("constraint_mode")),
				EtaSource.valueOf(resultSet.getString("eta_source")),
				RouteEtaOffsetBucket.valueOf(resultSet.getString("eta_offset_bucket")),
				resultSet.getLong("count")
			)
		);
	}

	private List<RecentBlockedFeedback> loadRecentBlockedFeedbacks() {
		return jdbcTemplate.query(
			"""
				SELECT route_search_results.origin_station_name,
					route_search_results.destination_station_name,
					route_search_results.mobility_type,
					route_feedbacks.created_at AS feedback_created_at
				FROM route_feedbacks
				JOIN route_search_results
					ON route_feedbacks.route_search_id = route_search_results.route_search_id
				WHERE route_feedbacks.rating = 'BLOCKED_BY_REAL_WORLD'
				ORDER BY feedback_created_at DESC, route_feedbacks.feedback_id
				LIMIT 5
				""",
			(resultSet, rowNumber) -> new RecentBlockedFeedback(
				resultSet.getString("origin_station_name"),
				resultSet.getString("destination_station_name"),
				MobilityType.valueOf(resultSet.getString("mobility_type")),
				resultSet.getTimestamp("feedback_created_at").toLocalDateTime()
			)
		);
	}

	@Override
	public RouteSearchDashboardSummary summarizeRouteSearches() {
		// 상태별 집계와 이동 프로필별 집계가 같은 DB statement snapshot에서 계산되도록 한 번만 읽는다.
		List<RouteSearchDashboardCountRow> countRows = jdbcTemplate.query(
			"""
				SELECT status,
					mobility_type,
					COUNT(*) AS count
				FROM route_search_results
				GROUP BY status, mobility_type
				""",
			(resultSet, rowNumber) -> new RouteSearchDashboardCountRow(
				RouteSearchStatus.valueOf(resultSet.getString("status")),
				MobilityType.valueOf(resultSet.getString("mobility_type")),
				resultSet.getLong("count")
			)
		);
		long foundCount = countByStatus(countRows, RouteSearchStatus.FOUND);
		long blockedCount = countByStatus(countRows, RouteSearchStatus.BLOCKED);
		return new RouteSearchDashboardSummary(
			foundCount + blockedCount,
			foundCount,
			blockedCount,
			sortedMobilityTypeCounts(countRows)
		);
	}

	@Override
	public List<RouteSearchStationPair> loadRouteSearchStationPairsForDashboard() {
		return jdbcTemplate.query(
			"""
				SELECT origin_station_id,
					destination_station_id
				FROM route_search_results
				ORDER BY created_at DESC, route_search_id
				""",
			(resultSet, rowNumber) -> new RouteSearchStationPair(
				resultSet.getString("origin_station_id"),
				resultSet.getString("destination_station_id")
			)
		);
	}

	@Override
	public List<RouteSearchBlockedReasons> loadRouteSearchBlockedReasonsForDashboard() {
		return jdbcTemplate.query(
			"""
				SELECT blocked_reasons_json
				FROM route_search_results
				WHERE status = 'BLOCKED'
				""",
			(resultSet, rowNumber) -> new RouteSearchBlockedReasons(
				readJson(resultSet.getString("blocked_reasons_json"), STRING_LIST_TYPE)
			)
		);
	}

	@Override
	public List<RouteSearchQualitySignals> loadRouteSearchQualitySignalsForDashboard() {
		return jdbcTemplate.query(
			"""
				SELECT status,
					steps_json,
					warnings_json
				FROM route_search_results
				ORDER BY created_at DESC, route_search_id
				""",
			(resultSet, rowNumber) -> {
				List<RouteStep> steps = readJson(resultSet.getString("steps_json"), ROUTE_STEPS_TYPE);
				List<RouteWarning> warnings = readJson(resultSet.getString("warnings_json"), ROUTE_WARNINGS_TYPE);
				return new RouteSearchQualitySignals(
					RouteSearchStatus.valueOf(resultSet.getString("status")),
					etaSourceFromSteps(steps),
					warnings.stream()
						.map(RouteWarning::code)
						.toList()
				);
			}
		);
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

	private void upsertRouteSearch(RouteSearchResult route) {
		if (databaseDialect == DatabaseDialect.H2) {
			upsertRouteSearchWithH2Merge(route);
			return;
		}
		upsertRouteSearchWithPostgresql(route);
	}

	private void upsertRouteSearchWithPostgresql(RouteSearchResult route) {
		// PostgreSQL ON CONFLICT로 같은 경로 검색 ID의 동시 저장을 원자적으로 처리한다.
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
				ON CONFLICT (route_search_id) DO UPDATE
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
					created_at = EXCLUDED.created_at
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
			// 경로 단계와 경고처럼 구조가 자주 바뀌는 값은 운영 DB에서 JSON으로 보관한다.
			writeJson(route.steps()),
			writeJson(route.warnings()),
			writeJson(route.blockedReasons()),
			route.createdAt()
		);
	}

	private void upsertRouteFeedback(RouteFeedback feedback) {
		if (databaseDialect == DatabaseDialect.H2) {
			upsertRouteFeedbackWithH2Merge(feedback);
			return;
		}
		upsertRouteFeedbackWithPostgresql(feedback);
	}

	private void upsertRouteSearchWithH2Merge(RouteSearchResult route) {
		jdbcTemplate.update(
			"""
				MERGE INTO route_search_results (
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
				KEY (route_search_id)
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

	private void upsertRouteFeedbackWithPostgresql(RouteFeedback feedback) {
		// 피드백 재전송도 같은 ID를 쓸 수 있어 단일 upsert 문으로 PK 충돌을 피한다.
		jdbcTemplate.update(
			"""
				INSERT INTO route_feedbacks (
					feedback_id,
					route_search_id,
					user_id,
					rating,
					comment,
					itinerary_id,
					mobility_type,
					constraint_mode,
					eta_source,
					eta_offset_bucket,
					eta_feedback_opted_in,
					created_at
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (feedback_id) DO UPDATE
				SET route_search_id = EXCLUDED.route_search_id,
					user_id = EXCLUDED.user_id,
					rating = EXCLUDED.rating,
					comment = EXCLUDED.comment,
					itinerary_id = EXCLUDED.itinerary_id,
					mobility_type = EXCLUDED.mobility_type,
					constraint_mode = EXCLUDED.constraint_mode,
					eta_source = EXCLUDED.eta_source,
					eta_offset_bucket = EXCLUDED.eta_offset_bucket,
					eta_feedback_opted_in = EXCLUDED.eta_feedback_opted_in,
					created_at = EXCLUDED.created_at
				""",
			feedback.feedbackId(),
			feedback.routeSearchId(),
			feedback.userId(),
			feedback.rating().name(),
			feedback.comment(),
			feedback.itineraryId(),
			nameOrNull(feedback.mobilityType()),
			nameOrNull(feedback.constraintMode()),
			nameOrNull(feedback.etaSource()),
			nameOrNull(feedback.etaOffsetBucket()),
			feedback.etaFeedbackOptedIn(),
			feedback.createdAt()
		);
	}

	private void upsertRouteFeedbackWithH2Merge(RouteFeedback feedback) {
		jdbcTemplate.update(
			"""
				MERGE INTO route_feedbacks (
					feedback_id,
					route_search_id,
					user_id,
					rating,
					comment,
					itinerary_id,
					mobility_type,
					constraint_mode,
					eta_source,
					eta_offset_bucket,
					eta_feedback_opted_in,
					created_at
				)
				KEY (feedback_id)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
			feedback.feedbackId(),
			feedback.routeSearchId(),
			feedback.userId(),
			feedback.rating().name(),
			feedback.comment(),
			feedback.itineraryId(),
			nameOrNull(feedback.mobilityType()),
			nameOrNull(feedback.constraintMode()),
			nameOrNull(feedback.etaSource()),
			nameOrNull(feedback.etaOffsetBucket()),
			feedback.etaFeedbackOptedIn(),
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

	private long countByStatus(List<RouteSearchDashboardCountRow> rows, RouteSearchStatus status) {
		return rows.stream()
			.filter(row -> row.status() == status)
			.mapToLong(RouteSearchDashboardCountRow::count)
			.sum();
	}

	private List<MobilityTypeCount> sortedMobilityTypeCounts(List<RouteSearchDashboardCountRow> rows) {
		return List.of(MobilityType.values())
			.stream()
			.map(mobilityType -> new MobilityTypeCount(mobilityType, countByMobilityType(rows, mobilityType)))
			.filter(row -> row.count() > 0)
			.toList();
	}

	private long countByMobilityType(List<RouteSearchDashboardCountRow> rows, MobilityType mobilityType) {
		return rows.stream()
			.filter(row -> row.mobilityType() == mobilityType)
			.mapToLong(RouteSearchDashboardCountRow::count)
			.sum();
	}

	private EtaSource etaSourceFromSteps(List<RouteStep> steps) {
		if (steps.isEmpty()) {
			return EtaSource.PLANNED;
		}
		boolean fallback = steps.stream()
			.anyMatch(step -> EtaSource.FALLBACK.name().equals(step.timeSource()));
		if (fallback) {
			return EtaSource.FALLBACK;
		}
		long realtimeSteps = steps.stream()
			.filter(step -> EtaSource.REALTIME.name().equals(step.timeSource()))
			.count();
		if (realtimeSteps == 0) {
			return EtaSource.PLANNED;
		}
		return realtimeSteps == steps.size() ? EtaSource.REALTIME : EtaSource.MIXED;
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

	private String nameOrNull(Enum<?> value) {
		return value == null ? null : value.name();
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

	private record RouteSearchDashboardCountRow(RouteSearchStatus status, MobilityType mobilityType, long count) {
	}
}
