package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.ServiceDayResolver;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JourneyProfileFullCorpusRunnerTest {

	@Test
	void usesTheRequestedProfileWindowAndCrossesCutoffBeforeAnOrdinaryMorningTrip() {
		var ready = Instant.parse("2024-01-02T20:00:00Z");
		var window = JourneyProfileFullCorpusRunner.profileWindow(ready, ready.plusSeconds(7_200), Duration.ofMinutes(30));
		assertThat(window.latestReadyAt()).isEqualTo(ready.plusSeconds(1_800));
		var earliest = JourneyProfileFullCorpusRunner.cutoffReadyAt(ready, ready.minusSeconds(10_800));
		assertThat(ServiceDayResolver.resolve(earliest).serviceDate())
			.isBefore(ServiceDayResolver.resolve(ready).serviceDate());
		assertThat(earliest).isBefore(ready);
		assertThatThrownBy(() -> JourneyProfileFullCorpusRunner.cutoffReadyAt(ready, ready))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void derivesStableDistinctRequestIdsFromRegionAndTemporalQuery() {
		var ready = Instant.parse("2024-01-02T20:00:00Z");
		var query = new JourneyRaptorQuery.DepartAt(ready);
		var id = JourneyProfileFullCorpusRunner.requestId("region-a", "origin", "destination", query);
		assertThat(id).matches("[0-7][0-9A-HJKMNP-TV-Z]{25}");
		assertThat(id).isEqualTo(JourneyProfileFullCorpusRunner.requestId("region-a", "origin", "destination", query));
		assertThat(id).isNotEqualTo(JourneyProfileFullCorpusRunner.requestId("region-b", "origin", "destination", query));
		assertThat(id).isNotEqualTo(JourneyProfileFullCorpusRunner.requestId("region-a", "origin", "destination",
			new JourneyRaptorQuery.DepartAt(ready.plusSeconds(60))));
	}

	@Test
	void requiresExplicitPositiveOracleBudgets() {
		assertThatThrownBy(() -> new JourneyProfileFullCorpusRunner.OracleLimits(0, 1, 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyProfileFullCorpusRunner.OracleLimits(1, 0, 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyProfileFullCorpusRunner.OracleLimits(1, 1, 0))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void includesPriorExtendedServiceDatesAndTheNextCalendarDay() {
		var start = Instant.parse("2024-01-02T17:30:00Z");
		var end = start.plusSeconds(7_200);
		var dates = JourneyProfileFullCorpusRunner.applicableServiceDates(start, end);
		assertThat(dates).contains(ServiceDayResolver.resolve(start).serviceDate(),
			ServiceDayResolver.resolve(end).serviceDate());
		assertThat(dates.getFirst()).isBefore(start.atZone(ServiceDayResolver.ZONE).toLocalDate());
		assertThatThrownBy(() -> JourneyProfileFullCorpusRunner.applicableServiceDates(end, start))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
