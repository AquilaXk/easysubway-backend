package com.easysubway.journey.analytics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!prod & !staging & !release & !prod-like")
public class InMemoryJourneySearchRecordStore implements JourneySearchRecordStore {

	private final List<JourneySearchRecord> records = new ArrayList<>();

	@Override
	public synchronized void save(JourneySearchRecord record) {
		records.add(record);
	}

	@Override
	public synchronized List<JourneySearchAggregateRow> aggregate(LocalDate fromInclusive, LocalDate toInclusive) {
		Map<List<Object>, Long> counts = new LinkedHashMap<>();
		for (JourneySearchRecord record : records) {
			if (record.recordedOn().isBefore(fromInclusive) || record.recordedOn().isAfter(toInclusive)) continue;
			counts.merge(List.of(record.recordedOn(), record.kind(), record.outcome(), record.engineVersion(),
				record.mobilityProfile(), record.alternativeCategoriesText(), record.stairFreeStatus()), 1L, Long::sum);
		}
		return counts.entrySet().stream().map(entry -> {
			List<Object> key = entry.getKey();
			return new JourneySearchAggregateRow((LocalDate) key.get(0), (JourneySearchKind) key.get(1),
				(JourneySearchOutcome) key.get(2), (String) key.get(3), (String) key.get(4), (String) key.get(5),
				(String) key.get(6), entry.getValue());
		}).toList();
	}

	@Override
	public synchronized int purgeRecordedBefore(LocalDate cutoff) {
		int before = records.size();
		records.removeIf(record -> record.recordedOn().isBefore(cutoff));
		return before - records.size();
	}
}
