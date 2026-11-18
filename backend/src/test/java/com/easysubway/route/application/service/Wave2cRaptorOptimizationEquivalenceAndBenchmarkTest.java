package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.StationTimetableIndex;
import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdate;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdates;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteAccessData;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendarDate;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransferRule;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.JourneyAccessProjection;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.JourneyDepartureProfilePoint;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.JourneyItinerary;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.JourneyRideProjection;
import com.easysubway.route.domain.BoardingSlackPolicy;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.ProfileWalkTimeCalculator.MobilityPreset;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@DisplayName("Wave 2C RAPTOR 최적화 동등성 및 성능 벤치마크")
class Wave2cRaptorOptimizationEquivalenceAndBenchmarkTest {

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 6); // Monday
	private static final ZoneOffset KST = ZoneOffset.ofHours(9);
	private static final JourneyProfileResourcePolicy.ProfilePlanningLimits LIMITS =
		new JourneyProfileResourcePolicy.ProfilePlanningLimits(500_000L, 64, 32, 32);

	@Test
	@DisplayName("다중 환승·경고상태·실시간 차단·복수 캘린더 격자에서 Point/Profile/Reverse 결과가 기준선과 완전히 동일하다")
	void preservesExactEquivalenceOnMultiTransferGridAndWarnings() {
		RouteTimetable timetable = buildGridTimetable(5, 8);
		RouteTimetableRaptorPlanner forward = new RouteTimetableRaptorPlanner();
		ReverseTimetableRaptorPlanner reverse = new ReverseTimetableRaptorPlanner();
		RouteTimetableRaptorPlanner.CompiledTimetable compiled = forward.compile(timetable);
		var overlay = forward.compileRealtimeOverlay(
			compiled,
			new TimetableRealtimeUpdates(
				"rt-grid",
				true,
				List.<TimetableRealtimeUpdate>of(),
				List.of("s-2-2-row2-col2-step-free"),
				null
			)
		);

		OffsetDateTime departAt = OffsetDateTime.of(2026, 7, 6, 8, 10, 0, 0, KST);
		OffsetDateTime windowEnd = OffsetDateTime.of(2026, 7, 6, 8, 45, 0, 0, KST);

		List<String> actualPointSigs = new ArrayList<>();
		for (JourneyRequest.ConstraintMode mode : List.of(
			JourneyRequest.ConstraintMode.NONE,
			JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE
		)) {
			JourneyRequest.MobilityProfile mob = mode == JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE
				? JourneyRequest.MobilityProfile.STEP_FREE
				: JourneyRequest.MobilityProfile.SLOW;
			var pointPlan = forward.journeyItineraries(
				new JourneyRaptorQuery(
					"01ARZ3NDEKTSV4RRFFQ69G5FAV",
					"s-0-0",
					"s-4-4",
					new JourneyRaptorQuery.DepartAt(departAt.toInstant()),
					JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
					JourneyRequest.WalkingPace.SLOW,
					mob,
					mode,
					3,
					3,
					() -> false
				),
				compiled,
				overlay
			);
			assertThat(pointPlan.itineraries()).isNotEmpty();
			for (JourneyItinerary it : pointPlan.itineraries()) {
				actualPointSigs.add(mode.name() + ":" + itinerarySignature(it));
				if (mode == JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE) {
					assertThat(it.legs()).filteredOn(JourneyAccessProjection.class::isInstance)
						.map(JourneyAccessProjection.class::cast)
						.allMatch(leg -> !leg.includesStairs());
				}
			}
		}

		List<JourneyDepartureProfilePoint> profilePoints = forward.departureProfile(
			new JourneyRaptorQuery(
				"01ARZ3NDEKTSV4RRFFQ69G5FAV",
				"s-0-0",
				"s-4-4",
				new JourneyRaptorQuery.DepartBetween(departAt.toInstant(), windowEnd.toInstant()),
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
				JourneyRequest.WalkingPace.SLOW,
				JourneyRequest.MobilityProfile.SLOW,
				JourneyRequest.ConstraintMode.NONE,
				3,
				3,
				() -> false
			),
			compiled,
			overlay,
			LIMITS
		);
		assertThat(profilePoints).isNotEmpty();

		int profileBit = RouteTimetableRaptorPlanner.profileBit(
			MobilityType.SENIOR, ConstraintMode.ALLOW_WITH_WARNINGS);
		int slack = BoardingSlackPolicy.secondsFor(MobilityType.SENIOR);
		var reverseResult = reverse.arriveBy(
			new ReverseTimetableRaptorPlanner.Query(
				"s-0-0",
				"s-4-4",
				SERVICE_DATE,
				28_800,
				36_000,
				3,
				profileBit,
				slack,
				MobilityPreset.SLOW,
				7_200,
				false,
				() -> false
			),
			compiled,
			compiled.activeServiceDay(SERVICE_DATE),
			overlay,
			LIMITS
		);
		assertThat(reverseResult.outcome()).isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		assertThat(reverseResult.itineraries()).isNotEmpty();

		List<String> profileSigs = profilePoints.stream()
			.map(p -> p.readyAtSeconds() + "->" + p.itineraries().stream()
				.map(Wave2cRaptorOptimizationEquivalenceAndBenchmarkTest::itinerarySignature)
				.collect(java.util.stream.Collectors.joining(";")))
			.toList();
		List<String> reverseSigs = reverseResult.itineraries().stream()
			.map(Wave2cRaptorOptimizationEquivalenceAndBenchmarkTest::itinerarySignature)
			.toList();

		assertThat(actualPointSigs).containsExactly(
			"NONE:32160|2|0|false|r-col0-5[s-0-0->s-4-0]|r-row4-6[s-4-0->s-4-4]",
			"REQUIRE_STEP_FREE:32160|2|0|false|r-col0-5[s-0-0->s-4-0]|r-row4-6[s-4-0->s-4-4]"
		);
		assertThat(profileSigs).containsExactly(
			"31110->33540|2|1|false|r-row0-6[s-0-0->s-0-4]|r-col4-7[s-0-4->s-4-4]",
			"30010->32160|2|1|false|r-col0-5[s-0-0->s-4-0]|r-row4-6[s-4-0->s-4-4];33360|2|1|false|r-col0-5[s-0-0->s-4-0]|r-row4-7[s-4-0->s-4-4]",
			"29910->32160|2|1|false|r-col0-5[s-0-0->s-4-0]|r-row4-6[s-4-0->s-4-4];33360|2|1|false|r-col0-5[s-0-0->s-4-0]|r-row4-7[s-4-0->s-4-4];33540|2|1|false|r-row0-5[s-0-0->s-0-4]|r-col4-7[s-0-4->s-4-4]"
		);
		assertThat(reverseSigs).containsExactly(
			"33540|2|1|false|r-row0-5[s-0-0->s-0-4]|r-col4-7[s-0-4->s-4-4]",
			"33540|2|1|false|r-row0-6[s-0-0->s-0-4]|r-col4-7[s-0-4->s-4-4]",
			"32160|2|1|false|r-col0-5[s-0-0->s-4-0]|r-row4-6[s-4-0->s-4-4]",
			"33360|2|1|false|r-col0-5[s-0-0->s-4-0]|r-row4-7[s-4-0->s-4-4]"
		);
	}

	@Test
	@DisplayName("ScanWorkspacePool 상한 32 적용 및 RaptorRouteBundleRuntimeView DCL 지연 인덱스 단일 인스턴스 검증")
	void enforcesPoolMaxCapAndRuntimeViewDclSingleton() throws Exception {
		var cappedPool = new RouteTimetableRaptorPlanner.ScanWorkspacePool(64);
		assertThat(cappedPool.maxIdleWorkspaces()).isEqualTo(32);

		var runtime = RaptorRouteBundleRuntimeView.compile("c".repeat(64), 1L, buildGridTimetable(5, 8));
		int threads = 8;
		var latch = new java.util.concurrent.CountDownLatch(1);
		try (var executor = java.util.concurrent.Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
			List<java.util.concurrent.Future<StationTimetableIndex>> futures = new ArrayList<>(threads);
			for (int i = 0; i < threads; i++) {
				futures.add(executor.submit(() -> {
					latch.await();
					return runtime.stationTimetableIndex();
				}));
			}
			latch.countDown();
			StationTimetableIndex first = futures.getFirst().get();
			for (var future : futures) {
				assertThat(future.get()).isSameAs(first);
			}
			assertThat(runtime.stationTimetableIndex()).isSameAs(first);
		}
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "EASYSUBWAY_BENCHMARK", matches = "true")
	@DisplayName("대형 격자(10x10역, 40패턴, 1600트립) Point/Profile/Reverse ns/op 및 할당량 측정")
	void measuresPointProfileAndReverseLatencyAndAllocations() {
		RouteTimetable timetable = buildGridTimetable(10, 40);
		RouteTimetableRaptorPlanner forward = new RouteTimetableRaptorPlanner();
		ReverseTimetableRaptorPlanner reverse = new ReverseTimetableRaptorPlanner();
		var compiled = forward.compile(timetable);
		var overlay = RouteTimetableRaptorPlanner.RealtimeOverlay.empty();
		var activeDay = compiled.activeServiceDay(SERVICE_DATE);

		OffsetDateTime departAt = OffsetDateTime.of(2026, 7, 6, 8, 10, 0, 0, KST);
		OffsetDateTime windowEnd = OffsetDateTime.of(2026, 7, 6, 9, 10, 0, 0, KST);
		var pointQuery = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV",
			"s-0-0",
			"s-9-9",
			new JourneyRaptorQuery.DepartAt(departAt.toInstant()),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.SLOW,
			JourneyRequest.ConstraintMode.NONE,
			3,
			3,
			() -> false
		);
		var profileQuery = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV",
			"s-0-0",
			"s-9-9",
			new JourneyRaptorQuery.DepartBetween(departAt.toInstant(), windowEnd.toInstant()),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.SLOW,
			JourneyRequest.ConstraintMode.NONE,
			3,
			3,
			() -> false
		);
		int profileBit = RouteTimetableRaptorPlanner.profileBit(
			MobilityType.SENIOR, ConstraintMode.ALLOW_WITH_WARNINGS);
		var reverseQuery = new ReverseTimetableRaptorPlanner.Query(
			"s-0-0",
			"s-9-9",
			SERVICE_DATE,
			25_200,
			42_000,
			3,
			profileBit,
			BoardingSlackPolicy.secondsFor(MobilityType.SENIOR),
			MobilityPreset.SLOW,
			16_800,
			false,
			() -> false
		);

		ThreadMXBean allocBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
		allocBean.setThreadAllocatedMemoryEnabled(true);
		long tid = Thread.currentThread().threadId();

		// Warmup JIT
		for (int i = 0; i < 40; i++) {
			assertThat(forward.journeyItineraries(pointQuery, compiled, overlay).itineraries()).isNotEmpty();
			assertThat(forward.departureProfile(profileQuery, compiled, overlay, LIMITS)).isNotEmpty();
			assertThat(reverse.arriveBy(reverseQuery, compiled, activeDay, overlay, LIMITS).outcome())
				.isEqualTo(ReverseTimetableRaptorPlanner.Outcome.FOUND);
		}

		int iters = 100;
		long[] pointNs = new long[iters];
		long[] pointAlloc = new long[iters];
		long[] profileNs = new long[iters];
		long[] profileAlloc = new long[iters];
		long[] reverseNs = new long[iters];
		long[] reverseAlloc = new long[iters];
		long sink = 0;

		for (int i = 0; i < iters; i++) {
			long a0 = allocBean.getThreadAllocatedBytes(tid);
			long t0 = System.nanoTime();
			var p = forward.journeyItineraries(pointQuery, compiled, overlay);
			pointNs[i] = System.nanoTime() - t0;
			pointAlloc[i] = allocBean.getThreadAllocatedBytes(tid) - a0;
			sink += p.itineraries().size();

			long a1 = allocBean.getThreadAllocatedBytes(tid);
			long t1 = System.nanoTime();
			var pr = forward.departureProfile(profileQuery, compiled, overlay, LIMITS);
			profileNs[i] = System.nanoTime() - t1;
			profileAlloc[i] = allocBean.getThreadAllocatedBytes(tid) - a1;
			sink += pr.size();

			long a2 = allocBean.getThreadAllocatedBytes(tid);
			long t2 = System.nanoTime();
			var rv = reverse.arriveBy(reverseQuery, compiled, activeDay, overlay, LIMITS);
			reverseNs[i] = System.nanoTime() - t2;
			reverseAlloc[i] = allocBean.getThreadAllocatedBytes(tid) - a2;
			sink += rv.itineraries().size();
		}

		Arrays.sort(pointNs);
		Arrays.sort(pointAlloc);
		Arrays.sort(profileNs);
		Arrays.sort(profileAlloc);
		Arrays.sort(reverseNs);
		Arrays.sort(reverseAlloc);
		System.out.printf(
			"WAVE2C_BENCH sink=%d | POINT p50=%d ns/op alloc=%d B/op | PROFILE p50=%d ns/op alloc=%d B/op | REVERSE p50=%d ns/op alloc=%d B/op%n",
			sink,
			pointNs[iters / 2], pointAlloc[iters / 2],
			profileNs[iters / 2], profileAlloc[iters / 2],
			reverseNs[iters / 2], reverseAlloc[iters / 2]
		);
		assertThat(sink).isPositive();
	}

	private static String itinerarySignature(JourneyItinerary itinerary) {
		List<JourneyRideProjection> rides = itinerary.legs().stream()
			.filter(JourneyRideProjection.class::isInstance)
			.map(JourneyRideProjection.class::cast)
			.toList();
		OffsetDateTime arr = rides.getLast().plannedArrivalTime().atZone(KST).toOffsetDateTime();
		int arrSec = arr.toLocalTime().toSecondOfDay();
		StringBuilder sb = new StringBuilder();
		long warningCount = itinerary.legs().stream()
			.filter(JourneyAccessProjection.class::isInstance)
			.map(JourneyAccessProjection.class::cast)
			.filter(JourneyAccessProjection::includesStairs)
			.count();
		sb.append(arrSec).append('|').append(rides.size()).append('|').append(warningCount)
			.append('|').append(itinerary.unconfirmedStairFreeTransfer());
		for (JourneyRideProjection ride : rides) {
			sb.append('|').append(ride.tripId())
				.append('[').append(ride.fromStationId()).append("->").append(ride.toStationId()).append(']');
		}
		return sb.toString();
	}

	private static RouteTimetable buildGridTimetable(int size, int tripsPerPattern) {
		ServiceCalendar weekday1 = new ServiceCalendar(
			"weekday-main", true, true, true, true, true, false, false,
			SERVICE_DATE.minusDays(7), SERVICE_DATE.plusDays(14), "Asia/Seoul");
		ServiceCalendar weekday2 = new ServiceCalendar(
			"weekday-peak", true, true, true, true, true, false, false,
			SERVICE_DATE.minusDays(7), SERVICE_DATE.plusDays(14), "Asia/Seoul");
		ServiceCalendar weekendOnly = new ServiceCalendar(
			"weekend-only", false, false, false, false, false, true, true,
			SERVICE_DATE.minusDays(7), SERVICE_DATE.plusDays(14), "Asia/Seoul");
		ServiceCalendar inactiveRange = new ServiceCalendar(
			"inactive-range", true, true, true, true, true, true, true,
			SERVICE_DATE.minusDays(30), SERVICE_DATE.minusDays(1), "Asia/Seoul");
		List<ServiceCalendarDate> exceptions = List.of(
			new ServiceCalendarDate("weekday-peak", SERVICE_DATE, 1),
			new ServiceCalendarDate("weekend-only", SERVICE_DATE, 2)
		);

		List<TransitRoute> routes = new ArrayList<>();
		List<TransitTrip> trips = new ArrayList<>();
		List<TransitStopTime> stopTimes = new ArrayList<>();
		List<PathwayNode> nodes = new ArrayList<>();
		List<PathwayEdge> edges = new ArrayList<>();
		List<TransferRule> transfers = new ArrayList<>();
		List<RouteEdgeEvidence> evidence = new ArrayList<>();

		// Horizontal rows + Vertical columns (both directions)
		for (int r = 0; r < size; r++) {
			String lineId = "row" + r;
			routes.add(new TransitRoute("rt-" + lineId, lineId, lineId, lineId, lineId, "Asia/Seoul"));
			for (int t = 0; t < tripsPerPattern; t++) {
				String svc = (t % 5 == 4) ? "weekend-only" : (t % 3 == 0 ? "weekday-peak" : "weekday-main");
				String tripId = "r-" + lineId + "-" + t;
				int base = 24_000 + t * 1_200 + r * 60;
				trips.add(new TransitTrip(tripId, "rt-" + lineId, svc, tripId, "0", "LOCAL", 0));
				for (int c = 0; c < size; c++) {
					int dep = base + c * 180;
					stopTimes.add(new TransitStopTime(tripId, c + 1, "s-" + r + "-" + c, lineId, dep, dep, 0, 0));
				}
			}
		}
		for (int c = 0; c < size; c++) {
			String lineId = "col" + c;
			routes.add(new TransitRoute("rt-" + lineId, lineId, lineId, lineId, lineId, "Asia/Seoul"));
			for (int t = 0; t < tripsPerPattern; t++) {
				String svc = (t % 7 == 6) ? "inactive-range" : "weekday-main";
				String tripId = "r-" + lineId + "-" + t;
				int base = 24_100 + t * 1_200 + c * 70;
				trips.add(new TransitTrip(tripId, "rt-" + lineId, svc, tripId, "0", "LOCAL", 0));
				for (int r = 0; r < size; r++) {
					int dep = base + r * 190;
					stopTimes.add(new TransitStopTime(tripId, r + 1, "s-" + r + "-" + c, lineId, dep, dep, 0, 0));
				}
			}
		}

		for (int r = 0; r < size; r++) {
			for (int c = 0; c < size; c++) {
				String station = "s-" + r + "-" + c;
				String rowLine = "row" + r;
				String colLine = "col" + c;
				addAccess(nodes, edges, evidence, station, rowLine);
				addAccess(nodes, edges, evidence, station, colLine);
				addTransfer(nodes, edges, evidence, transfers, station, rowLine, colLine);
				addTransfer(nodes, edges, evidence, transfers, station, colLine, rowLine);
			}
		}

		return new RouteTimetable(
			List.of(weekday1, weekday2, weekendOnly, inactiveRange),
			exceptions,
			routes,
			trips,
			stopTimes,
			List.of(),
			List.of(),
			null,
			new RouteAccessData(nodes, edges, transfers, evidence)
		);
	}

	private static void addAccess(
		List<PathwayNode> nodes,
		List<PathwayEdge> edges,
		List<RouteEdgeEvidence> evidence,
		String station,
		String line
	) {
		String key = station + "-" + line;
		PathwayEdge entry = new PathwayEdge(
			key + "-entry", key + "-en-from", key + "-en-to", 90, 60, false, false, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE");
		PathwayEdge exit = new PathwayEdge(
			key + "-exit", key + "-ex-from", key + "-ex-to", 90, 60, false, false, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE");
		edges.add(entry);
		edges.add(exit);
		nodes.add(new PathwayNode(entry.fromNodeId(), station, null, "ENTRANCE"));
		nodes.add(new PathwayNode(entry.toNodeId(), station, line, "PLATFORM"));
		nodes.add(new PathwayNode(exit.fromNodeId(), station, line, "PLATFORM"));
		nodes.add(new PathwayNode(exit.toNodeId(), station, null, "EXIT"));
		evidence.add(new RouteEdgeEvidence(key + "-ev-en", station, line, entry.id(), "ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null));
		evidence.add(new RouteEdgeEvidence(key + "-ev-ex", station, line, exit.id(), "EXIT", "OFFICIAL_SOURCE", "VERIFIED", true, null));
	}

	private static void addTransfer(
		List<PathwayNode> nodes,
		List<PathwayEdge> edges,
		List<RouteEdgeEvidence> evidence,
		List<TransferRule> transfers,
		String station,
		String fromLine,
		String toLine
	) {
		String key = station + "-" + fromLine + "-" + toLine;
		PathwayEdge stairs = new PathwayEdge(
			key + "-stairs", key + "-s-from", key + "-s-to", 120, 80, false, true, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STAIR_ONLY");
		PathwayEdge stepFree = new PathwayEdge(
			key + "-step-free", key + "-sf-from", key + "-sf-to", 240, 150, false, false, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE");
		edges.add(stairs);
		edges.add(stepFree);
		nodes.add(new PathwayNode(stairs.fromNodeId(), station, fromLine, "PLATFORM"));
		nodes.add(new PathwayNode(stairs.toNodeId(), station, toLine, "PLATFORM"));
		nodes.add(new PathwayNode(stepFree.fromNodeId(), station, fromLine, "PLATFORM"));
		nodes.add(new PathwayNode(stepFree.toNodeId(), station, toLine, "PLATFORM"));
		evidence.add(new RouteEdgeEvidence(key + "-s-ev", station, toLine, stairs.id(), "TRANSFER", "OFFICIAL_SOURCE", "VERIFIED", true, null));
		evidence.add(new RouteEdgeEvidence(key + "-sf-ev", station, toLine, stepFree.id(), "TRANSFER", "OFFICIAL_SOURCE", "VERIFIED", true, null));
		transfers.add(new TransferRule(
			key + "-rule", station, fromLine, station, toLine, "IN_STATION", 240,
			stairs.id(), stepFree.id(), "VERIFIED"));
	}

	@Test
	@DisplayName("PrimitiveLongObjectMap은 해시 충돌·재해시·갱신 및 무효 키·값 가드를 정확히 처리한다")
	void primitiveLongObjectMapHandlesCollisionsRehashOverwriteAndGuards() {
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PrimitiveLongObjectMap<String>(-1))
			.isInstanceOf(IllegalArgumentException.class);
		PrimitiveLongObjectMap<String> zeroHint = new PrimitiveLongObjectMap<>(0);
		assertThat(zeroHint.capacity()).isEqualTo(16);

		PrimitiveLongObjectMap<String> map = new PrimitiveLongObjectMap<>();
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> map.get(PrimitiveLongObjectMap.EMPTY_KEY))
			.isInstanceOf(IllegalArgumentException.class);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> map.put(PrimitiveLongObjectMap.EMPTY_KEY, "x"))
			.isInstanceOf(IllegalArgumentException.class);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> map.put(1L, null))
			.isInstanceOf(NullPointerException.class);

		long[] specialKeys = new long[] {0L, -2L, Long.MIN_VALUE, Long.MAX_VALUE};
		for (long k : specialKeys) {
			map.put(k, "init-" + k);
			assertThat(map.get(k)).isEqualTo("init-" + k);
		}
		map.put(0L, "updated-0");
		assertThat(map.get(0L)).isEqualTo("updated-0");
		assertThat(map.get(999_999L)).isNull();

		for (int i = 1; i <= 512; i++) {
			long key = i * 17L;
			map.put(key, "v-" + i);
		}
		assertThat(map.capacity()).isGreaterThanOrEqualTo(1024);
		assertThat(map.get(0L)).isEqualTo("updated-0");
		assertThat(map.get(-2L)).isEqualTo("init--2");
		assertThat(map.get(Long.MIN_VALUE)).isEqualTo("init-" + Long.MIN_VALUE);
		assertThat(map.get(Long.MAX_VALUE)).isEqualTo("init-" + Long.MAX_VALUE);
		int nonEmptySlots = 0;
		for (int slot = 0; slot < map.capacity(); slot++) {
			if (map.keyAt(slot) != PrimitiveLongObjectMap.EMPTY_KEY) {
				assertThat(map.valueAt(slot)).isNotNull();
				nonEmptySlots++;
			}
		}
		assertThat(nonEmptySlots).isEqualTo(516);
		for (int i = 1; i <= 512; i++) {
			assertThat(map.get(i * 17L)).isEqualTo("v-" + i);
		}
	}
}
