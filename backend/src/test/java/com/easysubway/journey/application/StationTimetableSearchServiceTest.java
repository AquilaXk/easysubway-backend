package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
	void frequencyWithoutExactTimesDoesNotInventAPreciseDeparture() {
		RouteTimetable timetable = timetable(List.of(calendar()), List.of(), List.of(
			new TransitFrequency("trip", 32_700, 33_300, 300, false)), List.of());

		var result = service(snapshot(timetable, NOW.plusSeconds(60))).search(request(
			new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))));

		assertThat(result.directionGroups()).isEmpty();
	}

	@Test
	void pickupForbiddenStopDoesNotAppearAsADeparture() {
		RouteTimetable template = timetable();
		RouteTimetable timetable = new RouteTimetable(template.serviceCalendars(), template.serviceCalendarDates(),
			template.transitRoutes(), template.transitTrips(),
			List.of(new TransitStopTime("trip", 1, "station", "line", 32_400, 32_400, 1, 0)),
			template.transitFrequencies(), template.officialFares(), template.feedEndDate(), template.routeAccessData());

		var result = service(snapshot(timetable, NOW.plusSeconds(60))).search(request(
			new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))));

		assertThat(result.directionGroups()).isEmpty();
	}

	@Test
	void dateAfterFeedEndFailsAsStale() {
		RouteTimetable template = timetable();
		RouteTimetable timetable = new RouteTimetable(template.serviceCalendars(), template.serviceCalendarDates(),
			template.transitRoutes(), template.transitTrips(), template.transitStopTimes(), template.transitFrequencies(),
			template.officialFares(), LocalDate.parse("2026-08-23"), template.routeAccessData());

		assertThatThrownBy(() -> service(snapshot(timetable, NOW.plusSeconds(60))).search(request(
			new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24")))))
			.isInstanceOf(FailureException.class)
			.extracting(error -> ((FailureException) error).failure())
			.isEqualTo(Failure.TIMETABLE_STALE);
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

	@Test
	void failsClosedForLoaderNullSnapshotIdentityAndFreshnessStates() {
		LoadRouteTimetablePort throwing = new LoadRouteTimetablePort() {
			@Override public RouteTimetable loadRouteTimetable() { return timetable(); }
			@Override public RouteTimetableSnapshot loadStationTimetableSnapshot() { throw new IllegalStateException("unavailable"); }
		};
		LoadRouteTimetablePort nullSnapshot = new LoadRouteTimetablePort() {
			@Override public RouteTimetable loadRouteTimetable() { return timetable(); }
			@Override public RouteTimetableSnapshot loadStationTimetableSnapshot() { return null; }
		};
		assertFailure(service(throwing), request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_UNAVAILABLE);
		assertFailure(service(nullSnapshot), request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_UNAVAILABLE);
		assertFailure(service(new RouteTimetableSnapshot("cache", null, null, null, timetable())), request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_UNAVAILABLE);
		assertFailure(service(new RouteTimetableSnapshot("cache", "artifact", null, null, timetable())), request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
		assertFailure(service(new RouteTimetableSnapshot("cache", null, snapshot(timetable(), NOW.plusSeconds(60)).plannerIdentity(), null, timetable())), request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
		assertFailure(service(new RouteTimetableSnapshot("cache", null, null, NOW.plusSeconds(60), timetable())), request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
		assertFailure(service(new RouteTimetableSnapshot("cache", "artifact", null, NOW.plusSeconds(60), timetable())), request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
		assertFailure(service(snapshot(timetable(), NOW)), request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_STALE);
	}

	@Test
	void rejectsMissingStationDuplicateIdsAndInvalidRouteReferences() {
		RouteTimetable base = timetable();
		RouteTimetable missingStation = new RouteTimetable(base.serviceCalendars(), base.serviceCalendarDates(), base.transitRoutes(),
			base.transitTrips(), base.transitStopTimes(), base.transitFrequencies(), base.officialFares(), base.feedEndDate(),
			new RouteAccessData(List.of(new PathwayNode("other", "other", "line", "PLATFORM")), List.of(), List.of(), List.of()));
		assertFailure(service(snapshot(missingStation, NOW.plusSeconds(60))), request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.STATION_LINE_NOT_FOUND);
		RouteTimetable duplicateTrip = timetable(List.of(calendar()), List.of(), List.of(), List.of(
			new TransitTrip("trip", "route", "weekday", "headsign", "0", "SUBWAY", "LOCAL", null, 0)));
		assertFailure(service(snapshot(duplicateTrip, NOW.plusSeconds(60))), request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
		RouteTimetable duplicateRoute = new RouteTimetable(base.serviceCalendars(), base.serviceCalendarDates(),
			List.of(base.transitRoutes().getFirst(), base.transitRoutes().getFirst()), base.transitTrips(), base.transitStopTimes(),
			base.transitFrequencies(), base.officialFares(), base.feedEndDate(), base.routeAccessData());
		assertFailure(service(snapshot(duplicateRoute, NOW.plusSeconds(60))), request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
		RouteTimetable missingTrip = new RouteTimetable(base.serviceCalendars(), base.serviceCalendarDates(), base.transitRoutes(), List.of(),
			base.transitStopTimes(), base.transitFrequencies(), base.officialFares(), base.feedEndDate(), base.routeAccessData());
		assertFailure(service(snapshot(missingTrip, NOW.plusSeconds(60))), request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
		for (TransitRoute route : List.of(
			new TransitRoute("route", "other", "L", "line", "direction", "Asia/Seoul"),
			new TransitRoute("route", "line", "L", "line", "direction", "UTC"),
			new TransitRoute("route", "line", "L", "line", "", "Asia/Seoul"))) {
			RouteTimetable invalidRoute = new RouteTimetable(base.serviceCalendars(), base.serviceCalendarDates(), List.of(route),
				base.transitTrips(), base.transitStopTimes(), base.transitFrequencies(), base.officialFares(), base.feedEndDate(), base.routeAccessData());
			assertFailure(service(snapshot(invalidRoute, NOW.plusSeconds(60))), request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
		}
	}

	@Test
	void dayTypeWeekendsMismatchAndCalendarIntegrityAreExplicit() {
		var saturday = service(snapshot(timetable(), NOW.plusSeconds(60))).search(request(
			new Selector.DayTypeSelector(DayType.SATURDAY, LocalDate.parse("2026-08-29"))));
		var sunday = service(snapshot(timetable(), NOW.plusSeconds(60))).search(request(
			new Selector.DayTypeSelector(DayType.SUNDAY_HOLIDAY, LocalDate.parse("2026-08-30"))));
		assertThat(saturday.resolvedDayType()).isEqualTo(DayType.SATURDAY);
		assertThat(sunday.resolvedDayType()).isEqualTo(DayType.SUNDAY_HOLIDAY);
		assertFailure(service(snapshot(timetable(), NOW.plusSeconds(60))), request(
			new Selector.DayTypeSelector(DayType.WEEKDAY, LocalDate.parse("2026-08-30"))), Failure.INVALID_JOURNEY_REQUEST);
		RouteTimetable duplicateDate = timetable(List.of(calendar()), List.of(
			new ServiceCalendarDate("weekday", LocalDate.parse("2026-08-24"), 1),
			new ServiceCalendarDate("weekday", LocalDate.parse("2026-08-24"), 2)), List.of(), List.of());
		assertFailure(service(snapshot(duplicateDate, NOW.plusSeconds(60))), request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
		RouteTimetable badTimezone = timetable(List.of(new ServiceCalendar("weekday", true, true, true, true, true, true, true,
			LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "UTC")), List.of(), List.of(), List.of());
		assertFailure(service(snapshot(badTimezone, NOW.plusSeconds(60))), request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
	}

	@Test
	void overrideCardinalityAndFrequencyArithmeticFailClosed() {
		List<ServiceCalendar> calendars = List.of(
			new ServiceCalendar("weekday", true, true, true, true, true, false, false, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"),
			new ServiceCalendar("added", false, false, false, false, false, true, false, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"),
			new ServiceCalendar("also-added", false, false, false, false, false, false, true, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"));
		RouteTimetable conflicting = timetable(calendars, List.of(
			new ServiceCalendarDate("weekday", LocalDate.parse("2026-08-25"), 2),
			new ServiceCalendarDate("added", LocalDate.parse("2026-08-25"), 1),
			new ServiceCalendarDate("also-added", LocalDate.parse("2026-08-25"), 1)), List.of(), List.of());
		assertFailure(service(snapshot(conflicting, NOW.plusSeconds(60))), request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-25"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
		RouteTimetable malformedFrequency = timetable(List.of(calendar()), List.of(), List.of(
			new TransitFrequency("trip", 107_998, 107_999, Integer.MAX_VALUE, true)), List.of());
		assertFailure(service(snapshot(malformedFrequency, NOW.plusSeconds(60))), request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
	}

	@Test
	void invalidPlannerIdentityRequestAndNextBoundsAreRejected() {
		RouteTimetable base = timetable();
		PlannerIdentity invalid = new PlannerIdentity("bad", "b".repeat(64), "c".repeat(64), "version", "d".repeat(64), "e".repeat(64), "f".repeat(64));
		assertFailure(service(new RouteTimetableSnapshot("cache", "artifact", invalid, NOW.plusSeconds(60), base)),
			request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
		for (String[] ids : List.of(new String[]{" ", "line"}, new String[]{"station", " "})) {
			assertThatThrownBy(() -> new SearchRequest(ids[0], ids[1], new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))))
				.isInstanceOf(FailureException.class);
		}
		for (int horizon : List.of(0, 9)) {
			assertThatThrownBy(() -> new Selector.NextDeparturesSelector(NOW, horizon))
				.isInstanceOf(FailureException.class)
				.extracting(error -> ((FailureException) error).failure()).isEqualTo(Failure.INVALID_JOURNEY_REQUEST);
		}
	}

	@Test
	void coversSnapshotAndSourceIdentityComponentBranches() {
		RouteTimetable base = timetable();
		assertFailure(service(new RouteTimetableSnapshot("cache", "artifact", snapshot(base, NOW.plusSeconds(60)).plannerIdentity(), NOW.plusSeconds(60), null)),
			request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_UNAVAILABLE);
		PlannerIdentity valid = snapshot(base, NOW.plusSeconds(60)).plannerIdentity();
		for (PlannerIdentity identity : List.of(
			new PlannerIdentity(null, valid.canonicalPackSha256(), valid.canonicalPackSqliteSha256(), valid.canonicalStationVersion(), valid.canonicalStationSetSha256(), valid.sourceLineageSha256(), valid.evidenceHash()),
			new PlannerIdentity("A".repeat(64), valid.canonicalPackSha256(), valid.canonicalPackSqliteSha256(), valid.canonicalStationVersion(), valid.canonicalStationSetSha256(), valid.sourceLineageSha256(), valid.evidenceHash()),
			new PlannerIdentity(valid.timetableSnapshotSha256(), valid.canonicalPackSha256(), valid.canonicalPackSqliteSha256(), " ", valid.canonicalStationSetSha256(), valid.sourceLineageSha256(), valid.evidenceHash()),
			new PlannerIdentity(valid.timetableSnapshotSha256(), valid.canonicalPackSha256(), valid.canonicalPackSqliteSha256(), valid.canonicalStationVersion(), "bad", valid.sourceLineageSha256(), valid.evidenceHash()),
			new PlannerIdentity(valid.timetableSnapshotSha256(), valid.canonicalPackSha256(), valid.canonicalPackSqliteSha256(), valid.canonicalStationVersion(), valid.canonicalStationSetSha256(), "bad", valid.evidenceHash()),
			new PlannerIdentity(valid.timetableSnapshotSha256(), valid.canonicalPackSha256(), valid.canonicalPackSqliteSha256(), valid.canonicalStationVersion(), valid.canonicalStationSetSha256(), valid.sourceLineageSha256(), "bad"))) {
			assertFailure(service(new RouteTimetableSnapshot("cache", "artifact", identity, NOW.plusSeconds(60), base)),
				request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
		}
		assertFailure(service(new RouteTimetableSnapshot("cache", " ", valid, NOW.plusSeconds(60), base)),
			request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
	}

	@Test
	void executesEveryServiceDayAndSelectorResolvedDayTypeBranch() {
		StationTimetableSearchService service = service(snapshot(timetable(), NOW.plusSeconds(60)));
		for (LocalDate date : List.of(LocalDate.parse("2026-08-24"), LocalDate.parse("2026-08-25"), LocalDate.parse("2026-08-26"),
			LocalDate.parse("2026-08-27"), LocalDate.parse("2026-08-28"), LocalDate.parse("2026-08-29"), LocalDate.parse("2026-08-30"))) {
			assertThat(service.search(request(new Selector.ServiceDateSelector(date))).resolvedDayType()).isEqualTo(DayType.from(date));
		}
		assertThat(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24")).resolvedDayType()).isEqualTo(DayType.WEEKDAY);
		assertThat(new Selector.DayTypeSelector(DayType.SATURDAY, LocalDate.parse("2026-08-29")).resolvedDayType()).isEqualTo(DayType.SATURDAY);
		assertThat(new Selector.NextDeparturesSelector(NOW, 1).resolvedDayType()).isEqualTo(DayType.WEEKDAY);
		assertThatThrownBy(() -> new Selector.ServiceDateSelector(null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new Selector.DayTypeSelector(null, LocalDate.parse("2026-08-24"))).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new Selector.NextDeparturesSelector(null, 1)).isInstanceOf(NullPointerException.class);
	}

	@Test
	void coversStopPredicateAndRouteDirectionNullBranches() {
		RouteTimetable base = timetable();
		RouteTimetable mixedStops = new RouteTimetable(base.serviceCalendars(), base.serviceCalendarDates(), base.transitRoutes(), base.transitTrips(),
			List.of(new TransitStopTime("trip", 1, "other", "line", 32_400, 32_400, 0, 0),
				new TransitStopTime("trip", 2, "station", "other", 32_400, 32_400, 0, 0), base.transitStopTimes().getFirst()),
			base.transitFrequencies(), base.officialFares(), base.feedEndDate(), base.routeAccessData());
		assertThat(service(snapshot(mixedStops, NOW.plusSeconds(60))).search(request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))))
			.directionGroups()).isNotEmpty();
		RouteTimetable lineMismatchNode = new RouteTimetable(base.serviceCalendars(), base.serviceCalendarDates(), base.transitRoutes(), base.transitTrips(),
			base.transitStopTimes(), base.transitFrequencies(), base.officialFares(), base.feedEndDate(),
			new RouteAccessData(List.of(new PathwayNode("platform", "station", "other", "PLATFORM")), List.of(), List.of(), List.of()));
		assertFailure(service(snapshot(lineMismatchNode, NOW.plusSeconds(60))), request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.STATION_LINE_NOT_FOUND);
		RouteTimetable nullDirection = new RouteTimetable(base.serviceCalendars(), base.serviceCalendarDates(),
			List.of(new TransitRoute("route", "line", "L", "line", null, "Asia/Seoul")), base.transitTrips(), base.transitStopTimes(),
			base.transitFrequencies(), base.officialFares(), base.feedEndDate(), base.routeAccessData());
		assertFailure(service(snapshot(nullDirection, NOW.plusSeconds(60))), request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
		RouteTimetable missingRoute = new RouteTimetable(base.serviceCalendars(), base.serviceCalendarDates(), base.transitRoutes(),
			List.of(new TransitTrip("trip", "missing", "weekday", "headsign", "0", "SUBWAY", "LOCAL", null, 0)), base.transitStopTimes(),
			base.transitFrequencies(), base.officialFares(), base.feedEndDate(), base.routeAccessData());
		assertFailure(service(snapshot(missingRoute, NOW.plusSeconds(60))), request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
	}

	@Test
	void privateFrequencyDefensesFailClosedAtTheirOwnBoundaries() {
		assertThat(invokeStatic("validFrequencyInstance", new Class<?>[]{List.class, int.class}, null, 0)).isEqualTo(false);
		assertThat(invokeStatic("validFrequencyInstance", new Class<?>[]{List.class, int.class}, List.of(), 0)).isEqualTo(false);
		TransitStopTime stop = new TransitStopTime("trip", 1, "station", "line", 0, 1, 0, 0);
		assertThat(invokeStatic("validFrequencyInstance", new Class<?>[]{List.class, int.class}, List.of(stop), Integer.MAX_VALUE)).isEqualTo(false);
		assertThatThrownBy(() -> invokeStatic("nextFrequencyBase", new Class<?>[]{int.class, int.class}, Integer.MAX_VALUE, 1))
			.isInstanceOf(FailureException.class)
			.extracting(error -> ((FailureException) error).failure()).isEqualTo(Failure.TIMETABLE_IDENTITY_MISMATCH);
	}

	@Test
	void privateCalendarSignatureCoversEveryRecognizedAndNeutralPattern() {
		for (int pattern = 0; pattern < 128; pattern++) {
			invokeStatic("calendarSignature", new Class<?>[]{ServiceCalendar.class}, calendarPattern(pattern));
		}
		assertThat(invokeStatic("calendarSignature", new Class<?>[]{ServiceCalendar.class}, calendarPattern(31))).isEqualTo(DayType.WEEKDAY);
		assertThat(invokeStatic("calendarSignature", new Class<?>[]{ServiceCalendar.class}, calendarPattern(32))).isEqualTo(DayType.SATURDAY);
		assertThat(invokeStatic("calendarSignature", new Class<?>[]{ServiceCalendar.class}, calendarPattern(64))).isEqualTo(DayType.SUNDAY_HOLIDAY);
		for (java.time.DayOfWeek day : java.time.DayOfWeek.values()) {
			assertThat((Boolean) invokeStatic("runsOn", new Class<?>[]{ServiceCalendar.class, java.time.DayOfWeek.class}, calendar(), day)).isTrue();
		}
	}

	@Test
	void corruptedStopChangingTripIdFailsWhenItsGroupedStopsDisappear() {
		TransitStopTime stop = mock(TransitStopTime.class);
		stubCorruptedStop(stop);
		when(stop.tripId()).thenReturn("other-trip", "trip", "trip", "trip");
		when(stop.departureSeconds()).thenReturn(32_400);
		RouteTimetable corrupted = corruptedTimetable(stop, new TransitFrequency("trip", 32_400, 32_401, 1, true));

		assertFailure(service(snapshot(corrupted, NOW.plusSeconds(60))), request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))),
			Failure.TIMETABLE_IDENTITY_MISMATCH);
	}

	@Test
	void corruptedFrequencyAndStopBoundsFailOnSubtractionOverflow() {
		TransitStopTime stop = mock(TransitStopTime.class);
		stubCorruptedStop(stop);
		when(stop.departureSeconds()).thenReturn(Integer.MAX_VALUE);
		TransitFrequency frequency = mock(TransitFrequency.class);
		when(frequency.tripId()).thenReturn("trip");
		when(frequency.startTimeSeconds()).thenReturn(Integer.MIN_VALUE);
		when(frequency.endTimeSeconds()).thenReturn(Integer.MAX_VALUE);
		when(frequency.headwaySeconds()).thenReturn(1);
		when(frequency.exactTimes()).thenReturn(true);

		assertFailure(service(snapshot(corruptedTimetable(stop, frequency), NOW.plusSeconds(60))),
			request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
	}

	@Test
	void corruptedStopFailsOnPostValidationAdditionOverflow() {
		TransitStopTime stop = mock(TransitStopTime.class);
		stubCorruptedStop(stop);
		when(stop.departureSeconds()).thenReturn(0, 0, Integer.MAX_VALUE);
		TransitFrequency frequency = new TransitFrequency("trip", 1, 2, 1, true);

		assertFailure(service(snapshot(corruptedTimetable(stop, frequency), NOW.plusSeconds(60))),
			request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
	}

	@Test
	void corruptedStopFailsWhenPostValidationDepartureExceedsServiceDay() {
		TransitStopTime stop = mock(TransitStopTime.class);
		stubCorruptedStop(stop);
		when(stop.departureSeconds()).thenReturn(0, 0, 107_999);
		TransitFrequency frequency = new TransitFrequency("trip", 1, 2, 1, true);

		assertFailure(service(snapshot(corruptedTimetable(stop, frequency), NOW.plusSeconds(60))),
			request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
	}

	@Test
	void corruptedStopFailsWhenPostValidationDepartureIsNegative() {
		TransitStopTime stop = mock(TransitStopTime.class);
		stubCorruptedStop(stop);
		when(stop.arrivalSeconds()).thenReturn(1);
		when(stop.departureSeconds()).thenReturn(1, 1, 0);

		assertFailure(service(snapshot(corruptedTimetable(stop, new TransitFrequency("trip", 0, 1, 1, true)), NOW.plusSeconds(60))),
			request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
	}

	@Test
	void privateFrequencyValidationCoversEachFailClosedOrderingOutcome() {
		TransitStopTime negativeArrival = mock(TransitStopTime.class);
		when(negativeArrival.arrivalSeconds()).thenReturn(0);
		when(negativeArrival.departureSeconds()).thenReturn(0);
		assertThat(invokeStatic("validFrequencyInstance", new Class<?>[]{List.class, int.class}, List.of(negativeArrival), -1)).isEqualTo(false);
		TransitStopTime inverted = mock(TransitStopTime.class);
		when(inverted.arrivalSeconds()).thenReturn(1);
		when(inverted.departureSeconds()).thenReturn(0);
		assertThat(invokeStatic("validFrequencyInstance", new Class<?>[]{List.class, int.class}, List.of(inverted), 0)).isEqualTo(false);
		TransitStopTime afterEnd = mock(TransitStopTime.class);
		when(afterEnd.arrivalSeconds()).thenReturn(0);
		when(afterEnd.departureSeconds()).thenReturn(107_999);
		assertThat(invokeStatic("validFrequencyInstance", new Class<?>[]{List.class, int.class}, List.of(afterEnd), 1)).isEqualTo(false);
	}

	@Test
	void feedCalendarExceptionsAndNextDirectionSelectionCoverRemainingOutcomes() {
		RouteTimetable base = timetable();
		RouteTimetable feedBoundary = new RouteTimetable(base.serviceCalendars(), base.serviceCalendarDates(), base.transitRoutes(), base.transitTrips(),
			base.transitStopTimes(), base.transitFrequencies(), base.officialFares(), LocalDate.parse("2026-08-24"), base.routeAccessData());
		assertThat(service(snapshot(feedBoundary, NOW.plusSeconds(60))).search(request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24")))).directionGroups()).isNotEmpty();
		RouteTimetable offDateException = timetable(List.of(new ServiceCalendar("weekday", true, true, true, true, true, true, true,
			LocalDate.parse("2026-08-25"), LocalDate.parse("2026-08-29"), "Asia/Seoul")),
			List.of(new ServiceCalendarDate("weekday", LocalDate.parse("2026-08-23"), 1)), List.of(), List.of());
		assertThat(service(snapshot(offDateException, NOW.plusSeconds(60))).search(request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24")))).directionGroups()).isEmpty();
		RouteTimetable nextOrder = new RouteTimetable(base.serviceCalendars(), base.serviceCalendarDates(), base.transitRoutes(),
			List.of(new TransitTrip("late", "route", "weekday", "headsign", "0", "SUBWAY", "LOCAL", null, 0),
				new TransitTrip("early", "route", "weekday", "headsign", "0", "SUBWAY", "LOCAL", null, 0)),
			List.of(new TransitStopTime("late", 1, "station", "line", 33_000, 33_000, 0, 0),
				new TransitStopTime("early", 1, "station", "line", 32_400, 32_400, 0, 0)), List.of(), List.of(), null, base.routeAccessData());
		assertThat(service(snapshot(nextOrder, NOW.plusSeconds(60))).search(request(new Selector.NextDeparturesSelector(Instant.parse("2026-08-24T00:00:00Z"), 1)))
			.directionGroups().getFirst().departures().getFirst().secondsFromServiceDayStart()).isEqualTo(32_400);
	}

	@Test
	void calendarShortCircuitOutcomesRemainFailClosedOrNeutral() {
		RouteTimetable timezoneMismatch = timetable(List.of(new ServiceCalendar("weekday", true, true, true, true, true, true, true,
			LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "UTC")), List.of(), List.of(), List.of());
		assertFailure(service(snapshot(timezoneMismatch, NOW.plusSeconds(60))), request(new Selector.NextDeparturesSelector(NOW, 1)), Failure.TIMETABLE_IDENTITY_MISMATCH);
		RouteTimetable afterCalendarEnd = timetable(List.of(new ServiceCalendar("weekday", true, true, true, true, true, true, true,
			LocalDate.parse("2026-01-01"), LocalDate.parse("2026-08-23"), "Asia/Seoul")), List.of(), List.of(), List.of());
		assertThat(service(snapshot(afterCalendarEnd, NOW.plusSeconds(60))).search(request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24")))).directionGroups()).isEmpty();
		RouteTimetable removedNonCivil = timetable(List.of(
			new ServiceCalendar("weekday", true, true, true, true, true, false, false, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"),
			new ServiceCalendar("sunday", false, false, false, false, false, false, true, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul")),
			List.of(new ServiceCalendarDate("sunday", LocalDate.parse("2026-08-24"), 2)), List.of(), List.of());
		assertThat(service(snapshot(removedNonCivil, NOW.plusSeconds(60))).search(request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24")))).resolvedDayType()).isEqualTo(DayType.WEEKDAY);
		RouteTimetable removedWithDuplicateReplacement = timetable(List.of(
			new ServiceCalendar("weekday", true, true, true, true, true, false, false, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"),
			new ServiceCalendar("added", false, false, false, false, false, true, false, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"),
			new ServiceCalendar("added", false, false, false, false, false, true, false, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul")),
			List.of(new ServiceCalendarDate("weekday", LocalDate.parse("2026-08-24"), 2), new ServiceCalendarDate("added", LocalDate.parse("2026-08-24"), 1)), List.of(), List.of());
		assertFailure(service(snapshot(removedWithDuplicateReplacement, NOW.plusSeconds(60))), request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
		assertThat(invokeStatic("text", new Class<?>[]{String.class}, (Object) null)).isEqualTo(false);
	}

	@Test
	void calendarExceptionMatchingCoversMissingDuplicateAndRemovalOnlyBranches() {
		ServiceCalendar weekday = new ServiceCalendar("weekday", true, true, true, true, true, false, false,
			LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul");
		for (RouteTimetable neutral : List.of(
			timetable(List.of(weekday), List.of(new ServiceCalendarDate("missing", LocalDate.parse("2026-08-24"), 2)), List.of(), List.of()),
			timetable(List.of(weekday, weekday), List.of(new ServiceCalendarDate("weekday", LocalDate.parse("2026-08-24"), 2)), List.of(), List.of()))) {
			assertThat(service(snapshot(neutral, NOW.plusSeconds(60))).search(request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24")))).resolvedDayType())
				.isEqualTo(DayType.WEEKDAY);
		}
		RouteTimetable removalOnly = timetable(List.of(weekday), List.of(
			new ServiceCalendarDate("weekday", LocalDate.parse("2026-08-24"), 2),
			new ServiceCalendarDate("weekday", LocalDate.parse("2026-08-23"), 1)), List.of(), List.of());
		assertFailure(service(snapshot(removalOnly, NOW.plusSeconds(60))), request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
	}

	private static StationTimetableSearchService service(RouteTimetableSnapshot snapshot) {
		LoadRouteTimetablePort port = new LoadRouteTimetablePort() {
			@Override public RouteTimetable loadRouteTimetable() { return snapshot.timetable(); }
			@Override public RouteTimetableSnapshot loadStationTimetableSnapshot() { return snapshot; }
		};
		return new StationTimetableSearchService(port, Clock.fixed(NOW, ZoneOffset.UTC));
	}
	private static StationTimetableSearchService service(LoadRouteTimetablePort port) {
		return new StationTimetableSearchService(port, Clock.fixed(NOW, ZoneOffset.UTC));
	}
	private static void assertFailure(StationTimetableSearchService service, SearchRequest request, Failure expected) {
		assertThatThrownBy(() -> service.search(request)).isInstanceOf(FailureException.class)
			.extracting(error -> ((FailureException) error).failure()).isEqualTo(expected);
	}
	private static Object invokeStatic(String name, Class<?>[] parameters, Object... arguments) {
		try {
			Method method = StationTimetableSearchService.class.getDeclaredMethod(name, parameters);
			method.setAccessible(true);
			return method.invoke(null, arguments);
		} catch (InvocationTargetException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof RuntimeException runtime) throw runtime;
			throw new AssertionError(cause);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
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
	private static RouteTimetable corruptedTimetable(TransitStopTime stop, TransitFrequency frequency) {
		return new RouteTimetable(List.of(calendar()), List.of(),
			List.of(new TransitRoute("route", "line", "L", "line", "direction", "Asia/Seoul")),
			List.of(new TransitTrip("trip", "route", "weekday", "headsign", "0", "SUBWAY", "LOCAL", null, 0)),
			List.of(stop), List.of(frequency), List.of(), null,
			new RouteAccessData(List.of(new PathwayNode("platform", "station", "line", "PLATFORM")), List.of(), List.of(), List.of()));
	}
	private static void stubCorruptedStop(TransitStopTime stop) {
		when(stop.tripId()).thenReturn("trip");
		when(stop.stopSequence()).thenReturn(1);
		when(stop.stationId()).thenReturn("station");
		when(stop.lineId()).thenReturn("line");
		when(stop.arrivalSeconds()).thenReturn(0);
		when(stop.pickupType()).thenReturn(0);
	}
	private static ServiceCalendar calendarPattern(int pattern) {
		return new ServiceCalendar("pattern-" + pattern, (pattern & 1) != 0, (pattern & 2) != 0, (pattern & 4) != 0,
			(pattern & 8) != 0, (pattern & 16) != 0, (pattern & 32) != 0, (pattern & 64) != 0,
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
