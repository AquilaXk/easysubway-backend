package com.easysubway.journey.analytics;

import java.time.LocalDate;

/** 같은 구분 기준을 가진 검색 기록의 건수. */
public record JourneySearchAggregateRow(
	LocalDate day,
	JourneySearchKind kind,
	JourneySearchOutcome outcome,
	String engineVersion,
	String mobilityProfile,
	String alternativeCategories,
	String stairFreeStatus,
	long count
) {
}
