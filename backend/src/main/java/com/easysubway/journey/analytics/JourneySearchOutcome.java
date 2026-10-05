package com.easysubway.journey.analytics;

import java.util.Set;

/**
 * 검색 응답을 운영 분석 분류로 묶은 값. 성공은 {@link #FOUND}뿐이며, 알 수 없는 조합은 성공으로 흘리지 않고
 * {@link #UNCLASSIFIED}로 남긴다.
 */
public enum JourneySearchOutcome {
	FOUND,
	NO_ROUTE,
	TOO_COMPLEX,
	TIMEOUT,
	UNAVAILABLE,
	REJECTED,
	UNCLASSIFIED;

	private static final Set<String> NO_ROUTE_CODES = Set.of(
		"ROUTE_NOT_FOUND", "NO_SERVICE_IN_DEPARTURE_WINDOW", "NO_ROUTE_ARRIVING_BY_DEADLINE", "NO_LAST_CONNECTION");
	private static final Set<String> TIMEOUT_CODES = Set.of("JOURNEY_PROFILE_TIMEOUT", "JOURNEY_SEARCH_TIMEOUT");
	private static final Set<String> REJECTED_CODES = Set.of(
		"TEMPORAL_WINDOW_TOO_LARGE", "REALTIME_NOT_APPLICABLE_TO_TEMPORAL_QUERY");

	/** 실패 응답의 HTTP 상태와 기계 코드로 분류한다. 성공 응답에는 쓰지 않는다. */
	public static JourneySearchOutcome classifyFailure(int httpStatus, String machineCode) {
		if (httpStatus == 422 && NO_ROUTE_CODES.contains(machineCode)) return NO_ROUTE;
		if (httpStatus == 422 && "TEMPORAL_QUERY_TOO_COMPLEX".equals(machineCode)) return TOO_COMPLEX;
		if (httpStatus == 504 && TIMEOUT_CODES.contains(machineCode)) return TIMEOUT;
		if (httpStatus == 503) return UNAVAILABLE;
		if ((httpStatus == 400 || httpStatus == 422) && REJECTED_CODES.contains(machineCode)) return REJECTED;
		return UNCLASSIFIED;
	}
}
