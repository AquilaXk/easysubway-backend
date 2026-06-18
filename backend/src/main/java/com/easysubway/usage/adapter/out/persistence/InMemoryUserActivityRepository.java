package com.easysubway.usage.adapter.out.persistence;

import com.easysubway.usage.application.port.out.RecordApiTrafficPort;
import com.easysubway.usage.application.port.out.RecordUserActivityPort;
import com.easysubway.usage.application.port.out.SummarizeUserActivityPort;
import com.easysubway.usage.domain.InvalidUserActivityException;
import com.easysubway.usage.domain.UserActivityDashboardSummary;
import com.easysubway.usage.domain.UserActivityDashboardSummary.DailyUserActivity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!prod")
public class InMemoryUserActivityRepository implements RecordUserActivityPort, RecordApiTrafficPort, SummarizeUserActivityPort {

	private final Map<LocalDate, Set<String>> userIdsByDate = new HashMap<>();
	private final Map<LocalDate, ApiTrafficCount> apiTrafficByDate = new HashMap<>();

	@Override
	public synchronized void recordUserActivity(String userId, LocalDateTime occurredAt) {
		if (userId == null || userId.isBlank()) {
			throw new InvalidUserActivityException("사용자 활동 식별자가 필요합니다.");
		}
		if (occurredAt == null) {
			throw new InvalidUserActivityException("사용자 활동 시간이 필요합니다.");
		}

		userIdsByDate.computeIfAbsent(occurredAt.toLocalDate(), ignored -> new HashSet<>()).add(userId.trim());
	}

	@Override
	public synchronized void recordApiTraffic(int statusCode, long durationMillis, LocalDateTime occurredAt) {
		if (statusCode < 100 || statusCode > 599) {
			throw new InvalidUserActivityException("API 응답 상태 코드는 100부터 599 사이여야 합니다.");
		}
		if (durationMillis < 0) {
			throw new InvalidUserActivityException("API 응답 시간은 0 이상이어야 합니다.");
		}
		if (occurredAt == null) {
			throw new InvalidUserActivityException("API 요청 시간이 필요합니다.");
		}

		apiTrafficByDate.computeIfAbsent(occurredAt.toLocalDate(), ignored -> new ApiTrafficCount())
			.add(statusCode, durationMillis);
	}

	@Override
	public synchronized UserActivityDashboardSummary summarizeUserActivity(LocalDate today, int days) {
		if (today == null) {
			throw new InvalidUserActivityException("사용자 활동 기준일이 필요합니다.");
		}
		if (days <= 0) {
			throw new InvalidUserActivityException("사용자 활동 집계 기간은 1일 이상이어야 합니다.");
		}

		LocalDate startDate = today.minusDays(days - 1L);
		Set<String> activeUserIds = new LinkedHashSet<>();
		List<DailyUserActivity> rows = today.datesUntil(startDate.minusDays(1), java.time.Period.ofDays(-1))
			.map(date -> {
				Set<String> userIds = userIdsByDate.getOrDefault(date, Set.of());
				ApiTrafficCount apiTrafficCount = apiTrafficByDate.getOrDefault(date, ApiTrafficCount.empty());
				activeUserIds.addAll(userIds);
				return new DailyUserActivity(
					date,
					userIds.size(),
					apiTrafficCount.requestCount(),
					apiTrafficCount.errorCount(),
					apiTrafficCount.responseMillis()
				);
			})
			.toList();
		long totalApiRequests = rows.stream().mapToLong(DailyUserActivity::apiRequestCount).sum();
		long totalApiErrors = rows.stream().mapToLong(DailyUserActivity::apiErrorCount).sum();
		long totalApiResponseMillis = rows.stream().mapToLong(DailyUserActivity::apiResponseMillis).sum();
		return new UserActivityDashboardSummary(
			activeUserIds.size(),
			totalApiRequests,
			totalApiErrors,
			totalApiResponseMillis,
			rows
		);
	}

	private static final class ApiTrafficCount {

		private long requestCount;
		private long errorCount;
		private long responseMillis;

		static ApiTrafficCount empty() {
			return new ApiTrafficCount();
		}

		void add(int statusCode, long durationMillis) {
			requestCount++;
			responseMillis += durationMillis;
			if (statusCode >= 400) {
				errorCount++;
			}
		}

		long requestCount() {
			return requestCount;
		}

		long errorCount() {
			return errorCount;
		}

		long responseMillis() {
			return responseMillis;
		}
	}
}
