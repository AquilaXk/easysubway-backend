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
	@DisplayName("active service day는 pattern trip을 stop마다 복제하지 않는다")
	void storesEachActivePatternTripOnce() {
		var compiled = planner.compile(frequencyTimetable());

		var activeDay = compiled.activeServiceDay(WEDNESDAY);

		assertThat(activeDay.routePatternTripLinkCount()).isEqualTo(compiled.scheduledTripCount());
	}

	@Test
	@DisplayName("일반 search는 next-service 전용 boarding index를 생성하지 않는다")
	void regularSearchDoesNotAllocateBoardingIndex() {
		var compiled = planner.compile(frequencyTimetable());

		assertThat(planner.search(command(WEDNESDAY, 9, 5), compiled)).hasSize(1);

		assertThat(compiled.activeServiceDay(WEDNESDAY).boardingIndexInitialized()).isFalse();
	}

	@Test
	@DisplayName("search는 marked stop이 속한 route pattern만 스캔한다")
	void scansOnlyMarkedRoutePatterns() {
		var compiled = planner.compile(disconnectedRoutesTimetable());

		assertThat(planner.search(command(WEDNESDAY, 8, 50), compiled)).hasSize(1);

		var metrics = planner.lastScanMetrics();
		assertThat(metrics.expandedRoutes()).isOne();
		assertThat(metrics.expandedTrips()).isOne();

		planner.search(command(WEDNESDAY, 8, 50), compiled);
		assertThat(planner.lastScanMetrics().workspaceIdentity()).isEqualTo(metrics.workspaceIdentity());
	}

	@Test
	@DisplayName("route 메타데이터가 없어도 stop line fallback으로 경로를 검색한다")
	void searchesTripWithoutRouteMetadata() {
		var results = planner.search(
			command(WEDNESDAY, 8, 50),
			planner.compile(missingRouteMetadataTimetable())
		);

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("lineId", "lineName")
			.containsExactly(org.assertj.core.groups.Tuple.tuple("line-fallback", "line-fallback"));
	}

	@Test
	@DisplayName("중간 stop에서 추월한 trip도 해당 stop 출발 시각 순서로 이진 탐색한다")
	void binarySearchesTripsInDepartureOrderAtEachStop() {
		var command = new SearchRouteV2Command(
			"station-b",
			"station-c",
			OffsetDateTime.parse("2026-07-01T09:10:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.ALLOW_WITH_WARNINGS,
			false,
			0,
			1
		);

		var results = planner.search(command, planner.compile(overtakingTimetable()));

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("tripId", "plannedDepartureTime")
			.containsExactly(org.assertj.core.groups.Tuple.tuple(
				"trip-fast", "2026-07-01T09:20:00+09:00"));
	}

	@Test
	@DisplayName("출발 stop에서 함께 탑승 가능한 추월 trip의 더 빠른 downstream 도착을 보존한다")
	void preservesFasterDownstreamArrivalFromOvertakingTrip() {
		var results = planner.search(
			command("station-a", "station-c", "2026-07-01T08:50:00+09:00"),
			planner.compile(overtakingTimetable())
		);

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("tripId", "plannedArrivalTime")
			.containsExactly(org.assertj.core.groups.Tuple.tuple(
				"trip-fast", "2026-07-01T09:35:00+09:00"));
	}

	@Test
	@DisplayName("동률 도착은 기존 exhaustive 전역 trip-id 순서를 유지한다")
	void preservesLegacyTripOrderForEqualArrival() {
		var results = planner.search(
			command("station-a", "station-c", "2026-07-01T08:50:00+09:00"),
			planner.compile(equalArrivalOvertakingTimetable())
		);

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("tripId", "plannedArrivalTime")
			.containsExactly(org.assertj.core.groups.Tuple.tuple(
				"a-fast", "2026-07-01T09:35:00+09:00"));
	}

	@Test
	@DisplayName("후행 trip이 downstream에서 합류해도 기존 trip-id 동률 순서를 유지한다")
	void preservesLegacyTripOrderWhenLaterTripMergesDownstream() {
		var results = planner.search(
			command("station-a", "station-c", "2026-07-01T08:50:00+09:00"),
			planner.compile(mergingTimetable())
		);

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("tripId", "plannedArrivalTime")
			.containsExactly(org.assertj.core.groups.Tuple.tuple(
				"a-late", "2026-07-01T09:20:00+09:00"));
	}

	@Test
	@DisplayName("같은 trip의 후속 stop에 더 일찍 도착한 predecessor로 환승 위치를 갱신한다")
	void preservesEarlierPredecessorAtLaterBoardingStop() {
		var command = new SearchRouteV2Command(
			"station-a",
			"station-d",
			OffsetDateTime.parse("2026-07-01T08:50:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.ALLOW_WITH_WARNINGS,
			false,
			1,
			1
		);

		var results = planner.search(command, planner.compile(laterBoardingPredecessorTimetable()));

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("tripId")
			.containsExactly("feeder-e", "connector");
	}

	@Test
	@DisplayName("후속 stop의 동일 출발 trip이 더 빨리 도착하면 해당 trip으로 전환한다")
	void switchesToFasterTripWithSameDepartureAtLaterStop() {
		var command = new SearchRouteV2Command(
			"station-origin",
			"station-d",
			OffsetDateTime.parse("2026-07-01T08:40:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.ALLOW_WITH_WARNINGS,
			false,
			1,
			1
		);

		var results = planner.search(command, planner.compile(sameDepartureDownstreamTimetable()));

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("tripId")
			.containsExactly("feeder-b", "a-fast");
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("plannedArrivalTime")
			.containsExactly(
				"2026-07-01T09:05:00+09:00",
				"2026-07-01T09:25:00+09:00"
			);
	}

	@Test
	@DisplayName("후속 stop의 동일 출발 후보가 더 늦게 도착하면 현재 trip을 유지한다")
	void keepsCurrentTripWhenSameDepartureCandidateArrivesLater() {
		var command = new SearchRouteV2Command(
			"station-origin",
			"station-d",
			OffsetDateTime.parse("2026-07-01T08:40:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.ALLOW_WITH_WARNINGS,
			false,
			1,
			1
		);

		var results = planner.search(command, planner.compile(sameDepartureSlowerCandidateTimetable()));

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("tripId")
			.containsExactly("feeder-b", "z-fast");
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("plannedArrivalTime")
			.containsExactly(
				"2026-07-01T09:00:00+09:00",
				"2026-07-01T09:35:00+09:00"
			);
	}

	@Test
	@DisplayName("출발 stop의 동일 출발 trip은 non-overtaking pattern 우위 순서를 유지한다")
	void preservesDominantPatternOrderForSameDepartureAtOrigin() {
		var results = planner.search(
			command("station-b", "station-d", "2026-07-01T09:20:00+09:00"),
			planner.compile(sameDepartureSlowerCandidateTimetable())
		);

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("tripId", "plannedArrivalTime")
			.containsExactly(org.assertj.core.groups.Tuple.tuple(
				"z-fast", "2026-07-01T09:35:00+09:00"));
	}

	@Test
	@DisplayName("같은 정차열에서 하차 정책이 다른 후속 trip을 잃지 않는다")
	void preservesLaterTripWhenEarlierTripBlocksDropOff() {
		var results = planner.search(
			command("station-a", "station-b", "2026-07-01T08:50:00+09:00"),
			planner.compile(dropOffVariantTimetable())
		);

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("tripId")
			.containsExactly("trip-usable");
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

	private static RouteTimetable disconnectedRoutesTimetable() {
		return new RouteTimetable(
			List.of(weekday("weekday")),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitRoute(
					"route-main", "line-main", "M", "주 경로", "B 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute(
					"route-other", "line-other", "O", "무관 경로", "Y 방면", "Asia/Seoul")
			),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"trip-main", "route-main", "weekday", "B", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"trip-other", "route-other", "weekday", "Y", "0", "LOCAL", 0)
			),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-main", 1, "station-a", "line-main", 32400, 32400, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-main", 2, "station-b", "line-main", 33000, 33000, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-other", 1, "station-x", "line-other", 32400, 32400, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-other", 2, "station-y", "line-other", 33000, 33000, 0, 0)
			),
			List.of()
		);
	}

	private static RouteTimetable missingRouteMetadataTimetable() {
		return new RouteTimetable(
			List.of(weekday("weekday")),
			List.of(),
			List.of(),
			List.of(new LoadRouteTimetablePort.TransitTrip(
				"trip-missing-route", "route-missing", "weekday", "B", "0", "LOCAL", 0)),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-missing-route", 1, "station-a", "line-fallback", 32400, 32400, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-missing-route", 2, "station-b", "line-fallback", 33000, 33000, 0, 0)
			),
			List.of()
		);
	}

	private static RouteTimetable overtakingTimetable() {
		return timetable(
			List.of(weekday("weekday")),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"trip-slow", "route", "weekday", "도착", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"trip-fast", "route", "weekday", "도착", "0", "EXPRESS", 0)
			),
			List.of(
				stop("trip-slow", 1, "station-a", 32400),
				stop("trip-slow", 2, "station-b", 34200),
				stop("trip-slow", 3, "station-c", 34800),
				stop("trip-fast", 1, "station-a", 33000),
				stop("trip-fast", 2, "station-b", 33600),
				stop("trip-fast", 3, "station-c", 34500)
			),
			List.of()
		);
	}

	private static RouteTimetable dropOffVariantTimetable() {
		return timetable(
			List.of(weekday("weekday")),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"trip-blocked", "route", "weekday", "도착", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"trip-usable", "route", "weekday", "도착", "0", "LOCAL", 0)
			),
			List.of(
				stop("trip-blocked", 1, "station-a", 32400),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-blocked", 2, "station-b", "line", 33000, 33000, 0, 1),
				stop("trip-usable", 1, "station-a", 32700),
				stop("trip-usable", 2, "station-b", 33300)
			),
			List.of()
		);
	}

	private static RouteTimetable equalArrivalOvertakingTimetable() {
		return timetable(
			List.of(weekday("weekday")),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"z-slow", "route", "weekday", "도착", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"a-fast", "route", "weekday", "도착", "0", "EXPRESS", 0)
			),
			List.of(
				stop("z-slow", 1, "station-a", 32400),
				stop("z-slow", 2, "station-b", 33600),
				stop("z-slow", 3, "station-c", 34500),
				stop("a-fast", 1, "station-a", 33000),
				stop("a-fast", 2, "station-b", 33300),
				stop("a-fast", 3, "station-c", 34500)
			),
			List.of()
		);
	}

	private static RouteTimetable mergingTimetable() {
		return timetable(
			List.of(weekday("weekday")),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"z-early", "route", "weekday", "도착", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"a-late", "route", "weekday", "도착", "0", "LOCAL", 0)
			),
			List.of(
				stop("z-early", 1, "station-a", 32400),
				stop("z-early", 2, "station-b", 33000),
				stop("z-early", 3, "station-c", 33600),
				stop("a-late", 1, "station-a", 32700),
				stop("a-late", 2, "station-b", 33000),
				stop("a-late", 3, "station-c", 33600)
			),
			List.of()
		);
	}

	private static RouteTimetable laterBoardingPredecessorTimetable() {
		return new RouteTimetable(
			List.of(weekday("weekday")),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitRoute(
					"route-b", "line-b", "B", "B 경유", "B 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute(
					"route-e", "line-e", "E", "E 경유", "E 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute(
					"route-connector", "line-c", "C", "연결", "D 방면", "Asia/Seoul")
			),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"connector", "route-connector", "weekday", "D", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"feeder-b", "route-b", "weekday", "B", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"feeder-e", "route-e", "weekday", "E", "0", "LOCAL", 0)
			),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime(
					"connector", 1, "station-b", "line-c", 33600, 33600, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"connector", 2, "station-e", "line-c", 33900, 33900, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"connector", 3, "station-d", "line-c", 34200, 34200, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"feeder-b", 1, "station-a", "line-b", 32400, 32400, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"feeder-b", 2, "station-b", "line-b", 32700, 32700, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"feeder-e", 1, "station-a", "line-e", 32520, 32520, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"feeder-e", 2, "station-e", "line-e", 32640, 32640, 0, 0)
			),
			List.of()
		);
	}

	private static RouteTimetable sameDepartureDownstreamTimetable() {
		return new RouteTimetable(
			List.of(weekday("weekday")),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitRoute(
					"route-feeder-a", "line-fa", "FA", "A 연결", "A 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute(
					"route-feeder-b", "line-fb", "FB", "B 연결", "B 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute(
					"route-main", "line-main", "M", "본선", "D 방면", "Asia/Seoul")
			),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"a-fast", "route-main", "weekday", "D", "0", "EXPRESS", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"feeder-a", "route-feeder-a", "weekday", "A", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"feeder-b", "route-feeder-b", "weekday", "B", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"z-slow", "route-main", "weekday", "D", "0", "LOCAL", 0)
			),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime(
					"a-fast", 1, "station-a", "line-main", 32100, 32100, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"a-fast", 2, "station-b", "line-main", 33600, 33600, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"a-fast", 3, "station-d", "line-main", 33900, 33900, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"feeder-a", 1, "station-origin", "line-fa", 31620, 31620, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"feeder-a", 2, "station-a", "line-fa", 32400, 32400, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"feeder-b", 1, "station-origin", "line-fb", 31680, 31680, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"feeder-b", 2, "station-b", "line-fb", 32700, 32700, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"z-slow", 1, "station-a", "line-main", 33000, 33000, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"z-slow", 2, "station-b", "line-main", 33600, 33600, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"z-slow", 3, "station-d", "line-main", 34200, 34200, 0, 0)
			),
			List.of()
		);
	}

	private static RouteTimetable sameDepartureSlowerCandidateTimetable() {
		return new RouteTimetable(
			List.of(weekday("weekday")),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitRoute(
					"route-feeder-a", "line-fa", "FA", "A 연결", "A 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute(
					"route-feeder-b", "line-fb", "FB", "B 연결", "B 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute(
					"route-main", "line-main", "M", "본선", "D 방면", "Asia/Seoul")
			),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"a-slow", "route-main", "weekday", "D", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"feeder-a", "route-feeder-a", "weekday", "A", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"feeder-b", "route-feeder-b", "weekday", "B", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"z-fast", "route-main", "weekday", "D", "0", "EXPRESS", 0)
			),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime(
					"a-slow", 1, "station-a", "line-main", 33600, 33600, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"a-slow", 2, "station-b", "line-main", 33900, 34200, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"a-slow", 3, "station-d", "line-main", 34800, 34800, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"feeder-a", 1, "station-origin", "line-fa", 31620, 31620, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"feeder-a", 2, "station-a", "line-fa", 32700, 32700, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"feeder-b", 1, "station-origin", "line-fb", 31680, 31680, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"feeder-b", 2, "station-b", "line-fb", 32400, 32400, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"z-fast", 1, "station-a", "line-main", 33300, 33300, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"z-fast", 2, "station-b", "line-main", 33600, 34200, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"z-fast", 3, "station-d", "line-main", 34500, 34500, 0, 0)
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

	private static SearchRouteV2Command command(String origin, String destination, String departureTime) {
		return new SearchRouteV2Command(
			origin,
			destination,
			OffsetDateTime.parse(departureTime),
			MobilityType.SENIOR,
			ConstraintMode.ALLOW_WITH_WARNINGS,
			false,
			0,
			1
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
