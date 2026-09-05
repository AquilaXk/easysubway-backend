package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendarDate;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitFrequency;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import java.time.LocalDate;
import com.easysubway.journey.application.ServiceDayResolver;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JourneyProfileCandidateEventsTest {
	private static final LocalDate BASE_DATE = LocalDate.of(2024, 1, 1);

	@Test
	void mapsEveryDatedStopPairWithoutReplacingInterlineIdentity() {
		var event = new JourneyProfileCandidateEvents.Event("route", "route-filter-line", "trip", 7, BASE_DATE, List.of(
			new JourneyProfileCandidateEvents.Stop("a", "line-a", 100, 110, true, false),
			new JourneyProfileCandidateEvents.Stop("b", "line-b", 200, 210, false, true),
			new JourneyProfileCandidateEvents.Stop("c", "line-c", 300, 310, true, true)));
		var rides = JourneyProfileCandidateEvents.oracleRides(List.of(event), 3);
		assertThat(rides).hasSize(3);
		var midnight = BASE_DATE.atStartOfDay(ServiceDayResolver.ZONE).toInstant();
		assertThat(rides.get(1)).isEqualTo(new JourneyProfileExactOracle.Ride(
			"trip", BASE_DATE, 7, "a", "line-a", "c", "line-c",
			midnight.plusSeconds(110), midnight.plusSeconds(300), 0, 2, true, true));
		assertThat(rides.get(2).pickupAllowed()).isFalse();
		assertThat(rides).extracting(JourneyProfileExactOracle.Ride::identity).doesNotHaveDuplicates();
		assertThatThrownBy(() -> JourneyProfileCandidateEvents.oracleRides(List.of(event), 2))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ride budget");
		var missingLine = new JourneyProfileCandidateEvents.Event("route", "not-a-fallback", "trip", 7, BASE_DATE,
			List.of(new JourneyProfileCandidateEvents.Stop("a", null, 100, 110, true, false), event.stops().getLast()));
		assertThatThrownBy(() -> JourneyProfileCandidateEvents.oracleRides(List.of(missingLine), 1))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fromLineId");
	}

	@Test
	void rejectsAnEmptyCanonicalLineSetBeforeReadingCompiledData() {
		assertThatThrownBy(() -> JourneyProfileCandidateEvents.events(null, BASE_DATE, Set.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("canonical lines");
	}

	@Test
	void readsFrequencyExpandedTimesAndPickupRulesFromTheCompiledCandidate() {
		var timetable = fixture();
		var events = JourneyProfileCandidateEvents.events(timetable, BASE_DATE, Set.of("line-fixture"));

		assertThat(events).hasSize(2);
		assertThat(events).extracting(event -> event.stops().getFirst().departureSeconds())
			.containsExactly(37_800, 38_100);
		assertThat(events).extracting(event -> event.stops().getLast().arrivalSeconds())
			.containsExactly(37_920, 38_220);
		assertThat(events).extracting(JourneyProfileCandidateEvents.Event::scheduledTripIndex)
			.doesNotHaveDuplicates();
		for (var event : events) {
			assertThat(event.routeId()).isEqualTo("route-fixture");
			assertThat(event.routeLineId()).isEqualTo("line-fixture");
			assertThat(event.tripId()).isEqualTo("trip-fixture");
			assertThat(event.serviceDate()).isEqualTo(BASE_DATE);
			assertThat(event.stops().getFirst().stationId()).isEqualTo("station-a");
			assertThat(event.stops().getFirst().allowsPickup()).isTrue();
			assertThat(event.stops().getFirst().allowsDropOff()).isFalse();
			assertThat(event.stops().getLast().allowsPickup()).isFalse();
			assertThat(event.stops().getLast().allowsDropOff()).isTrue();
		}
		assertThat(JourneyProfileCandidateEvents.events(timetable, BASE_DATE, Set.of("line-fixture")))
			.isEqualTo(events);
		assertThatThrownBy(events::clear).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(events.getFirst().stops()::clear).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void respectsCalendarRemovalAndAdditionWithoutAliasingUnknownLines() {
		var timetable = fixture();
		assertThat(JourneyProfileCandidateEvents.events(timetable, BASE_DATE.plusDays(1), Set.of("line-fixture")))
			.isEmpty();
		assertThat(JourneyProfileCandidateEvents.events(timetable, BASE_DATE.plusDays(2), Set.of("line-fixture")))
			.hasSize(2).allSatisfy(event -> assertThat(event.serviceDate()).isEqualTo(BASE_DATE.plusDays(2)));
		assertThat(JourneyProfileCandidateEvents.events(timetable, BASE_DATE.plusDays(3), Set.of("line-fixture")))
			.isEmpty();
		assertThat(JourneyProfileCandidateEvents.events(timetable, BASE_DATE, Set.of("line-idle"))).isEmpty();
		assertThatThrownBy(() -> JourneyProfileCandidateEvents.events(timetable, BASE_DATE, Set.of("unknown-line")))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not compiled");
	}

	private static RouteTimetableRaptorPlanner.CompiledTimetable fixture() {
		return fixture(37_800);
	}

	@Test
	void selectsActualEventsInsideTheHalfOpenCandidateWindow() {
		var first = BASE_DATE.atStartOfDay(ServiceDayResolver.ZONE).toInstant().plusSeconds(37_800);
		var events = JourneyProfileCandidateEvents.events(fixture(), first, first.plusSeconds(300), Set.of("line-fixture"));
		assertThat(events).hasSize(1);
		assertThat(events.getFirst().stops().getFirst().departureSeconds()).isEqualTo(37_800);
		assertThat(JourneyProfileCandidateEvents.events(fixture(), first.plusSeconds(121), first.plusSeconds(300), Set.of("line-fixture")))
			.isEmpty();
		assertThatThrownBy(() -> JourneyProfileCandidateEvents.events(fixture(), first, first, Set.of("line-fixture")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void includesPredecessorServiceDayEventsBeyondTheCutoff() {
		int departure = 27 * 3600 + 60;
		var instant = BASE_DATE.atStartOfDay(ServiceDayResolver.ZONE).toInstant().plusSeconds(departure);
		assertThat(ServiceDayResolver.resolve(instant).serviceDate()).isEqualTo(BASE_DATE.plusDays(1));
		var events = JourneyProfileCandidateEvents.events(fixture(departure), instant, instant.plusSeconds(1), Set.of("line-fixture"));
		assertThat(events).hasSize(1);
		assertThat(events.getFirst().serviceDate()).isEqualTo(BASE_DATE);
		assertThat(events.getFirst().stops().getFirst().departureSeconds()).isEqualTo(departure);
	}

	private static RouteTimetableRaptorPlanner.CompiledTimetable fixture(int frequencyStart) {
		var source = new RouteTimetable(
			List.of(new ServiceCalendar("service", true, true, true, true, true, true, true,
				BASE_DATE, BASE_DATE.plusDays(1), "Asia/Seoul")),
			List.of(new ServiceCalendarDate("service", BASE_DATE.plusDays(1), 2),
				new ServiceCalendarDate("service", BASE_DATE.plusDays(2), 1)),
			List.of(new TransitRoute("route-fixture", "line-fixture", "test", "test", "outbound", "Asia/Seoul"),
				new TransitRoute("route-idle", "line-idle", "idle", "idle", "outbound", "Asia/Seoul")),
			List.of(new TransitTrip("trip-fixture", "route-fixture", "service", "terminal", "0", "LOCAL", 0)),
			List.of(new TransitStopTime("trip-fixture", 1, "station-a", "line-fixture", 36_000, 36_000, 0, 1),
				new TransitStopTime("trip-fixture", 2, "station-b", "line-fixture", 36_120, 36_120, 1, 0)),
			List.of(new TransitFrequency("trip-fixture", frequencyStart, frequencyStart + 600, 300, true)));
		return RaptorRouteBundleRuntimeView.compile("a".repeat(64), 1, source).compiledTimetable();
	}
}
