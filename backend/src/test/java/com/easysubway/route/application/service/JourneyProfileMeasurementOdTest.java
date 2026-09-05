package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.bundle.JourneyProfileMeasurementInputs.Line;
import com.easysubway.journey.bundle.JourneyProfileMeasurementInputs.Scope;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class JourneyProfileMeasurementOdTest {
	private static final LocalDate DATE = LocalDate.of(2024, 1, 1);

	@Test
	void choosesTheSameOrderedCandidateWhenEventsAreReversed() {
		var scope = scope(new Line("seoul", "operator", "line-b"), new Line("seoul", "operator", "line-a"));
		var earlier = event("line-a", "trip-a", 2, stop("origin", "line-a", 100, true, false), stop("destination", "line-a", 200, false, true));
		var later = event("line-b", "trip-b", 1, stop("other-origin", "line-b", 10, true, false), stop("other-destination", "line-b", 20, false, true));

		var selected = JourneyProfileMeasurementOd.selectDirectOd(scope, "seoul", List.of(later, earlier));
		assertThat(JourneyProfileMeasurementOd.selectDirectOd(scope, "seoul", List.of(earlier, later))).isEqualTo(selected);
		assertThat(selected.routeLineId()).isEqualTo("line-a");
		assertThat(selected.boardStopIndex()).isZero();
		assertThat(selected.alightStopIndex()).isEqualTo(1);
	}

	@Test
	void excludesEventsForNonSelectedRegions() {
		var scope = scope(new Line("seoul", "operator", "seoul-line"), new Line("busan", "operator", "busan-line"));
		var busan = event("busan-line", "busan-trip", 1, stop("a", "busan-line", 1, true, false), stop("b", "busan-line", 2, false, true));
		var seoul = event("seoul-line", "seoul-trip", 1, stop("c", "seoul-line", 3, true, false), stop("d", "seoul-line", 4, false, true));

		assertThat(JourneyProfileMeasurementOd.selectDirectOd(scope, "seoul", List.of(busan, seoul)).routeLineId())
			.isEqualTo("seoul-line");
	}

	@Test
	void rejectsAmbiguousLineAttribution() {
		var scope = scope(new Line("seoul", "operator-a", "shared"), new Line("busan", "operator-b", "shared"));
		assertThatThrownBy(() -> JourneyProfileMeasurementOd.selectDirectOd(scope, "seoul", List.of()))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ambiguous");
	}

	@Test
	void rejectsWhenNoAllowedPairExists() {
		var scope = scope(new Line("seoul", "operator", "line"));
		var event = event("line", "trip", 1, stop("same", "line", 10, true, false), stop("same", "line", 20, false, true));
		assertThatThrownBy(() -> JourneyProfileMeasurementOd.selectDirectOd(scope, "seoul", List.of(event)))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no allowed");
	}

	@Test
	void preservesAbsoluteTimesAfterTwentyFiveHours() {
		var scope = scope(new Line("seoul", "operator", "line"));
		var candidate = JourneyProfileMeasurementOd.selectDirectOd(scope, "seoul", List.of(event("line", "trip", 1,
			stop("origin", "line", 25 * 3600, true, false), stop("destination", "line", 26 * 3600, false, true))));
		assertThat(candidate.departureAt()).isEqualTo(Instant.parse("2024-01-01T16:00:00Z"));
		assertThat(candidate.arrivalAt()).isEqualTo(Instant.parse("2024-01-01T17:00:00Z"));
	}

	private static Scope scope(Line... lines) {
		return new Scope("version", "a".repeat(64), List.of(lines));
	}

	private static JourneyProfileCandidateEvents.Event event(String lineId, String tripId, int index, JourneyProfileCandidateEvents.Stop... stops) {
		return new JourneyProfileCandidateEvents.Event("route-" + lineId, lineId, tripId, index, DATE, List.of(stops));
	}

	private static JourneyProfileCandidateEvents.Stop stop(String stationId, String lineId, int seconds, boolean pickup, boolean dropOff) {
		return new JourneyProfileCandidateEvents.Stop(stationId, lineId, seconds, seconds, pickup, dropOff);
	}
}
