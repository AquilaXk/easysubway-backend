package com.easysubway.journey.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Journey V3 검색 결과 분류")
class JourneySearchOutcomeTest {

	private record Case(int status, String code, JourneySearchOutcome expected) {
	}

	@Test
	@DisplayName("HTTP 상태와 기계 코드 조합이 결과 분류를 정한다")
	void classifiesFailuresByStatusAndMachineCode() {
		var cases = List.of(
			new Case(422, "ROUTE_NOT_FOUND", JourneySearchOutcome.NO_ROUTE),
			new Case(422, "NO_SERVICE_IN_DEPARTURE_WINDOW", JourneySearchOutcome.NO_ROUTE),
			new Case(422, "NO_ROUTE_ARRIVING_BY_DEADLINE", JourneySearchOutcome.NO_ROUTE),
			new Case(422, "NO_LAST_CONNECTION", JourneySearchOutcome.NO_ROUTE),
			new Case(422, "TEMPORAL_QUERY_TOO_COMPLEX", JourneySearchOutcome.TOO_COMPLEX),
			new Case(504, "JOURNEY_PROFILE_TIMEOUT", JourneySearchOutcome.TIMEOUT),
			new Case(504, "JOURNEY_SEARCH_TIMEOUT", JourneySearchOutcome.TIMEOUT),
			new Case(503, "ROUTE_SERVICE_UNAVAILABLE", JourneySearchOutcome.UNAVAILABLE),
			new Case(503, "ROUTING_BUNDLE_STALE", JourneySearchOutcome.UNAVAILABLE),
			new Case(503, "RAPTOR_FRONTIER_CAPACITY_EXCEEDED", JourneySearchOutcome.UNAVAILABLE),
			new Case(400, "TEMPORAL_WINDOW_TOO_LARGE", JourneySearchOutcome.REJECTED),
			new Case(422, "REALTIME_NOT_APPLICABLE_TO_TEMPORAL_QUERY", JourneySearchOutcome.REJECTED)
		);
		for (Case c : cases) {
			assertThat(JourneySearchOutcome.classifyFailure(c.status(), c.code()))
				.as("%d %s", c.status(), c.code())
				.isEqualTo(c.expected());
		}
	}

	@Test
	@DisplayName("알 수 없는 조합은 성공 분류로 흘리지 않고 미분류로 남긴다")
	void unknownCombinationsAreUnclassifiedNeverFound() {
		assertThat(JourneySearchOutcome.classifyFailure(500, "SOMETHING_NEW"))
			.isEqualTo(JourneySearchOutcome.UNCLASSIFIED);
		assertThat(JourneySearchOutcome.classifyFailure(422, "SOMETHING_NEW"))
			.isEqualTo(JourneySearchOutcome.UNCLASSIFIED);
		assertThat(JourneySearchOutcome.classifyFailure(200, "ROUTE_NOT_FOUND"))
			.isEqualTo(JourneySearchOutcome.UNCLASSIFIED);
		assertThat(JourneySearchOutcome.classifyFailure(504, "ROUTE_NOT_FOUND"))
			.isEqualTo(JourneySearchOutcome.UNCLASSIFIED);
		assertThat(JourneySearchOutcome.classifyFailure(422, "JOURNEY_PROFILE_TIMEOUT"))
			.isEqualTo(JourneySearchOutcome.UNCLASSIFIED);
		for (int status : new int[] {200, 400, 401, 422, 500, 504}) {
			assertThat(JourneySearchOutcome.classifyFailure(status, "ANY")).isNotEqualTo(JourneySearchOutcome.FOUND);
		}
	}
}
