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
	private static final Instant ACTIVE_FROM = DATE.atStartOfDay(com.easysubway.journey.application.ServiceDayResolver.ZONE).toInstant();
	private static final Instant FRESH_UNTIL = ACTIVE_FROM.plusSeconds(2 * 86_400L);

	@Test
	void choosesTheSameOrderedCandidateWhenEventsAreReversed() {
		var scope = scope(new Line("seoul", "operator", "line-b"), new Line("seoul", "operator", "line-a"));
		var earlier = event("line-a", "trip-a", 2, stop("origin", "line-a", 100, true, false), stop("destination", "line-a", 200, false, true));
		var later = event("line-b", "trip-b", 1, stop("other-origin", "line-b", 10, true, false), stop("other-destination", "line-b", 20, false, true));

		var selected = select(scope, "seoul", List.of(later, earlier), ACTIVE_FROM, FRESH_UNTIL, 0);
		assertThat(select(scope, "seoul", List.of(earlier, later), ACTIVE_FROM, FRESH_UNTIL, 0)).isEqualTo(selected);
		assertThat(selected.routeLineId()).isEqualTo("line-a");
		assertThat(selected.boardStopIndex()).isZero();
		assertThat(selected.alightStopIndex()).isEqualTo(1);
	}

	@Test
	void excludesEventsForNonSelectedRegions() {
		var scope = scope(new Line("seoul", "operator", "seoul-line"), new Line("busan", "operator", "busan-line"));
		var busan = event("busan-line", "busan-trip", 1, stop("a", "busan-line", 1, true, false), stop("b", "busan-line", 2, false, true));
		var seoul = event("seoul-line", "seoul-trip", 1, stop("c", "seoul-line", 3, true, false), stop("d", "seoul-line", 4, false, true));

		assertThat(select(scope, "seoul", List.of(busan, seoul), ACTIVE_FROM, FRESH_UNTIL, 0).routeLineId())
			.isEqualTo("seoul-line");
	}

	@Test
	void rejectsAmbiguousLineAttribution() {
		var scope = scope(new Line("seoul", "operator-a", "shared"), new Line("busan", "operator-b", "shared"));
		assertThatThrownBy(() -> select(scope, "seoul", List.of(), ACTIVE_FROM, FRESH_UNTIL, 0))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ambiguous");
	}

	@Test
	void distinguishesAnAbsentCandidateFromInvalidAttribution() {
		var valid = scope(new Line("seoul", "operator", "line"));
		assertThat(JourneyProfileMeasurementOd.findDirectOd(valid, "seoul", List.of(),
			ACTIVE_FROM, FRESH_UNTIL, 0)).isEmpty();
		var ambiguous = scope(new Line("seoul", "operator-a", "shared"), new Line("busan", "operator-b", "shared"));
		assertThatThrownBy(() -> JourneyProfileMeasurementOd.findDirectOd(ambiguous, "seoul", List.of(),
			ACTIVE_FROM, FRESH_UNTIL, 0)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ambiguous");
	}

	@Test
	void rejectsWhenNoAllowedPairExists() {
		var scope = scope(new Line("seoul", "operator", "line"));
		var event = event("line", "trip", 1, stop("same", "line", 10, true, false), stop("same", "line", 20, false, true));
		assertThatThrownBy(() -> select(scope, "seoul", List.of(event), ACTIVE_FROM, FRESH_UNTIL, 0))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no allowed");
	}

	@Test
	void preservesAbsoluteTimesAfterTwentyFiveHours() {
		var scope = scope(new Line("seoul", "operator", "line"));
		var event = event("line", "trip", 1,
			stop("origin", "line", 25 * 3600, true, false), stop("destination", "line", 26 * 3600, false, true));
		var candidate = select(scope, "seoul", List.of(event), ACTIVE_FROM, FRESH_UNTIL, 0);
		assertThat(candidate.departureAt()).isEqualTo(Instant.parse("2024-01-01T16:00:00Z"));
		assertThat(candidate.arrivalAt()).isEqualTo(Instant.parse("2024-01-01T17:00:00Z"));
	}

	@Test
	void selectsTheFirstOrderedCandidateWithoutAccessEvidence() {
		// #454: 승강장 기준 여정은 진입·하차 접근 증거를 요구하지 않는다.
		var scope = scope(new Line("seoul", "operator", "line-a"), new Line("seoul", "operator", "line-b"));
		var earlier = event("line-a", "a", 1, stop("a", "line-a", 100, true, false), stop("b", "line-a", 200, false, true));
		var later = event("line-b", "b", 1, stop("c", "line-b", 100, true, false), stop("d", "line-b", 200, false, true));
		assertThat(select(scope, "seoul", List.of(later, earlier), ACTIVE_FROM, FRESH_UNTIL, 0).routeLineId())
			.isEqualTo("line-a");
	}

	@Test
	void rejectsCandidatesOutsideTheActiveWindow() {
		var scope = scope(new Line("seoul", "operator", "line"));
		var event = event("line", "trip", 1, stop("origin", "line", 100, true, false), stop("destination", "line", 200, false, true));
		assertThatThrownBy(() -> select(scope, "seoul", List.of(event), ACTIVE_FROM.plusSeconds(101), FRESH_UNTIL, 0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> select(scope, "seoul", List.of(event), ACTIVE_FROM, ACTIVE_FROM.plusSeconds(200), 0))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void derivesReadinessFromDepartureMinusBoardingSlackAndArrivalAtThePlatform() {
		var scope = scope(new Line("seoul", "operator", "line"));
		var event = event("line", "trip", 1, stop("origin", "line", 100, true, false), stop("destination", "line", 200, false, true));
		var candidate = select(scope, "seoul", List.of(event), ACTIVE_FROM, FRESH_UNTIL, 5);
		assertThat(candidate.readyAt()).isEqualTo(ACTIVE_FROM.plusSeconds(95));
		assertThat(candidate.arrivalAtDestination()).isEqualTo(ACTIVE_FROM.plusSeconds(200));
		assertThat(select(scope, "seoul", List.of(event), ACTIVE_FROM.plusSeconds(95), FRESH_UNTIL, 5).readyAt())
			.isEqualTo(ACTIVE_FROM.plusSeconds(95));
		assertThatThrownBy(() -> select(scope, "seoul", List.of(event), ACTIVE_FROM.plusSeconds(95), FRESH_UNTIL, 6))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static JourneyProfileMeasurementOd.DirectOdCandidate select(
		Scope scope, String regionId, List<JourneyProfileCandidateEvents.Event> events,
		Instant activeFrom, Instant freshUntil, int slack
	) {
		return JourneyProfileMeasurementOd.selectDirectOd(scope, regionId, events, activeFrom, freshUntil, slack);
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
