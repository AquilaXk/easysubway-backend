package com.easysubway.journey.analytics;

import com.easysubway.journey.application.JourneyRaptorQuery;
import java.util.Objects;

/** 운영 분석이 구분하는 Journey V3 탐색 종류. */
public enum JourneySearchKind {
	DEPART_AT,
	DEPART_BETWEEN,
	ARRIVE_BY,
	LAST_CONNECTION;

	public static JourneySearchKind of(JourneyRaptorQuery.TemporalQuery temporalQuery) {
		return switch (Objects.requireNonNull(temporalQuery, "temporalQuery")) {
			case JourneyRaptorQuery.DepartAt ignored -> DEPART_AT;
			case JourneyRaptorQuery.DepartBetween ignored -> DEPART_BETWEEN;
			case JourneyRaptorQuery.ArriveBy ignored -> ARRIVE_BY;
			case JourneyRaptorQuery.LastConnection ignored -> LAST_CONNECTION;
		};
	}
}
