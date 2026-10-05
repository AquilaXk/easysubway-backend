package com.easysubway.journey.analytics;

import java.time.LocalDate;
import java.util.List;

public interface JourneySearchRecordStore {

	void save(JourneySearchRecord record);

	/** {@code recordedOn}이 [fromInclusive, toInclusive]인 기록을 모든 구분 기준으로 묶어 센다. */
	List<JourneySearchAggregateRow> aggregate(LocalDate fromInclusive, LocalDate toInclusive);
}
