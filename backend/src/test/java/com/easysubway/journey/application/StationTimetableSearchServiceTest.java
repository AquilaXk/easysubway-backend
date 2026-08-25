package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.StationTimetableSearchService.DayType;
import com.easysubway.journey.application.StationTimetableSearchService.Failure;
import com.easysubway.journey.application.StationTimetableSearchService.FailureException;
import com.easysubway.journey.application.StationTimetableSearchService.SearchRequest;
import com.easysubway.journey.application.StationTimetableSearchService.Selector;
import com.easysubway.route.application.model.PlannerIdentity;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteAccessData;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetableSnapshot;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendarDate;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitFrequency;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class StationTimetableSearchServiceTest {
	private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");

	@Test
	void unrelatedSundayOnlyAddedServiceKeepsCivilWeekdayDayType() {
		RouteTimetable timetable = timetable(List.of(
			new ServiceCalendar("weekday", true, true, true, true, true, false, false,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"),
			new ServiceCalendar("added", false, false, false, false, false, false, true,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul")),
			List.of(new ServiceCalendarDate("added", LocalDate.parse("2026-08-25"), 1)),
			List.of(), List.of());

		var result = service(snapshot(timetable, NOW.plusSeconds(60))).search(request(new Selector.ServiceDateSelector(
			LocalDate.parse("2026-08-25"))));

		assertThat(result.resolvedDayType()).isEqualTo(DayType.WEEKDAY);
	}

	@Test
	void removedCivilServiceWithOneAddedSundaySignatureOverridesDayType() {
		RouteTimetable timetable = timetable(List.of(
			new ServiceCalendar("weekday", true, true, true, true, true, false, false,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"),
			new ServiceCalendar("added", false, false, false, false, false, false, true,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul")),
			List.of(new ServiceCalendarDate("weekday", LocalDate.parse("2026-08-25"), 2),
				new ServiceCalendarDate("added", LocalDate.parse("2026-08-25"), 1)), List.of(), List.of());
		var result = service(snapshot(timetable, NOW.plusSeconds(60))).search(request(new Selector.ServiceDateSelector(
			LocalDate.parse("2026-08-25"))));
		assertThat(result.resolvedDayType()).isEqualTo(DayType.SUNDAY_HOLIDAY);
	}

	@Test
	void removedCivilServiceWithNeutralOrMissingReplacementFailsClosed() {
		RouteTimetable neutral = timetable(List.of(
			new ServiceCalendar("weekday", true, true, true, true, true, false, false,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"),
			new ServiceCalendar("neutral", false, false, false, false, false, false, false,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul")),
			List.of(new ServiceCalendarDate("weekday", LocalDate.parse("2026-08-25"), 2),
				new ServiceCalendarDate("neutral", LocalDate.parse("2026-08-25"), 1)), List.of(), List.of());
		for (RouteTimetable timetable : List.of(neutral, timetable(List.of(
			new ServiceCalendar("weekday", true, true, true, true, true, false, false,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul")),
			List.of(new ServiceCalendarDate("weekday", LocalDate.parse("2026-08-25"), 2),
				new ServiceCalendarDate("missing", LocalDate.parse("2026-08-25"), 1)), List.of(), List.of()))) {
			assertThatThrownBy(() -> service(snapshot(timetable, NOW.plusSeconds(60))).search(request(
				new Selector.ServiceDateSelector(LocalDate.parse("2026-08-25")))))
				.isInstanceOf(FailureException.class)
				.extracting(error -> ((FailureException) error).failure())
				.isEqualTo(Failure.TIMETABLE_IDENTITY_MISMATCH);
		}
	}

	@Test
	void rawTransportTripOrderIsSortedAtTheServiceBoundary() {
		RouteTimetable timetable = timetable(List.of(calendar()), List.of(), List.of(), List.of(
			new TransitTrip("a-late", "route", "weekday", "headsign", "0", "SUBWAY", "LOCAL", null, 0),
			new TransitTrip("b-early", "route", "weekday", "headsign", "0", "SUBWAY", "EXPRESS", null, 0)));
		List<TransitStopTime> orderedStops = List.of(
			new TransitStopTime("trip", 1, "station", "line", 32_400, 32_400, 0, 0),
			new TransitStopTime("a-late", 1, "station", "line", 33_000, 33_000, 0, 0),
			new TransitStopTime("b-early", 1, "station", "line", 32_700, 32_700, 0, 0));
		timetable = new RouteTimetable(timetable.serviceCalendars(), timetable.serviceCalendarDates(), timetable.transitRoutes(),
			timetable.transitTrips(), orderedStops, timetable.transitFrequencies(), timetable.officialFares(), timetable.feedEndDate(), timetable.routeAccessData());
		var result = service(snapshot(timetable, NOW.plusSeconds(60))).search(request(
			new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))));
		assertThat(result.directionGroups()).singleElement().extracting(group -> group.departures()
			.stream().map(departure -> departure.secondsFromServiceDayStart()).toList()).isEqualTo(List.of(32_400, 32_700, 33_000));
	}

	@Test
	void malformedFreshnessIsIdentityMismatchNotStale() {
		assertThatThrownBy(() -> service(snapshot(timetable(), null)).search(request(new Selector.ServiceDateSelector(
			LocalDate.parse("2026-08-24")))))
			.isInstanceOf(FailureException.class)
			.extracting(error -> ((FailureException) error).failure())
			.isEqualTo(Failure.TIMETABLE_IDENTITY_MISMATCH);
	}

	@Test
	void canonicalStationLineWithoutStopTimeCoverageIsTyped404Failure() {
		RouteTimetable timetable = new RouteTimetable(List.of(calendar()), List.of(),
			List.of(new TransitRoute("route", "line", "L", "line", "direction", "Asia/Seoul")),
			List.of(), List.of(), List.of(), List.of(), null,
			new RouteAccessData(List.of(new PathwayNode("platform", "station", "line", "PLATFORM")), List.of(), List.of(), List.of()));
		assertThatThrownBy(() -> service(snapshot(timetable, NOW.plusSeconds(60))).search(request(
			new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24")))))
			.isInstanceOf(FailureException.class)
			.extracting(error -> ((FailureException) error).failure())
			.isEqualTo(Failure.TIMETABLE_NOT_COVERED);
	}

	@Test
	void nextDeparturesExpandsFrequencyWithoutReturningBaselineSeparately() {
		RouteTimetable timetable = timetable(List.of(calendar()), List.of(), List.of(
			new TransitFrequency("trip", 32_700, 33_300, 300, true)), List.of());
		var result = service(snapshot(timetable, NOW.plusSeconds(60))).search(request(
			new Selector.NextDeparturesSelector(Instant.parse("2026-08-24T00:06:00Z"), 1)));

		assertThat(result.directionGroups()).singleElement().satisfies(group ->
			assertThat(group.departures()).singleElement().satisfies(departure ->
				assertThat(departure.secondsFromServiceDayStart()).isEqualTo(33_000)));
	}

	@Test
	void frequencyInstanceWithLaterStopOverflowIsSkippedAsAWhole() {
		RouteTimetable template = timetable(List.of(calendar()), List.of(), List.of(), List.of());
		RouteTimetable timetable = new RouteTimetable(template.serviceCalendars(), template.serviceCalendarDates(), template.transitRoutes(),
			List.of(new TransitTrip("frequency", "route", "weekday", "headsign", "0", "SUBWAY", "LOCAL", null, 0)),
			List.of(new TransitStopTime("frequency", 1, "station", "line", 107_900, 107_900, 0, 0),
				new TransitStopTime("frequency", 2, "later", "line", 107_990, 107_990, 0, 0)),
			List.of(new TransitFrequency("frequency", 107_950, 107_960, 300, true)), template.officialFares(),
			template.feedEndDate(), template.routeAccessData());
		var result = service(snapshot(timetable, NOW.plusSeconds(60))).search(request(
			new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))));
		assertThat(result.directionGroups()).isEmpty();
	}

	@Test
	void nextDeparturesRejectsDuplicateBeforeTakingFirstDirectionDeparture() {
		RouteTimetable timetable = timetable(List.of(calendar()), List.of(), List.of(), List.of(
			new TransitTrip("trip-duplicate", "route", "weekday", "headsign", "0", "SUBWAY", "LOCAL", null, 0)));
		assertThatThrownBy(() -> service(snapshot(timetable, NOW.plusSeconds(60))).search(request(
			new Selector.NextDeparturesSelector(Instant.parse("2026-08-24T00:00:00Z"), 1))))
			.isInstanceOf(FailureException.class)
			.extracting(error -> ((FailureException) error).failure())
			.isEqualTo(Failure.TIMETABLE_IDENTITY_MISMATCH);
	}

	@Test
	void serviceDateRejectsDuplicateBeforeBuildingDirectionGroups() {
		RouteTimetable timetable = timetable(List.of(calendar()), List.of(), List.of(), List.of(
			new TransitTrip("trip-duplicate", "route", "weekday", "headsign", "0", "SUBWAY", "LOCAL", null, 0)));
		assertThatThrownBy(() -> service(snapshot(timetable, NOW.plusSeconds(60))).search(request(
			new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24")))))
			.isInstanceOf(FailureException.class)
			.extracting(error -> ((FailureException) error).failure())
			.isEqualTo(Failure.TIMETABLE_IDENTITY_MISMATCH);
	}

	private static StationTimetableSearchService service(RouteTimetableSnapshot snapshot) {
		LoadRouteTimetablePort port = new LoadRouteTimetablePort() {
			@Override public RouteTimetable loadRouteTimetable() { return snapshot.timetable(); }
			@Override public RouteTimetableSnapshot loadStationTimetableSnapshot() { return snapshot; }
		};
		return new StationTimetableSearchService(port, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static RouteTimetableSnapshot snapshot(RouteTimetable timetable, Instant freshUntil) {
		return new RouteTimetableSnapshot("cache", "artifact", new PlannerIdentity("a".repeat(64), "b".repeat(64), "c".repeat(64),
			"sha256:" + "d".repeat(64), "d".repeat(64), "e".repeat(64), "f".repeat(64)), freshUntil, timetable);
	}

	private static SearchRequest request(Selector selector) { return new SearchRequest("station", "line", selector); }
	private static ServiceCalendar calendar() {
		return new ServiceCalendar("weekday", true, true, true, true, true, true, true,
			LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul");
	}
	private static RouteTimetable timetable() { return timetable(List.of(calendar()), List.of(), List.of(), List.of()); }
	private static RouteTimetable timetable(
		List<ServiceCalendar> calendars, List<ServiceCalendarDate> dates, List<TransitFrequency> frequencies, List<TransitTrip> extraTrips
	) {
		List<TransitTrip> trips = new java.util.ArrayList<>();
		trips.add(new TransitTrip("trip", "route", "weekday", "headsign", "0", "SUBWAY", "LOCAL", null, 0));
		trips.addAll(extraTrips);
		List<TransitStopTime> stops = new java.util.ArrayList<>();
		for (TransitTrip trip : trips) stops.add(new TransitStopTime(trip.id(), 1, "station", "line", 32_400, 32_400, 0, 0));
		return new RouteTimetable(calendars, dates, List.of(new TransitRoute("route", "line", "L", "line", "direction", "Asia/Seoul")),
			trips, stops, frequencies, List.of(), null,
			new RouteAccessData(List.of(new PathwayNode("platform", "station", "line", "PLATFORM")), List.of(), List.of(), List.of()));
	}
}
