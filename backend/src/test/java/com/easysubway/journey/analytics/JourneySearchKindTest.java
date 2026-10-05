package com.easysubway.journey.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.JourneyRaptorQuery;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Journey V3 탐색 종류")
class JourneySearchKindTest {

	private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");

	@Test
	@DisplayName("시간 질의 형태가 탐색 종류를 정한다")
	void mapsTemporalQueryToKind() {
		assertThat(JourneySearchKind.of(new JourneyRaptorQuery.DepartAt(T0))).isEqualTo(JourneySearchKind.DEPART_AT);
		assertThat(JourneySearchKind.of(new JourneyRaptorQuery.DepartBetween(T0, T0.plusSeconds(60))))
			.isEqualTo(JourneySearchKind.DEPART_BETWEEN);
		assertThat(JourneySearchKind.of(new JourneyRaptorQuery.ArriveBy(T0, T0.plusSeconds(600))))
			.isEqualTo(JourneySearchKind.ARRIVE_BY);
		assertThat(JourneySearchKind.of(new JourneyRaptorQuery.LastConnection(LocalDate.parse("2026-09-01"))))
			.isEqualTo(JourneySearchKind.LAST_CONNECTION);
	}
}
