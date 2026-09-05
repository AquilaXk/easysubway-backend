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

		var selected = select(scope, "seoul", List.of(later, earlier), accesses(later, earlier), ACTIVE_FROM, FRESH_UNTIL, 0);
		assertThat(select(scope, "seoul", List.of(earlier, later), accesses(earlier, later), ACTIVE_FROM, FRESH_UNTIL, 0)).isEqualTo(selected);
		assertThat(selected.routeLineId()).isEqualTo("line-a");
		assertThat(selected.boardStopIndex()).isZero();
		assertThat(selected.alightStopIndex()).isEqualTo(1);
	}

	@Test
	void excludesEventsForNonSelectedRegions() {
		var scope = scope(new Line("seoul", "operator", "seoul-line"), new Line("busan", "operator", "busan-line"));
		var busan = event("busan-line", "busan-trip", 1, stop("a", "busan-line", 1, true, false), stop("b", "busan-line", 2, false, true));
		var seoul = event("seoul-line", "seoul-trip", 1, stop("c", "seoul-line", 3, true, false), stop("d", "seoul-line", 4, false, true));

		assertThat(select(scope, "seoul", List.of(busan, seoul), accesses(busan, seoul), ACTIVE_FROM, FRESH_UNTIL, 0).routeLineId())
			.isEqualTo("seoul-line");
	}

	@Test
	void rejectsAmbiguousLineAttribution() {
		var scope = scope(new Line("seoul", "operator-a", "shared"), new Line("busan", "operator-b", "shared"));
		assertThatThrownBy(() -> select(scope, "seoul", List.of(), List.of(), ACTIVE_FROM, FRESH_UNTIL, 0))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ambiguous");
	}

	@Test
	void rejectsWhenNoAllowedPairExists() {
		var scope = scope(new Line("seoul", "operator", "line"));
		var event = event("line", "trip", 1, stop("same", "line", 10, true, false), stop("same", "line", 20, false, true));
		assertThatThrownBy(() -> select(scope, "seoul", List.of(event), accesses(event), ACTIVE_FROM, FRESH_UNTIL, 0))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no allowed");
	}

	@Test
	void preservesAbsoluteTimesAfterTwentyFiveHours() {
		var scope = scope(new Line("seoul", "operator", "line"));
		var event = event("line", "trip", 1,
			stop("origin", "line", 25 * 3600, true, false), stop("destination", "line", 26 * 3600, false, true));
		var candidate = select(scope, "seoul", List.of(event), accesses(event), ACTIVE_FROM, FRESH_UNTIL, 0);
		assertThat(candidate.departureAt()).isEqualTo(Instant.parse("2024-01-01T16:00:00Z"));
		assertThat(candidate.arrivalAt()).isEqualTo(Instant.parse("2024-01-01T17:00:00Z"));
	}

	@Test
	void skipsAnEarlierSortedCandidateWithoutUsableAccess() {
		var scope = scope(new Line("seoul", "operator", "line-a"), new Line("seoul", "operator", "line-b"));
		var earlier = event("line-a", "a", 1, stop("a", "line-a", 100, true, false), stop("b", "line-a", 200, false, true));
		var accessible = event("line-b", "b", 1, stop("c", "line-b", 100, true, false), stop("d", "line-b", 200, false, true));
		assertThat(select(scope, "seoul", List.of(earlier, accessible), accesses(accessible), ACTIVE_FROM, FRESH_UNTIL, 0).routeLineId())
			.isEqualTo("line-b");
	}

	@Test
	void rejectsWrongDirectionUnverifiedOrDisallowedExitAndInvalidBoundaries() {
		var scope = scope(new Line("seoul", "operator", "line"));
		var event = event("line", "trip", 1, stop("origin", "line", 100, true, false), stop("destination", "line", 200, false, true));
		var entry = entry("entry", "origin", "line", 20, true, true);
		for (var exit : List.of(exit("wrong", "other", "line", 20, true, true),
			exit("unverified", "destination", "line", 20, false, true), exit("disallowed", "destination", "line", 20, true, false))) {
			assertThatThrownBy(() -> select(scope, "seoul", List.of(event), List.of(entry, exit), ACTIVE_FROM, FRESH_UNTIL, 0))
				.isInstanceOf(IllegalArgumentException.class);
		}
		var valid = List.of(entry, exit("exit", "destination", "line", 20, true, true));
		assertThatThrownBy(() -> select(scope, "seoul", List.of(event), valid, ACTIVE_FROM.plusSeconds(81), FRESH_UNTIL, 0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> select(scope, "seoul", List.of(event), valid, ACTIVE_FROM, ACTIVE_FROM.plusSeconds(220), 0))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void choosesShortestBoundaryDurationIndependentlyOfAccessOrdering() {
		var scope = scope(new Line("seoul", "operator", "line"));
		var event = event("line", "trip", 1, stop("origin", "line", 100, true, false), stop("destination", "line", 200, false, true));
		var slow = entry("slow", "origin", "line", 30, true, true);
		var fast = entry("fast", "origin", "line", 10, true, true);
		var exit = exit("exit", "destination", "line", 10, true, true);
		var first = select(scope, "seoul", List.of(event), List.of(slow, fast, exit), ACTIVE_FROM, FRESH_UNTIL, 0);
		var second = select(scope, "seoul", List.of(event), List.of(exit, fast, slow), ACTIVE_FROM, FRESH_UNTIL, 0);
		assertThat(first).isEqualTo(second);
		assertThat(first.entryAccessId()).isEqualTo("fast");
		assertThat(first.readyAt()).isEqualTo(ACTIVE_FROM.plusSeconds(90));
		assertThat(first.arrivalAtDestination()).isEqualTo(ACTIVE_FROM.plusSeconds(210));
		assertThat(select(scope, "seoul", List.of(event), List.of(fast, exit),
			ACTIVE_FROM.plusSeconds(85), FRESH_UNTIL, 5).readyAt()).isEqualTo(ACTIVE_FROM.plusSeconds(85));
		assertThatThrownBy(() -> select(scope, "seoul", List.of(event), List.of(fast, exit),
			ACTIVE_FROM.plusSeconds(85), FRESH_UNTIL, 6)).isInstanceOf(IllegalArgumentException.class);
	}

	private static JourneyProfileMeasurementOd.DirectOdCandidate select(
		Scope scope, String regionId, List<JourneyProfileCandidateEvents.Event> events,
		List<JourneyProfileExactOracle.Access> accesses, Instant activeFrom, Instant freshUntil, int slack
	) {
		return JourneyProfileMeasurementOd.selectDirectOd(scope, regionId, events, accesses, activeFrom, freshUntil, slack);
	}

	private static List<JourneyProfileExactOracle.Access> accesses(JourneyProfileCandidateEvents.Event... events) {
		var result = new java.util.ArrayList<JourneyProfileExactOracle.Access>();
		for (var event : events) {
			var first = event.stops().getFirst();
			var last = event.stops().getLast();
			result.add(entry("entry-" + event.tripId(), first.stationId(), first.lineId(), 0, true, true));
			result.add(exit("exit-" + event.tripId(), last.stationId(), last.lineId(), 0, true, true));
		}
		return List.copyOf(result);
	}

	private static JourneyProfileExactOracle.Access entry(String id, String station, String line, int duration, boolean verified, boolean allowed) {
		return new JourneyProfileExactOracle.Access(id, JourneyProfileExactOracle.AccessKind.ENTRY,
			station, null, station, line, duration, 0, 0, verified, allowed);
	}

	private static JourneyProfileExactOracle.Access exit(String id, String station, String line, int duration, boolean verified, boolean allowed) {
		return new JourneyProfileExactOracle.Access(id, JourneyProfileExactOracle.AccessKind.EXIT,
			station, line, station, null, duration, 0, 0, verified, allowed);
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
