package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.SearchRouteV2Command;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.domain.ConstraintMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#2249 timetable compiled snapshot")
class RouteTimetableRaptorPlannerCompiledSnapshotTest {

	private static final LocalDate WEDNESDAY = LocalDate.of(2026, 7, 1);
	private final RouteTimetableRaptorPlanner planner = new RouteTimetableRaptorPlanner();

	@Test
	@DisplayName("feed grouping·정렬·frequency 전개 결과를 한 번 compile한다")
	void compilesDenseIndexesPatternsAndFrequencyTrips() {
		var compiled = planner.compile(frequencyTimetable());

		assertThat(compiled.stationCount()).isEqualTo(2);
		assertThat(compiled.routeCount()).isOne();
		assertThat(compiled.tripCount()).isOne();
		assertThat(compiled.routePatternCount()).isOne();
		assertThat(compiled.scheduledTripCount()).isEqualTo(3);
		assertThat(compiled.primitiveTimeArrayCount()).isEqualTo(3);
	}

	@Test
	@DisplayName("같은 route의 branch·short-turn station sequence를 distinct pattern으로 compile한다")
	void compilesBranchAndShortTurnTripsAsDistinctPatterns() {
		var compiled = planner.compile(branchAndShortTurnTimetable());

		assertThat(compiled.routePatternCount()).isEqualTo(3);
		assertThat(compiled.scheduledTripCount()).isEqualTo(4);
		assertThat(compiled.routePatternTripLinkCount()).isEqualTo(compiled.scheduledTripCount());
	}

	@Test
	@DisplayName("compiled frequency 출발은 반복 검색에서도 기존 시각을 유지한다")
	void preservesFrequencyDeparturesAcrossRepeatedSearches() {
		var compiled = planner.compile(frequencyTimetable());
		var command = command(WEDNESDAY, 9, 5);

		var first = planner.search(command, compiled);
		var second = planner.search(command, compiled);

		assertThat(first).hasSize(1);
		assertThat(second).extracting("estimatedDurationSeconds")
			.containsExactly(first.getFirst().estimatedDurationSeconds());
		assertThat(first.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("plannedDepartureTime")
			.containsExactly("2026-07-01T09:20:00+09:00");
	}

	@Test
	@DisplayName("search의 비교와 결과 시각은 compiled primitive 배열을 사용한다")
	void usesPrimitiveTimesForSearchAndResultTimes() throws Exception {
		var compiled = planner.compile(everyDayTimetable());
		primitiveIntArray(compiled, "departureSeconds")[0] = 32520;
		primitiveIntArray(compiled, "arrivalSeconds")[1] = 33120;

		var results = planner.search(command(WEDNESDAY, 8, 50), compiled);

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("plannedDepartureTime", "plannedArrivalTime")
			.containsExactly(org.assertj.core.groups.Tuple.tuple(
				"2026-07-01T09:02:00+09:00",
				"2026-07-01T09:12:00+09:00"));
	}

	@Test
	@DisplayName("search의 승하차 허용 판단은 compiled primitive 배열을 사용한다")
	void usesPrimitivePickupAndDropOffTypesForSearch() throws Exception {
		var pickupBlocked = planner.compile(everyDayTimetable());
		primitiveByteArray(pickupBlocked, "pickupTypes")[0] = 1;
		var dropOffBlocked = planner.compile(everyDayTimetable());
		primitiveByteArray(dropOffBlocked, "dropOffTypes")[1] = 1;

		assertThat(planner.search(command(WEDNESDAY, 8, 50), pickupBlocked)).isEmpty();
		assertThat(planner.search(command(WEDNESDAY, 8, 50), dropOffBlocked)).isEmpty();
	}

	@Test
	@DisplayName("nextServiceTime 비교는 compiled primitive 출발 시각을 사용한다")
	void usesPrimitiveDepartureTimeForNextServiceTime() throws Exception {
		var compiled = planner.compile(everyDayTimetable());
		primitiveIntArray(compiled, "departureSeconds")[0] = 32700;

		assertThat(planner.nextServiceTime(command(WEDNESDAY, 8, 50), compiled))
			.contains(OffsetDateTime.parse("2026-07-01T09:05:00+09:00"));
	}

	@Test
	@DisplayName("같은 service day의 immutable active snapshot을 재사용한다")
	void reusesSameActiveServiceDay() {
		var compiled = planner.compile(frequencyTimetable());

		var first = compiled.activeServiceDay(WEDNESDAY);
		var second = compiled.activeServiceDay(WEDNESDAY);

		assertThat(second).isSameAs(first);
		assertThat(compiled.activeServiceDayCacheSize()).isOne();
	}

	@Test
	@DisplayName("일반 search는 next-service 전용 boarding index를 생성하지 않는다")
	void regularSearchDoesNotAllocateBoardingIndex() {
		var compiled = planner.compile(frequencyTimetable());

		assertThat(planner.search(command(WEDNESDAY, 9, 5), compiled)).hasSize(1);

		assertThat(compiled.activeServiceDay(WEDNESDAY).boardingIndexInitialized()).isFalse();
	}

	@Test
	@DisplayName("동시 nextServiceTime은 service day boarding index를 안전하게 lazy publish한다")
	void concurrentNextServiceTimeLazilyPublishesBoardingIndex() throws Exception {
		var compiled = planner.compile(frequencyTimetable());
		var activeDay = compiled.activeServiceDay(WEDNESDAY);
		var start = new CountDownLatch(1);

		assertThat(activeDay.boardingIndexInitialized()).isFalse();
		try (var executor = Executors.newFixedThreadPool(8)) {
			var attempts = java.util.stream.IntStream.range(0, 8).mapToObj(ignored -> executor.submit(() -> {
				start.await();
				return planner.nextServiceTime(command(WEDNESDAY, 8, 0), compiled);
			})).toList();
			start.countDown();
			for (var attempt : attempts) {
				assertThat(attempt.get(5, TimeUnit.SECONDS))
					.contains(OffsetDateTime.parse("2026-07-01T09:00:00+09:00"));
			}
		}
		assertThat(activeDay.boardingIndexInitialized()).isTrue();
	}

	@Test
	@DisplayName("active service day cache는 access-order 최근 8일만 유지한다")
	void evictsLeastRecentlyUsedServiceDayAfterEightEntries() {
		var compiled = planner.compile(everyDayTimetable());
		for (int offset = 0; offset < 9; offset += 1) {
			compiled.activeServiceDay(WEDNESDAY.plusDays(offset));
		}

		assertThat(compiled.activeServiceDayCacheSize()).isEqualTo(8);
		assertThat(compiled.isServiceDayCached(WEDNESDAY)).isFalse();
		compiled.activeServiceDay(WEDNESDAY.plusDays(1));
		compiled.activeServiceDay(WEDNESDAY.plusDays(9));
		assertThat(compiled.isServiceDayCached(WEDNESDAY.plusDays(1))).isTrue();
		assertThat(compiled.isServiceDayCached(WEDNESDAY.plusDays(2))).isFalse();
	}

	@Test
	@DisplayName("평일·주말과 calendar exception add/remove semantics를 보존한다")
	void preservesCalendarAndExceptionSemantics() {
		var compiled = planner.compile(calendarExceptionTimetable());

		assertThat(compiled.activeTripCount(WEDNESDAY)).isOne();
		assertThat(compiled.activeTripCount(WEDNESDAY.plusDays(1))).isZero();
		assertThat(compiled.activeTripCount(WEDNESDAY.plusDays(2))).isOne();
		assertThat(compiled.activeTripCount(WEDNESDAY.plusDays(4))).isOne();
	}

	private static RouteTimetable frequencyTimetable() {
		return timetable(
			List.of(weekday("weekday")),
			List.of(),
			List.of(new LoadRouteTimetablePort.TransitTrip(
				"trip-frequency", "route", "weekday", "도착", "0", "LOCAL", 0)),
			List.of(
				stop("trip-frequency", 1, "station-a", 32400),
				stop("trip-frequency", 2, "station-b", 33300)
			),
			List.of(new LoadRouteTimetablePort.TransitFrequency(
				"trip-frequency", 32400, 34200, 600, false))
		);
	}

	private static RouteTimetable branchAndShortTurnTimetable() {
		return timetable(
			List.of(weekday("weekday")),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"trip-full", "route", "weekday", "도착", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"trip-branch", "route", "weekday", "분기", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"trip-short", "route", "weekday", "단축", "0", "LOCAL", 0)
			),
			List.of(
				stop("trip-full", 1, "station-a", 32400),
				stop("trip-full", 2, "station-b", 32500),
				stop("trip-full", 3, "station-c", 32600),
				stop("trip-full", 4, "station-d", 32700),
				stop("trip-branch", 1, "station-a", 33000),
				stop("trip-branch", 2, "station-b", 33100),
				stop("trip-branch", 3, "station-e", 33200),
				stop("trip-short", 1, "station-a", 33600),
				stop("trip-short", 2, "station-b", 33700),
				stop("trip-short", 3, "station-c", 33800)
			),
			List.of(new LoadRouteTimetablePort.TransitFrequency(
				"trip-full", 32400, 33600, 600, false))
		);
	}

	private static RouteTimetable everyDayTimetable() {
		return timetable(
			List.of(new LoadRouteTimetablePort.ServiceCalendar(
				"daily", true, true, true, true, true, true, true,
				WEDNESDAY, WEDNESDAY.plusDays(30), "Asia/Seoul")),
			List.of(),
			List.of(new LoadRouteTimetablePort.TransitTrip(
				"trip-daily", "route", "daily", "도착", "0", "LOCAL", 0)),
			List.of(stop("trip-daily", 1, "station-a", 32400), stop("trip-daily", 2, "station-b", 33000)),
			List.of()
		);
	}

	private static RouteTimetable calendarExceptionTimetable() {
		return timetable(
			List.of(weekday("weekday")),
			List.of(
				new LoadRouteTimetablePort.ServiceCalendarDate("weekday", WEDNESDAY.plusDays(1), 2),
				new LoadRouteTimetablePort.ServiceCalendarDate("special", WEDNESDAY.plusDays(4), 1)
			),
			List.of(
				new LoadRouteTimetablePort.TransitTrip("trip-weekday", "route", "weekday", "도착", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip("trip-special", "route", "special", "도착", "0", "LOCAL", 0)
			),
			List.of(
				stop("trip-weekday", 1, "station-a", 32400), stop("trip-weekday", 2, "station-b", 33000),
				stop("trip-special", 1, "station-a", 32400), stop("trip-special", 2, "station-b", 33000)
			),
			List.of()
		);
	}

	private static LoadRouteTimetablePort.ServiceCalendar weekday(String serviceId) {
		return new LoadRouteTimetablePort.ServiceCalendar(
			serviceId, true, true, true, true, true, false, false,
			WEDNESDAY, WEDNESDAY.plusDays(30), "Asia/Seoul");
	}

	private static RouteTimetable timetable(
		List<LoadRouteTimetablePort.ServiceCalendar> calendars,
		List<LoadRouteTimetablePort.ServiceCalendarDate> exceptions,
		List<LoadRouteTimetablePort.TransitTrip> trips,
		List<LoadRouteTimetablePort.TransitStopTime> stopTimes,
		List<LoadRouteTimetablePort.TransitFrequency> frequencies
	) {
		return new RouteTimetable(
			calendars,
			exceptions,
			List.of(new LoadRouteTimetablePort.TransitRoute(
				"route", "line", "L", "테스트", "도착 방면", "Asia/Seoul")),
			trips,
			stopTimes,
			frequencies
		);
	}

	private static LoadRouteTimetablePort.TransitStopTime stop(
		String tripId,
		int sequence,
		String stationId,
		int seconds
	) {
		return new LoadRouteTimetablePort.TransitStopTime(
			tripId, sequence, stationId, "line", seconds, seconds, 0, 0);
	}

	private static SearchRouteV2Command command(LocalDate date, int hour, int minute) {
		return new SearchRouteV2Command(
			"station-a",
			"station-b",
			OffsetDateTime.parse("%sT%02d:%02d:00+09:00".formatted(date, hour, minute)),
			MobilityType.SENIOR,
			ConstraintMode.ALLOW_WITH_WARNINGS,
			false,
			0,
			3
		);
	}

	private static int[] primitiveIntArray(
		RouteTimetableRaptorPlanner.CompiledTimetable compiled,
		String fieldName
	) throws Exception {
		return (int[]) primitiveArray(compiled, fieldName);
	}

	private static byte[] primitiveByteArray(
		RouteTimetableRaptorPlanner.CompiledTimetable compiled,
		String fieldName
	) throws Exception {
		return (byte[]) primitiveArray(compiled, fieldName);
	}

	private static Object primitiveArray(
		RouteTimetableRaptorPlanner.CompiledTimetable compiled,
		String fieldName
	) throws Exception {
		var scheduledTripsField = compiled.getClass().getDeclaredField("scheduledTrips");
		scheduledTripsField.setAccessible(true);
		var scheduledTrip = ((List<?>) scheduledTripsField.get(compiled)).getFirst();
		var timesField = scheduledTrip.getClass().getDeclaredField("times");
		timesField.setAccessible(true);
		var times = timesField.get(scheduledTrip);
		var primitiveArrayField = times.getClass().getDeclaredField(fieldName);
		primitiveArrayField.setAccessible(true);
		return primitiveArrayField.get(times);
	}
}
