package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.easysubway.journey.application.StationTimetableSearchService.DayType;
import com.easysubway.journey.application.StationTimetableSearchService.Failure;
import com.easysubway.journey.application.StationTimetableSearchService.FailureDetail;
import com.easysubway.journey.application.StationTimetableSearchService.FailureException;
import com.easysubway.journey.application.StationTimetableSearchService.SearchRequest;
import com.easysubway.journey.application.StationTimetableSearchService.Selector;
import com.easysubway.journey.application.StationTimetableSearchService.SourceIdentity;
import com.easysubway.journey.application.StationTimetableSnapshotPort.StationLine;
import com.easysubway.journey.application.StationTimetableSnapshotPort.StationTimetableSnapshot;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteAccessData;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
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
import java.util.Set;
import org.junit.jupiter.api.Test;

class StationTimetableSearchServiceTest {
	private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");

	@Test
	void sundayOnlyServiceAddedOnlyForAnUnrelatedAgencyMarksAHolidayGapForThisStationLine() {
		// #476 F2: 다른 서비스만 휴일 달력을 더한 평일에 이 역·노선은 예외가 없으므로 평일 시간표를 성공으로 내지 않는다.
		RouteTimetable timetable = timetable(List.of(
			new ServiceCalendar("weekday", true, true, true, true, true, false, false,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"),
			new ServiceCalendar("added", false, false, false, false, false, false, true,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul")),
			List.of(new ServiceCalendarDate("added", LocalDate.parse("2026-08-25"), 1)),
			List.of(), List.of());

		assertThatThrownBy(() -> service(snapshot(timetable, NOW.plusSeconds(60))).search(request(new Selector.ServiceDateSelector(
			LocalDate.parse("2026-08-25")))))
			.isInstanceOf(FailureException.class)
			.satisfies(error -> assertThat(((FailureException) error).detail()).isEqualTo(FailureDetail.HOLIDAY_CALENDAR_EXCEPTION_MISSING))
			.extracting(error -> ((FailureException) error).failure())
			.isEqualTo(Failure.TIMETABLE_NOT_COVERED);
	}

	@Test
	void removedCivilServiceWithOneAddedSundaySignatureOverridesDayType() {
		RouteTimetable timetable = timetable(List.of(
			new ServiceCalendar("weekday", true, true, true, true, true, false, false,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"),
			new ServiceCalendar("added", false, false, false, false, false, false, true,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul")),
			List.of(new ServiceCalendarDate("weekday", LocalDate.parse("2026-08-25"), 2),
				new ServiceCalendarDate("added", LocalDate.parse("2026-08-25"), 1)), List.of(),
			List.of(new TransitTrip("holiday-trip", "route", "added", "headsign", "0", "SUBWAY", "LOCAL", null, 0)));
		var result = service(snapshot(timetable, NOW.plusSeconds(60))).search(request(new Selector.ServiceDateSelector(
			LocalDate.parse("2026-08-25"))));
		assertThat(result.resolvedDayType()).isEqualTo(DayType.SUNDAY_HOLIDAY);
	}

	@Test
	void weekdayHolidayServedByAWeekendHolidayCalendarResolvesSundayHolidayAndItsTrips() {
		// #476: 서버 경로 번들은 토요일 시간표가 없는 기관의 휴일 시간표를 토·일 운행 달력으로 싣고, 평일 공휴일에는
		// 평일 달력을 빼고(2) 그 달력을 더한다(1). 2026-10-09(금, 한글날)가 실제 seq126 번들의 이 형태다.
		RouteTimetable timetable = timetable(List.of(
			new ServiceCalendar("weekday", true, true, true, true, true, false, false,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"),
			new ServiceCalendar("weekend-holiday", false, false, false, false, false, true, true,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul")),
			List.of(new ServiceCalendarDate("weekday", LocalDate.parse("2026-10-09"), 2),
				new ServiceCalendarDate("weekend-holiday", LocalDate.parse("2026-10-09"), 1)), List.of(),
			List.of(new TransitTrip("holiday-trip", "route", "weekend-holiday", "headsign", "0", "SUBWAY", "EXPRESS", null, 0)));

		var result = service(snapshot(timetable, Instant.parse("2026-10-10T00:00:00Z"))).search(request(
			new Selector.ServiceDateSelector(LocalDate.parse("2026-10-09"))));

		assertThat(result.resolvedDayType()).isEqualTo(DayType.SUNDAY_HOLIDAY);
		assertThat(result.directionGroups()).singleElement().satisfies(group -> assertThat(group.departures())
			.extracting(StationTimetableSearchService.Departure::servicePattern).containsExactly("EXPRESS"));
	}

	@Test
	void saturdayHolidayResolvesSundayHolidayFromTheBundleExceptions() {
		// 2026-10-03(토, 개천절): 토요일 달력을 빼고 일요일·공휴일 달력을 더한다(명절·공휴일 우선).
		RouteTimetable timetable = timetable(List.of(
			new ServiceCalendar("saturday", false, false, false, false, false, true, false,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"),
			new ServiceCalendar("sunday-holiday", false, false, false, false, false, false, true,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul")),
			List.of(new ServiceCalendarDate("saturday", LocalDate.parse("2026-10-03"), 2),
				new ServiceCalendarDate("sunday-holiday", LocalDate.parse("2026-10-03"), 1)), List.of(),
			List.of(new TransitTrip("holiday-trip", "route", "sunday-holiday", "headsign", "0", "SUBWAY", "EXPRESS", null, 0),
				new TransitTrip("saturday-trip", "route", "saturday", "headsign", "0", "SUBWAY", "EXPRESS", null, 0)));

		var result = service(snapshot(timetable, Instant.parse("2026-10-10T00:00:00Z"))).search(request(
			new Selector.ServiceDateSelector(LocalDate.parse("2026-10-03"))));

		assertThat(result.resolvedDayType()).isEqualTo(DayType.SUNDAY_HOLIDAY);
		assertThat(result.directionGroups()).singleElement().satisfies(group -> assertThat(group.departures())
			.extracting(StationTimetableSearchService.Departure::servicePattern).containsExactly("EXPRESS"));
	}

	@Test
	void saturdayOnAnAgencyWithoutSaturdayTimetableIsLabelledByTheHolidayCalendarItActuallyUses() {
		// #476 F1: 토요일 시간표가 없는 기관은 토요일에 휴일 시간표를 쓴다(QA 정책). 라벨도 실제로 쓴 달력 종류(휴일)다.
		RouteTimetable timetable = timetable(List.of(
			new ServiceCalendar("weekday", true, true, true, true, true, false, false,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"),
			new ServiceCalendar("weekend-holiday", false, false, false, false, false, true, true,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul")),
			List.of(), List.of(),
			List.of(new TransitTrip("holiday-trip", "route", "weekend-holiday", "headsign", "0", "SUBWAY", "EXPRESS", null, 0)));
		StationTimetableSearchService service = service(snapshot(timetable, Instant.parse("2026-10-12T00:00:00Z")));

		var byDate = service.search(request(new Selector.ServiceDateSelector(LocalDate.parse("2026-10-10"))));
		assertThat(byDate.resolvedDayType()).isEqualTo(DayType.SUNDAY_HOLIDAY);
		assertThat(byDate.directionGroups()).singleElement().satisfies(group -> assertThat(group.departures())
			.extracting(StationTimetableSearchService.Departure::servicePattern).containsExactly("EXPRESS"));

		// 토요일 날짜로 휴일 시간표를 요청하면 정상 처리하고, 토요일 시간표를 요청하면 그 날 쓰는 시간표가 아니므로 거절한다.
		assertThat(service.search(request(new Selector.DayTypeSelector(DayType.SUNDAY_HOLIDAY, LocalDate.parse("2026-10-10"))))
			.resolvedDayType()).isEqualTo(DayType.SUNDAY_HOLIDAY);
		assertFailure(service, request(new Selector.DayTypeSelector(DayType.SATURDAY, LocalDate.parse("2026-10-10"))),
			Failure.INVALID_JOURNEY_REQUEST);

		// 다음 출발도 같은 기준이다(2026-10-10 09:00 KST).
		assertThat(service.search(request(new Selector.NextDeparturesSelector(Instant.parse("2026-10-10T00:00:00Z"), 1)))
			.resolvedDayType()).isEqualTo(DayType.SUNDAY_HOLIDAY);
		// 평일은 평일 달력이다.
		assertThat(service.search(request(new Selector.ServiceDateSelector(LocalDate.parse("2026-10-12")))).resolvedDayType())
			.isEqualTo(DayType.WEEKDAY);
	}

	@Test
	void holidayMarkedOnlyInOtherAgencyCalendarsIsNotServedAsAWeekdayTimetable() {
		// #476 F2: seq126에서 대구·부산·대전 달력에는 공휴일 예외가 없다(data#919). 2026-10-09(금, 한글날)는 다른 기관 달력에
		// 휴일 예외가 있는데 이 역·노선 달력에는 없으므로, 평일 시간표를 성공으로 내지 않고 명시적으로 범위 밖으로 답한다.
		List<ServiceCalendar> calendars = List.of(
			new ServiceCalendar("weekday", true, true, true, true, true, false, false,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"),
			new ServiceCalendar("holiday", false, false, false, false, false, false, true,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"),
			new ServiceCalendar("other-weekday", true, true, true, true, true, false, false,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"),
			new ServiceCalendar("other-holiday", false, false, false, false, false, true, true,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"));
		List<ServiceCalendarDate> otherAgencyOnly = List.of(
			new ServiceCalendarDate("other-weekday", LocalDate.parse("2026-10-09"), 2),
			new ServiceCalendarDate("other-holiday", LocalDate.parse("2026-10-09"), 1));
		List<TransitTrip> holidayTrip = List.of(
			new TransitTrip("holiday-trip", "route", "holiday", "headsign", "0", "SUBWAY", "EXPRESS", null, 0));
		StationTimetableSearchService gap = service(snapshot(timetable(calendars, otherAgencyOnly, List.of(), holidayTrip),
			Instant.parse("2026-10-12T00:00:00Z")));

		for (Selector selector : List.<Selector>of(
			new Selector.ServiceDateSelector(LocalDate.parse("2026-10-09")),
			new Selector.DayTypeSelector(DayType.WEEKDAY, LocalDate.parse("2026-10-09")),
			new Selector.NextDeparturesSelector(Instant.parse("2026-10-08T23:00:00Z"), 1))) {
			assertThatThrownBy(() -> gap.search(request(selector)))
				.isInstanceOf(FailureException.class)
				.satisfies(error -> {
					assertThat(((FailureException) error).failure()).isEqualTo(Failure.TIMETABLE_NOT_COVERED);
					assertThat(((FailureException) error).detail()).isEqualTo(FailureDetail.HOLIDAY_CALENDAR_EXCEPTION_MISSING);
				});
		}
		// 평일인 다른 날은 그대로 평일 시간표다.
		assertThat(gap.search(request(new Selector.ServiceDateSelector(LocalDate.parse("2026-10-08")))).resolvedDayType())
			.isEqualTo(DayType.WEEKDAY);

		// 이 역·노선 달력에도 휴일 예외가 실리면(data#919 해결) 휴일 시간표로 정상 응답한다.
		List<ServiceCalendarDate> fixed = new java.util.ArrayList<>(otherAgencyOnly);
		fixed.add(new ServiceCalendarDate("weekday", LocalDate.parse("2026-10-09"), 2));
		fixed.add(new ServiceCalendarDate("holiday", LocalDate.parse("2026-10-09"), 1));
		var served = service(snapshot(timetable(calendars, fixed, List.of(), holidayTrip), Instant.parse("2026-10-12T00:00:00Z")))
			.search(request(new Selector.ServiceDateSelector(LocalDate.parse("2026-10-09"))));
		assertThat(served.resolvedDayType()).isEqualTo(DayType.SUNDAY_HOLIDAY);
		assertThat(served.directionGroups()).singleElement().satisfies(group -> assertThat(group.departures())
			.extracting(StationTimetableSearchService.Departure::servicePattern).containsExactly("EXPRESS"));
	}

	@Test
	void holidayElsewhereDoesNotFailAStationLineAlreadyOnItsHolidayCalendar() {
		// 토요일 시간표가 없는 기관은 토요일 공휴일(2026-10-03)에도 이미 휴일 달력을 쓰므로 자기 예외가 없어도 빠진 것이 아니다.
		List<ServiceCalendar> calendars = List.of(
			new ServiceCalendar("weekday", true, true, true, true, true, false, false,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"),
			new ServiceCalendar("weekend-holiday", false, false, false, false, false, true, true,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"),
			new ServiceCalendar("other-holiday", false, false, false, false, false, false, true,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"));
		var result = service(snapshot(timetable(calendars,
			List.of(new ServiceCalendarDate("other-holiday", LocalDate.parse("2026-10-03"), 1)), List.of(),
			List.of(new TransitTrip("holiday-trip", "route", "weekend-holiday", "headsign", "0", "SUBWAY", "EXPRESS", null, 0))),
			Instant.parse("2026-10-12T00:00:00Z"))).search(request(new Selector.ServiceDateSelector(LocalDate.parse("2026-10-03"))));
		assertThat(result.resolvedDayType()).isEqualTo(DayType.SUNDAY_HOLIDAY);
	}

	@Test
	void stationLineServedByCalendarsOfDifferentClassesOnOneDateFailsClosed() {
		RouteTimetable timetable = timetable(List.of(
			new ServiceCalendar("weekday", true, true, true, true, true, false, false,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"),
			new ServiceCalendar("saturday", false, false, false, false, false, true, false,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"),
			new ServiceCalendar("weekend-holiday", false, false, false, false, false, true, true,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul")),
			List.of(), List.of(),
			List.of(new TransitTrip("saturday-trip", "route", "saturday", "headsign", "0", "SUBWAY", "EXPRESS", null, 0),
				new TransitTrip("holiday-trip", "route", "weekend-holiday", "headsign", "0", "SUBWAY", "LOCAL", null, 0)));
		assertFailure(service(snapshot(timetable, Instant.parse("2026-10-12T00:00:00Z"))),
			request(new Selector.ServiceDateSelector(LocalDate.parse("2026-10-10"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
	}

	@Test
	void dayTypeIgnoresCalendarExceptionsOfServicesThatDoNotServeTheStationLine() {
		// 전국 번들에는 여러 기관의 달력이 함께 있다. 요청 역·노선을 지나는 열차의 달력만 요일 판정에 쓴다.
		RouteTimetable timetable = timetable(List.of(
			new ServiceCalendar("weekday", true, true, true, true, true, false, false,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"),
			new ServiceCalendar("other-weekday", true, true, true, true, true, false, false,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"),
			new ServiceCalendar("other-saturday", false, false, false, false, false, true, false,
				LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul")),
			// 휴일이 아닌 대체(다른 기관이 평일에 토요일 시간표를 쓰는 경우)는 이 역·노선의 판정에 영향이 없다.
			List.of(new ServiceCalendarDate("other-weekday", LocalDate.parse("2026-08-25"), 2),
				new ServiceCalendarDate("other-saturday", LocalDate.parse("2026-08-25"), 1)), List.of(), List.of());

		var result = service(snapshot(timetable, NOW.plusSeconds(60))).search(request(new Selector.ServiceDateSelector(
			LocalDate.parse("2026-08-25"))));

		assertThat(result.resolvedDayType()).isEqualTo(DayType.WEEKDAY);
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
			new TransitStopTime("b-early", 1, "station", "line", 32_700, 32_700, 0, 0),
			new TransitStopTime("trip", 2, "next", "line", 33_600, 33_600, 0, 0),
			new TransitStopTime("a-late", 2, "next", "line", 33_600, 33_600, 0, 0),
			new TransitStopTime("b-early", 2, "next", "line", 33_300, 33_300, 0, 0));
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
			RouteAccessData.empty());
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

		// 정확한 시각이 없는 간격 운행은 출발을 만들지 않는다. 출발이 하나도 없으면 시간표 범위 밖이다.
		assertFailure(service(snapshot(timetable, NOW.plusSeconds(60))), request(
			new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_NOT_COVERED);
	}

	@Test
	void pickupForbiddenStopDoesNotAppearAsADeparture() {
		RouteTimetable template = timetable();
		RouteTimetable timetable = new RouteTimetable(template.serviceCalendars(), template.serviceCalendarDates(),
			template.transitRoutes(), template.transitTrips(),
			List.of(new TransitStopTime("trip", 1, "station", "line", 32_400, 32_400, 1, 0),
				new TransitStopTime("trip", 2, "next", "line", 33_000, 33_000, 0, 0)),
			template.transitFrequencies(), template.officialFares(), template.feedEndDate(), template.routeAccessData());

		assertFailure(service(snapshot(timetable, NOW.plusSeconds(60))), request(
			new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_NOT_COVERED);
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
		assertFailure(service(snapshot(timetable, NOW.plusSeconds(60))), request(
			new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_NOT_COVERED);
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
		SearchRequest request = request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24")));
		assertFailure(service(() -> { throw new IllegalStateException("unavailable"); }), request, Failure.TIMETABLE_UNAVAILABLE);
		assertFailure(service(() -> null), request, Failure.TIMETABLE_UNAVAILABLE);
		// #476: 활성 번들이 없거나 만료되면 어댑터가 낸 명시적 실패를 그대로 드러낸다.
		for (Failure failure : List.of(Failure.TIMETABLE_UNAVAILABLE, Failure.TIMETABLE_STALE)) {
			assertFailure(service(() -> { throw new FailureException(failure); }), request, failure);
		}
		assertFailure(service(() -> { throw new FailureException(Failure.STATION_LINE_NOT_FOUND); }), request,
			Failure.TIMETABLE_UNAVAILABLE);
		assertFailure(service(new StationTimetableSnapshot(identity(NOW.plusSeconds(60)), null, canonical())), request,
			Failure.TIMETABLE_UNAVAILABLE);
		assertFailure(service(new StationTimetableSnapshot(null, timetable(), canonical())), request,
			Failure.TIMETABLE_IDENTITY_MISMATCH);
		assertFailure(service(snapshot(timetable(), null)), request, Failure.TIMETABLE_IDENTITY_MISMATCH);
		assertFailure(service(new StationTimetableSnapshot(identity(NOW.plusSeconds(60)), timetable(), null)), request,
			Failure.TIMETABLE_IDENTITY_MISMATCH);
		assertFailure(service(snapshot(timetable(), NOW)), request, Failure.TIMETABLE_STALE);
	}

	@Test
	void rejectsMissingStationDuplicateIdsAndInvalidRouteReferences() {
		RouteTimetable base = timetable();
		assertFailure(service(snapshot(base, NOW.plusSeconds(60), Set.of(new StationLine("other", "line")))),
			request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.STATION_LINE_NOT_FOUND);
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
			new TransitRoute("route", "line", "L", "line", "direction", "UTC"))) {
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
	void invalidSourceIdentityRequestAndNextBoundsAreRejected() {
		RouteTimetable base = timetable();
		SourceIdentity invalid = new SourceIdentity("artifact", "bad", "version", "d".repeat(64), "e".repeat(64), "f".repeat(64),
			NOW.plusSeconds(60));
		assertFailure(service(new StationTimetableSnapshot(invalid, base, canonical())),
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
		SourceIdentity valid = identity(NOW.plusSeconds(60));
		for (SourceIdentity identity : List.of(
			new SourceIdentity(valid.timetableArtifactId(), null, valid.canonicalStationVersion(), valid.canonicalStationSetSha256(), valid.sourceLineageSha256(), valid.evidenceHash(), valid.freshUntil()),
			new SourceIdentity(valid.timetableArtifactId(), "A".repeat(64), valid.canonicalStationVersion(), valid.canonicalStationSetSha256(), valid.sourceLineageSha256(), valid.evidenceHash(), valid.freshUntil()),
			new SourceIdentity(valid.timetableArtifactId(), valid.timetableSnapshotSha256(), " ", valid.canonicalStationSetSha256(), valid.sourceLineageSha256(), valid.evidenceHash(), valid.freshUntil()),
			new SourceIdentity(valid.timetableArtifactId(), valid.timetableSnapshotSha256(), valid.canonicalStationVersion(), "bad", valid.sourceLineageSha256(), valid.evidenceHash(), valid.freshUntil()),
			new SourceIdentity(valid.timetableArtifactId(), valid.timetableSnapshotSha256(), valid.canonicalStationVersion(), valid.canonicalStationSetSha256(), "bad", valid.evidenceHash(), valid.freshUntil()),
			new SourceIdentity(valid.timetableArtifactId(), valid.timetableSnapshotSha256(), valid.canonicalStationVersion(), valid.canonicalStationSetSha256(), valid.sourceLineageSha256(), "bad", valid.freshUntil()),
			new SourceIdentity(" ", valid.timetableSnapshotSha256(), valid.canonicalStationVersion(), valid.canonicalStationSetSha256(), valid.sourceLineageSha256(), valid.evidenceHash(), valid.freshUntil()))) {
			assertFailure(service(new StationTimetableSnapshot(identity, base, canonical())),
				request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
		}
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
		assertFailure(service(snapshot(base, NOW.plusSeconds(60), Set.of(new StationLine("station", "other")))),
			request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.STATION_LINE_NOT_FOUND);
		RouteTimetable nullDirection = new RouteTimetable(base.serviceCalendars(), base.serviceCalendarDates(),
			List.of(new TransitRoute("route", "line", "L", "line", null, "Asia/Seoul")), base.transitTrips(), base.transitStopTimes(),
			base.transitFrequencies(), base.officialFares(), base.feedEndDate(), base.routeAccessData());
		// #476: 원천 방면 이름이 없어도 다음 정차역으로 묶고 이름은 비워 둔다(추정하지 않는다).
		assertThat(service(snapshot(nullDirection, NOW.plusSeconds(60))).search(request(new Selector.ServiceDateSelector(
			LocalDate.parse("2026-08-24")))).directionGroups()).singleElement().satisfies(group -> {
				assertThat(group.nextStationId()).isEqualTo("next");
				assertThat(group.directionName()).isNull();
			});
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
		// #476: 토요일 시간표가 없는 기관의 휴일 시간표(토·일 운행)는 휴일 서명이다.
		assertThat(invokeStatic("calendarSignature", new Class<?>[]{ServiceCalendar.class}, calendarPattern(96))).isEqualTo(DayType.SUNDAY_HOLIDAY);
		assertThat(invokeStatic("calendarSignature", new Class<?>[]{ServiceCalendar.class}, calendarPattern(127))).isNull();
		assertThat(invokeStatic("calendarSignature", new Class<?>[]{ServiceCalendar.class}, calendarPattern(0))).isNull();
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
				new TransitStopTime("late", 2, "next", "line", 33_600, 33_600, 0, 0),
				new TransitStopTime("early", 1, "station", "line", 32_400, 32_400, 0, 0),
				new TransitStopTime("early", 2, "next", "line", 33_000, 33_000, 0, 0)), List.of(), List.of(), null, base.routeAccessData());
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
		RouteTimetable unrelatedRemoval = timetable(List.of(weekday),
			List.of(new ServiceCalendarDate("missing", LocalDate.parse("2026-08-24"), 2)), List.of(), List.of());
		assertThat(service(snapshot(unrelatedRemoval, NOW.plusSeconds(60))).search(request(new Selector.ServiceDateSelector(
			LocalDate.parse("2026-08-24")))).resolvedDayType()).isEqualTo(DayType.WEEKDAY);
		// #476 F1: 이 역·노선의 서비스가 빠지기만 하고 대신 도는 서비스가 없으면(달력 행 중복이어도) 날짜 종류를 정할 수 없다.
		RouteTimetable duplicateRemoved = timetable(List.of(weekday, weekday),
			List.of(new ServiceCalendarDate("weekday", LocalDate.parse("2026-08-24"), 2)), List.of(), List.of());
		assertFailure(service(snapshot(duplicateRemoved, NOW.plusSeconds(60))), request(new Selector.ServiceDateSelector(
			LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
		RouteTimetable removalOnly = timetable(List.of(weekday), List.of(
			new ServiceCalendarDate("weekday", LocalDate.parse("2026-08-24"), 2),
			new ServiceCalendarDate("weekday", LocalDate.parse("2026-08-23"), 1)), List.of(), List.of());
		assertFailure(service(snapshot(removalOnly, NOW.plusSeconds(60))), request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
	}

	@Test
	void lineTwoDeparturesWithTheSameHeadsignAreSplitByTheNextStoppingStation() {
		// #476 seq126 실측: 2호선 강남의 "성수" 행 열차는 역삼 쪽(432편)과 교대 쪽(399편) 양방향에 모두 있다.
		// 종착역(headsign)으로 묶으면 방향이 섞이므로 다음 정차역으로 묶는다. 원천 방면 이름은 비어 있다.
		String gangnam = "station-gangnam", yeoksam = "station-6cb6f7dc212c", gyodae = "station-7dfc6ea6a83c";
		RouteTimetable timetable = new RouteTimetable(List.of(calendar()), List.of(),
			List.of(new TransitRoute("route-kric-capital-s1102", "seoul-2", "S1102", "", "", "Asia/Seoul")),
			List.of(new TransitTrip("outer", "route-kric-capital-s1102", "weekday", "성수", "", "SUBWAY", "LOCAL", null, 0),
				new TransitTrip("inner", "route-kric-capital-s1102", "weekday", "성수", "", "SUBWAY", "LOCAL", null, 0)),
			List.of(new TransitStopTime("outer", 1, gangnam, "seoul-2", 32_400, 32_400, 0, 0),
				new TransitStopTime("outer", 2, yeoksam, "seoul-2", 32_520, 32_520, 0, 0),
				new TransitStopTime("outer", 3, "station-seongsu-outer", "seoul-2", 33_600, 33_600, 0, 0),
				new TransitStopTime("inner", 1, gangnam, "seoul-2", 32_460, 32_460, 0, 0),
				new TransitStopTime("inner", 2, gyodae, "seoul-2", 32_580, 32_580, 0, 0),
				new TransitStopTime("inner", 3, "station-seongsu-inner", "seoul-2", 36_000, 36_000, 0, 0)),
			List.of(), List.of(), null, RouteAccessData.empty());

		var result = new StationTimetableSearchService(() -> new StationTimetableSnapshot(identity(NOW.plusSeconds(60)), timetable,
			Set.of(new StationLine(gangnam, "seoul-2"))), Clock.fixed(NOW, ZoneOffset.UTC)).search(
			new SearchRequest(gangnam, "seoul-2", new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))));

		assertThat(result.directionGroups()).extracting(StationTimetableSearchService.DirectionGroup::nextStationId)
			.containsExactly(yeoksam, gyodae);
		assertThat(result.directionGroups()).allSatisfy(group -> assertThat(group.directionName()).isNull());
		assertThat(result.directionGroups().getFirst().departures()).singleElement().satisfies(departure -> {
			assertThat(departure.secondsFromServiceDayStart()).isEqualTo(32_400);
			assertThat(departure.terminalStationId()).isEqualTo("station-seongsu-outer");
		});
		assertThat(result.directionGroups().getLast().departures()).singleElement().satisfies(departure ->
			assertThat(departure.terminalStationId()).isEqualTo("station-seongsu-inner"));
	}

	@Test
	void sameSecondDeparturesToDifferentTerminalsAreDistinctButAnExactDuplicateFailsClosed() {
		RouteTimetable template = timetable();
		RouteTimetable twoTerminals = new RouteTimetable(template.serviceCalendars(), template.serviceCalendarDates(), template.transitRoutes(),
			List.of(new TransitTrip("gwangmyeong", "route", "weekday", "광명", "", "SUBWAY", "LOCAL", null, 0),
				new TransitTrip("incheon", "route", "weekday", "인천", "", "SUBWAY", "LOCAL", null, 0)),
			List.of(new TransitStopTime("gwangmyeong", 1, "station", "line", 26_040, 26_040, 0, 0),
				new TransitStopTime("gwangmyeong", 2, "next", "line", 26_220, 26_220, 0, 0),
				new TransitStopTime("gwangmyeong", 3, "terminal-gwangmyeong", "line", 27_120, 27_120, 0, 0),
				new TransitStopTime("incheon", 1, "station", "line", 26_040, 26_040, 0, 0),
				new TransitStopTime("incheon", 2, "next", "line", 26_220, 26_220, 0, 0),
				new TransitStopTime("incheon", 3, "terminal-incheon", "line", 29_000, 29_000, 0, 0)),
			List.of(), List.of(), null, RouteAccessData.empty());
		assertThat(service(snapshot(twoTerminals, NOW.plusSeconds(60))).search(request(new Selector.ServiceDateSelector(
			LocalDate.parse("2026-08-24")))).directionGroups()).singleElement().satisfies(group -> assertThat(group.departures())
				.extracting(StationTimetableSearchService.Departure::terminalStationId)
				.containsExactlyInAnyOrder("terminal-gwangmyeong", "terminal-incheon"));

		RouteTimetable exactDuplicate = timetable(List.of(calendar()), List.of(), List.of(), List.of(
			new TransitTrip("trip-duplicate", "route", "weekday", "headsign", "0", "SUBWAY", "LOCAL", null, 0)));
		assertFailure(service(snapshot(exactDuplicate, NOW.plusSeconds(60))), request(new Selector.ServiceDateSelector(
			LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
	}

	@Test
	void nextStoppingStationIsTheClosestLaterSequenceEvenWhenRowsAreUnordered() {
		RouteTimetable template = timetable();
		RouteTimetable unordered = new RouteTimetable(template.serviceCalendars(), template.serviceCalendarDates(), template.transitRoutes(),
			template.transitTrips(),
			List.of(new TransitStopTime("trip", 3, "far", "line", 33_600, 33_600, 0, 0),
				new TransitStopTime("trip", 1, "station", "line", 32_400, 32_400, 0, 0),
				new TransitStopTime("trip", 2, "near", "line", 33_000, 33_000, 0, 0)),
			List.of(), List.of(), null, RouteAccessData.empty());
		assertThat(service(snapshot(unordered, NOW.plusSeconds(60))).search(request(new Selector.ServiceDateSelector(
			LocalDate.parse("2026-08-24")))).directionGroups()).singleElement().satisfies(group -> {
				assertThat(group.nextStationId()).isEqualTo("near");
				assertThat(group.departures().getFirst().terminalStationId()).isEqualTo("far");
			});
	}

	@Test
	void removedCivilServiceWithServingReplacementWithoutUsableCalendarFailsClosed() {
		// 요청 역을 지나는 열차의 대체 달력이 없거나(행 없음·중복) 요일 서명이 없으면 요일을 판정할 수 없다.
		ServiceCalendar weekday = new ServiceCalendar("weekday", true, true, true, true, true, false, false,
			LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul");
		ServiceCalendar neutral = new ServiceCalendar("replacement", false, false, false, false, false, false, false,
			LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul");
		ServiceCalendar sunday = new ServiceCalendar("replacement", false, false, false, false, false, false, true,
			LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul");
		List<ServiceCalendarDate> dates = List.of(new ServiceCalendarDate("weekday", LocalDate.parse("2026-08-25"), 2),
			new ServiceCalendarDate("replacement", LocalDate.parse("2026-08-25"), 1));
		List<TransitTrip> replacementTrip = List.of(
			new TransitTrip("replacement-trip", "route", "replacement", "headsign", "0", "SUBWAY", "EXPRESS", null, 0));
		for (List<ServiceCalendar> calendars : List.of(List.of(weekday), List.of(weekday, neutral), List.of(weekday, sunday, sunday))) {
			assertFailure(service(snapshot(timetable(calendars, dates, List.of(), replacementTrip), NOW.plusSeconds(60))),
				request(new Selector.ServiceDateSelector(LocalDate.parse("2026-08-25"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
		}
	}

	@Test
	void departuresEndingAtTheRequestedStationAreNotListedAndAnArrivalOnlyStationIsNotCovered() {
		RouteTimetable template = timetable();
		RouteTimetable mixed = new RouteTimetable(template.serviceCalendars(), template.serviceCalendarDates(), template.transitRoutes(),
			List.of(new TransitTrip("trip", "route", "weekday", "headsign", "0", "SUBWAY", "LOCAL", null, 0),
				new TransitTrip("terminating", "route", "weekday", "headsign", "0", "SUBWAY", "LOCAL", null, 0)),
			List.of(new TransitStopTime("trip", 1, "station", "line", 32_400, 32_400, 0, 0),
				new TransitStopTime("trip", 2, "next", "line", 33_000, 33_000, 0, 0),
				new TransitStopTime("terminating", 1, "previous", "line", 31_800, 31_800, 0, 0),
				new TransitStopTime("terminating", 2, "station", "line", 32_100, 32_100, 0, 0)),
			List.of(), List.of(), null, RouteAccessData.empty());
		var result = service(snapshot(mixed, NOW.plusSeconds(60))).search(request(
			new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))));
		assertThat(result.directionGroups()).singleElement().satisfies(group -> assertThat(group.departures())
			.extracting(StationTimetableSearchService.Departure::secondsFromServiceDayStart).containsExactly(32_400));

		RouteTimetable arrivalsOnly = new RouteTimetable(template.serviceCalendars(), template.serviceCalendarDates(),
			template.transitRoutes(), List.of(new TransitTrip("terminating", "route", "weekday", "headsign", "0", "SUBWAY", "LOCAL", null, 0)),
			List.of(new TransitStopTime("terminating", 1, "previous", "line", 31_800, 31_800, 0, 0),
				new TransitStopTime("terminating", 2, "station", "line", 32_100, 32_100, 0, 0)),
			List.of(), List.of(), null, RouteAccessData.empty());
		assertFailure(service(snapshot(arrivalsOnly, NOW.plusSeconds(60))), request(
			new Selector.ServiceDateSelector(LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_NOT_COVERED);
	}

	@Test
	void sourceDirectionNameIsKeptOnlyWhenEveryDepartureOfTheGroupCarriesTheSameValueAndMixedNamingFailsClosed() {
		RouteTimetable named = timetable();
		assertThat(service(snapshot(named, NOW.plusSeconds(60))).search(request(new Selector.ServiceDateSelector(
			LocalDate.parse("2026-08-24")))).directionGroups()).singleElement()
			.satisfies(group -> assertThat(group.directionName()).isEqualTo("direction"));

		// #476 F6: 같은 다음 정차역 그룹에 이름 있는 열차와 없는 열차가 섞이면 이름을 숨기지 않고 원천 불일치로 실패한다.
		RouteTimetable partial = withSecondRoute(named, "");
		assertFailure(service(snapshot(partial, NOW.plusSeconds(60))), request(new Selector.ServiceDateSelector(
			LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);

		RouteTimetable unnamed = withSecondRoute(new RouteTimetable(named.serviceCalendars(), named.serviceCalendarDates(),
			List.of(new TransitRoute("route", "line", "L", "line", "", "Asia/Seoul")), named.transitTrips(), named.transitStopTimes(),
			named.transitFrequencies(), named.officialFares(), named.feedEndDate(), named.routeAccessData()), "");
		assertThat(service(snapshot(unnamed, NOW.plusSeconds(60))).search(request(new Selector.ServiceDateSelector(
			LocalDate.parse("2026-08-24")))).directionGroups()).singleElement()
			.satisfies(group -> assertThat(group.directionName()).isNull());

		RouteTimetable conflicting = withSecondRoute(named, "other direction");
		assertFailure(service(snapshot(conflicting, NOW.plusSeconds(60))), request(new Selector.ServiceDateSelector(
			LocalDate.parse("2026-08-24"))), Failure.TIMETABLE_IDENTITY_MISMATCH);
	}

	private static RouteTimetable withSecondRoute(RouteTimetable base, String directionName) {
		List<TransitTrip> trips = new java.util.ArrayList<>(base.transitTrips());
		trips.add(new TransitTrip("trip-2", "route-2", "weekday", "headsign", "0", "SUBWAY", "EXPRESS", null, 0));
		List<TransitStopTime> stops = new java.util.ArrayList<>(base.transitStopTimes());
		stops.add(new TransitStopTime("trip-2", 1, "station", "line", 32_700, 32_700, 0, 0));
		stops.add(new TransitStopTime("trip-2", 2, "next", "line", 33_300, 33_300, 0, 0));
		List<TransitRoute> routes = new java.util.ArrayList<>(base.transitRoutes());
		routes.add(new TransitRoute("route-2", "line", "L", "line", directionName, "Asia/Seoul"));
		return new RouteTimetable(base.serviceCalendars(), base.serviceCalendarDates(), routes, trips, stops,
			base.transitFrequencies(), base.officialFares(), base.feedEndDate(), base.routeAccessData());
	}

	private static StationTimetableSearchService service(StationTimetableSnapshot snapshot) {
		return service(() -> snapshot);
	}
	private static StationTimetableSearchService service(StationTimetableSnapshotPort port) {
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

	private static StationTimetableSnapshot snapshot(RouteTimetable timetable, Instant freshUntil) {
		return snapshot(timetable, freshUntil, canonical());
	}
	private static StationTimetableSnapshot snapshot(RouteTimetable timetable, Instant freshUntil, Set<StationLine> canonical) {
		return new StationTimetableSnapshot(identity(freshUntil), timetable, canonical);
	}
	private static SourceIdentity identity(Instant freshUntil) {
		return new SourceIdentity("artifact", "a".repeat(64), "sha256:" + "d".repeat(64), "d".repeat(64), "e".repeat(64),
			"f".repeat(64), freshUntil);
	}
	private static Set<StationLine> canonical() { return Set.of(new StationLine("station", "line")); }

	private static SearchRequest request(Selector selector) { return new SearchRequest("station", "line", selector); }
	private static ServiceCalendar calendar() {
		return new ServiceCalendar("weekday", true, true, true, true, true, true, true,
			LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul");
	}
	private static RouteTimetable corruptedTimetable(TransitStopTime stop, TransitFrequency frequency) {
		return new RouteTimetable(List.of(calendar()), List.of(),
			List.of(new TransitRoute("route", "line", "L", "line", "direction", "Asia/Seoul")),
			List.of(new TransitTrip("trip", "route", "weekday", "headsign", "0", "SUBWAY", "LOCAL", null, 0)),
			List.of(stop, new TransitStopTime("trip", 2, "next", "line", 50_000, 50_000, 0, 0)), List.of(frequency), List.of(), null,
			RouteAccessData.empty());
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
		for (TransitTrip trip : trips) {
			stops.add(new TransitStopTime(trip.id(), 1, "station", "line", 32_400, 32_400, 0, 0));
			stops.add(new TransitStopTime(trip.id(), 2, "next", "line", 33_000, 33_000, 0, 0));
		}
		return new RouteTimetable(calendars, dates, List.of(new TransitRoute("route", "line", "L", "line", "direction", "Asia/Seoul")),
			trips, stops, frequencies, List.of(), null,
			RouteAccessData.empty());
	}
}
