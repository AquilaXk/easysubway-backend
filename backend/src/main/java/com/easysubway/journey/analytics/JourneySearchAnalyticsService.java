package com.easysubway.journey.analytics;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class JourneySearchAnalyticsService {

	private static final Set<Integer> ALLOWED_DAYS = Set.of(7, 30, 90);
	private static final int DEFAULT_DAYS = 7;

	private final JourneySearchRecordStore store;
	private final JourneySearchRecorder recorder;
	private final Clock clock;

	@Autowired
	public JourneySearchAnalyticsService(
		JourneySearchRecordStore store, JourneySearchRecorder recorder, ObjectProvider<Clock> clockProvider
	) {
		this(store, recorder, clockProvider.getIfAvailable(() -> Clock.system(ZoneId.of("Asia/Seoul"))));
	}

	public JourneySearchAnalyticsService(JourneySearchRecordStore store, JourneySearchRecorder recorder, Clock clock) {
		this.store = store;
		this.recorder = recorder;
		this.clock = clock;
	}

	public JourneySearchAnalyticsSummary summarize(int requestedDays) {
		int days = ALLOWED_DAYS.contains(requestedDays) ? requestedDays : DEFAULT_DAYS;
		LocalDate today = LocalDate.now(clock);
		LocalDate from = today.minusDays(days - 1L);
		List<JourneySearchAggregateRow> rows = store.aggregate(from, today);

		Map<JourneySearchKind, Map<JourneySearchOutcome, Long>> byKind = new EnumMap<>(JourneySearchKind.class);
		Map<LocalDate, Map<JourneySearchOutcome, Long>> byDay = new HashMap<>();
		Map<String, Long> engines = new HashMap<>();
		Map<String, Long> stairFree = new HashMap<>();
		Map<String, Long> categories = new HashMap<>();
		long total = 0;
		for (JourneySearchAggregateRow row : rows) {
			total += row.count();
			byKind.computeIfAbsent(row.kind(), kind -> new EnumMap<>(JourneySearchOutcome.class))
				.merge(row.outcome(), row.count(), Long::sum);
			byDay.computeIfAbsent(row.day(), day -> new EnumMap<>(JourneySearchOutcome.class))
				.merge(row.outcome(), row.count(), Long::sum);
			engines.merge(row.engineVersion(), row.count(), Long::sum);
			stairFree.merge(row.stairFreeStatus(), row.count(), Long::sum);
			if (!row.alternativeCategories().isEmpty()) {
				for (String category : row.alternativeCategories().split(",")) {
					categories.merge(category, row.count(), Long::sum);
				}
			}
		}

		List<JourneySearchAnalyticsSummary.KindRow> kindRows = new ArrayList<>();
		for (JourneySearchKind kind : JourneySearchKind.values()) {
			if (byKind.containsKey(kind)) kindRows.add(new JourneySearchAnalyticsSummary.KindRow(kind, byKind.get(kind)));
		}
		List<JourneySearchAnalyticsSummary.DayRow> dayRows = from.datesUntil(today.plusDays(1))
			.map(day -> new JourneySearchAnalyticsSummary.DayRow(
				day, byDay.containsKey(day), byDay.getOrDefault(day, Map.of())))
			.toList();
		return new JourneySearchAnalyticsSummary(days, total, kindRows, dayRows, countRows(engines),
			countRows(stairFree), countRows(categories), recorder.failureCount());
	}

	private static List<JourneySearchAnalyticsSummary.CountRow> countRows(Map<String, Long> counts) {
		return counts.entrySet().stream()
			.map(entry -> new JourneySearchAnalyticsSummary.CountRow(entry.getKey(), entry.getValue()))
			.sorted(Comparator.comparingLong(JourneySearchAnalyticsSummary.CountRow::count).reversed()
				.thenComparing(JourneySearchAnalyticsSummary.CountRow::label))
			.toList();
	}
}
