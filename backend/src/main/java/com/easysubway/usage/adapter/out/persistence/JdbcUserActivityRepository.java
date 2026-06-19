package com.easysubway.usage.adapter.out.persistence;

import com.easysubway.usage.application.port.out.RecordApiTrafficPort;
import com.easysubway.usage.application.port.out.RecordUserActivityPort;
import com.easysubway.usage.application.port.out.SummarizeUserActivityPort;
import com.easysubway.usage.domain.InvalidUserActivityException;
import com.easysubway.usage.domain.UserActivityDashboardSummary;
import com.easysubway.usage.domain.UserActivityDashboardSummary.DailyUserActivity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("prod")
public class JdbcUserActivityRepository implements RecordUserActivityPort, RecordApiTrafficPort, SummarizeUserActivityPort {

	private static final int USER_ID_MAX_LENGTH = 120;

	private final JdbcTemplate jdbcTemplate;

	public JdbcUserActivityRepository(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	JdbcUserActivityRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void recordUserActivity(String userId, LocalDateTime occurredAt) {
		if (userId == null || userId.isBlank()) {
			throw new InvalidUserActivityException("사용자 활동 식별자가 필요합니다.");
		}
		String normalizedUserId = userId.trim();
		if (normalizedUserId.length() > USER_ID_MAX_LENGTH) {
			throw new InvalidUserActivityException("사용자 활동 식별자는 120자 이하여야 합니다.");
		}
		if (occurredAt == null) {
			throw new InvalidUserActivityException("사용자 활동 시간이 필요합니다.");
		}

		jdbcTemplate.update(
			"""
				INSERT INTO user_activity_events (user_id, occurred_at)
				VALUES (?, ?)
				""",
			normalizedUserId,
			occurredAt
		);
	}

	@Override
	public void recordApiTraffic(int statusCode, long durationMillis, LocalDateTime occurredAt) {
		if (statusCode < 100 || statusCode > 599) {
			throw new InvalidUserActivityException("API 응답 상태 코드는 100부터 599 사이여야 합니다.");
		}
		if (durationMillis < 0) {
			throw new InvalidUserActivityException("API 응답 시간은 0 이상이어야 합니다.");
		}
		if (occurredAt == null) {
			throw new InvalidUserActivityException("API 요청 시간이 필요합니다.");
		}

		jdbcTemplate.update(
			"""
				INSERT INTO api_traffic_events (status_code, duration_millis, occurred_at)
				VALUES (?, ?, ?)
				""",
			statusCode,
			durationMillis,
			occurredAt
		);
	}

	@Override
	public UserActivityDashboardSummary summarizeUserActivity(LocalDate today, int days) {
		if (today == null) {
			throw new InvalidUserActivityException("사용자 활동 기준일이 필요합니다.");
		}
		if (days <= 0) {
			throw new InvalidUserActivityException("사용자 활동 집계 기간은 1일 이상이어야 합니다.");
		}

		LocalDate startDate = today.minusDays(days - 1L);
		LocalDateTime startAt = startDate.atStartOfDay();
		LocalDateTime endAt = today.plusDays(1).atStartOfDay();
		Map<LocalDate, Long> activeUsersByDate = activeUsersByDate(startAt, endAt);
		Map<LocalDate, ApiTrafficSummary> apiTrafficByDate = apiTrafficByDate(startAt, endAt);
		List<DailyUserActivity> rows = today.datesUntil(startDate.minusDays(1), java.time.Period.ofDays(-1))
			.map(date -> {
				ApiTrafficSummary apiTraffic = apiTrafficByDate.getOrDefault(date, ApiTrafficSummary.empty());
				return new DailyUserActivity(
					date,
					activeUsersByDate.getOrDefault(date, 0L),
					apiTraffic.requestCount(),
					apiTraffic.errorCount(),
					apiTraffic.responseMillis()
				);
			})
			.toList();
		long totalApiRequests = rows.stream().mapToLong(DailyUserActivity::apiRequestCount).sum();
		long totalApiErrors = rows.stream().mapToLong(DailyUserActivity::apiErrorCount).sum();
		long totalApiResponseMillis = rows.stream().mapToLong(DailyUserActivity::apiResponseMillis).sum();
		return new UserActivityDashboardSummary(
			totalActiveUsers(startAt, endAt),
			totalApiRequests,
			totalApiErrors,
			totalApiResponseMillis,
			rows
		);
	}

	private long totalActiveUsers(LocalDateTime startAt, LocalDateTime endAt) {
		Long count = jdbcTemplate.queryForObject(
			"""
				SELECT COUNT(DISTINCT user_id)
				FROM user_activity_events
				WHERE occurred_at >= ?
					AND occurred_at < ?
				""",
			Long.class,
			startAt,
			endAt
		);
		return count == null ? 0L : count;
	}

	private Map<LocalDate, Long> activeUsersByDate(LocalDateTime startAt, LocalDateTime endAt) {
		List<DailyActiveUserCount> rows = jdbcTemplate.query(
			"""
				SELECT CAST(occurred_at AS DATE) AS activity_date,
					COUNT(DISTINCT user_id) AS active_user_count
				FROM user_activity_events
				WHERE occurred_at >= ?
					AND occurred_at < ?
				GROUP BY CAST(occurred_at AS DATE)
				""",
			(resultSet, rowNumber) -> new DailyActiveUserCount(
				resultSet.getDate("activity_date").toLocalDate(),
				resultSet.getLong("active_user_count")
			),
			startAt,
			endAt
		);
		Map<LocalDate, Long> counts = new LinkedHashMap<>();
		rows.forEach(row -> counts.put(row.date(), row.activeUserCount()));
		return counts;
	}

	private Map<LocalDate, ApiTrafficSummary> apiTrafficByDate(LocalDateTime startAt, LocalDateTime endAt) {
		List<ApiTrafficSummary> rows = jdbcTemplate.query(
			"""
				SELECT CAST(occurred_at AS DATE) AS traffic_date,
					COUNT(*) AS request_count,
					SUM(CASE WHEN status_code >= 400 THEN 1 ELSE 0 END) AS error_count,
					SUM(duration_millis) AS response_millis
				FROM api_traffic_events
				WHERE occurred_at >= ?
					AND occurred_at < ?
				GROUP BY CAST(occurred_at AS DATE)
				""",
			(resultSet, rowNumber) -> new ApiTrafficSummary(
				resultSet.getDate("traffic_date").toLocalDate(),
				resultSet.getLong("request_count"),
				resultSet.getLong("error_count"),
				resultSet.getLong("response_millis")
			),
			startAt,
			endAt
		);
		Map<LocalDate, ApiTrafficSummary> summaries = new LinkedHashMap<>();
		rows.forEach(row -> summaries.put(row.date(), row));
		return summaries;
	}

	private record DailyActiveUserCount(LocalDate date, long activeUserCount) {
	}

	private record ApiTrafficSummary(
		LocalDate date,
		long requestCount,
		long errorCount,
		long responseMillis
	) {

		static ApiTrafficSummary empty() {
			return new ApiTrafficSummary(LocalDate.MIN, 0, 0, 0);
		}
	}
}
