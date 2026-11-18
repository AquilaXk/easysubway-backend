package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.ScanWorkspacePool;

import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteAccessData;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ScanWorkspacePool - Virtual Threads 환경 워크스페이스 풀링 및 GC 절감 검증")
class ScanWorkspacePoolTest {

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 6);
	private static final OffsetDateTime DEPARTURE = OffsetDateTime.of(2026, 7, 6, 7, 55, 0, 0, ZoneOffset.ofHours(9));

	@Test
	@DisplayName("기본 풀 생성 및 유휴 워크스페이스 획득/반납이 정상 동작한다")
	void acquiresAndReleasesWorkspaceSuccessfully() {
		var pool = new ScanWorkspacePool(4);
		assertThat(pool.maxIdleWorkspaces()).isEqualTo(4);
		assertThat(pool.idleCount()).isZero();
		assertThat(pool.totalAllocated()).isZero();

		var ws1 = pool.acquire();
		assertThat(ws1).isNotNull();
		assertThat(pool.totalAllocated()).isEqualTo(1);
		assertThat(pool.idleCount()).isZero();

		pool.release(ws1);
		assertThat(pool.idleCount()).isEqualTo(1);
		assertThat(pool.totalAllocated()).isEqualTo(1);

		var ws2 = pool.acquire();
		assertThat(ws2).isSameAs(ws1);
		assertThat(pool.idleCount()).isZero();
		assertThat(pool.totalAllocated()).isEqualTo(1);

		pool.release(ws2);
		assertThat(pool.idleCount()).isEqualTo(1);

		// Releasing null is a no-op
		pool.release(null);
		assertThat(pool.idleCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("공용 싱글톤 및 기본 생성자, 유효하지 않은 용량 거부 검증")
	void validatesPoolConfigurationAndSharedInstance() {
		var shared = ScanWorkspacePool.shared();
		assertThat(shared).isNotNull();
		assertThat(ScanWorkspacePool.shared()).isSameAs(shared);
		assertThat(shared.maxIdleWorkspaces()).isPositive();

		var defaultPool = new ScanWorkspacePool();
		assertThat(defaultPool.maxIdleWorkspaces()).isEqualTo(shared.maxIdleWorkspaces());

		assertThatThrownBy(() -> new ScanWorkspacePool(0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("positive");
		assertThatThrownBy(() -> new ScanWorkspacePool(-5))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("positive");
	}

	@Test
	@DisplayName("풀 용량(maxIdleWorkspaces)을 초과하여 반납된 워크스페이스는 폐기되어 상한이 유지된다")
	void releasesBeyondCapacityAreDiscarded() {
		var pool = new ScanWorkspacePool(2);
		var ws1 = pool.acquire();
		var ws2 = pool.acquire();
		var ws3 = pool.acquire();
		assertThat(pool.totalAllocated()).isEqualTo(3);

		pool.release(ws1);
		pool.release(ws2);
		pool.release(ws3); // 3rd should be discarded

		assertThat(pool.idleCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("가상 스레드 환경에서 순차 실행 시 단일 워크스페이스를 재사용하여 28MB 중복 할당을 방지한다")
	void reusesWorkspaceAcrossSequentialVirtualThreads() throws Exception {
		var pool = new ScanWorkspacePool(4);
		var planner = new RouteTimetableRaptorPlanner(pool);
		var timetable = multiLineTimetable();
		var compiled = planner.compile(timetable);

		try (var executor = Executors.newThreadPerTaskExecutor(
			Thread.ofVirtual().name("test-search-", 0).factory())) {

			// Run 10 successive searches on 10 separate virtual threads
			for (int i = 0; i < 10; i++) {
				final int idx = i;
				Future<RouteTimetableRaptorPlanner.JourneyPlan> future = executor.submit(() -> {
					var query = new JourneyRaptorQuery(
						"01ARZ3NDEKTSV4RRFFQ69G5F" + String.format("%02d", idx),
						"sta-1",
						"sta-3",
						new JourneyRaptorQuery.DepartAt(DEPARTURE.toInstant()),
						JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
						JourneyRequest.WalkingPace.SLOW,
						JourneyRequest.MobilityProfile.SLOW,
						JourneyRequest.ConstraintMode.NONE,
						1,
						2,
						() -> false
					);
					return planner.journeyItineraries(query, compiled);
				});

				var plan = future.get();
				assertThat(plan.itineraries()).isNotEmpty();
			}

			// In ThreadLocal, 10 virtual threads would create 10 workspaces (280MB).
			// With ScanWorkspacePool, sequential virtual threads reuse the exact same workspace!
			assertThat(pool.totalAllocated()).isEqualTo(1);
			assertThat(pool.idleCount()).isEqualTo(1);
		}
	}

	@Test
	@DisplayName("동시 가상 스레드 환경에서 최대 동시성 수준만큼만 워크스페이스를 할당하고 정상 반납한다")
	void boundsAllocationsUnderConcurrentVirtualThreads() throws Exception {
		int concurrency = 10;
		var pool = new ScanWorkspacePool(16);
		var planner = new RouteTimetableRaptorPlanner(pool);
		var timetable = multiLineTimetable();
		var compiled = planner.compile(timetable);

		var startGate = new CountDownLatch(1);
		var tasks = new ArrayList<Callable<RouteTimetableRaptorPlanner.JourneyPlan>>();

		for (int i = 0; i < concurrency; i++) {
			final int idx = i;
			tasks.add(() -> {
				startGate.await();
				var query = new JourneyRaptorQuery(
					"01ARZ3NDEKTSV4RRFFQ69G5C" + String.format("%02d", idx),
					"sta-1",
					"sta-3",
					new JourneyRaptorQuery.DepartAt(DEPARTURE.toInstant()),
					JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
					JourneyRequest.WalkingPace.SLOW,
					JourneyRequest.MobilityProfile.SLOW,
					JourneyRequest.ConstraintMode.NONE,
					1,
					2,
					() -> false
				);
				return planner.journeyItineraries(query, compiled);
			});
		}

		try (var executor = Executors.newThreadPerTaskExecutor(
			Thread.ofVirtual().name("concurrent-search-", 0).factory())) {

			var futures = new ArrayList<Future<RouteTimetableRaptorPlanner.JourneyPlan>>();
			for (var task : tasks) {
				futures.add(executor.submit(task));
			}

			// Release all threads simultaneously
			startGate.countDown();

			for (var future : futures) {
				var plan = future.get();
				assertThat(plan.itineraries()).isNotEmpty();
			}

			// Total allocated must be bounded by the concurrency
			assertThat(pool.totalAllocated()).isLessThanOrEqualTo(concurrency);
			// All allocated workspaces must be returned to the pool
			assertThat(pool.idleCount()).isEqualTo(pool.totalAllocated());
		}
	}

	@Test
	@DisplayName("탐색 취소 또는 예외 발생 시에도 워크스페이스가 누수 없이 풀로 안전하게 반납된다")
	void releasesWorkspaceSafelyOnCancellation() {
		var pool = new ScanWorkspacePool(4);
		var planner = new RouteTimetableRaptorPlanner(pool);
		var timetable = multiLineTimetable();
		var compiled = planner.compile(timetable);

		var cancelledSignal = new AtomicBoolean(true);
		var query = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5CAN",
			"sta-1",
			"sta-3",
			new JourneyRaptorQuery.DepartAt(DEPARTURE.toInstant()),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.SLOW,
			JourneyRequest.ConstraintMode.NONE,
			1,
			2,
			cancelledSignal::get
		);

		assertThatThrownBy(() -> planner.journeyItineraries(query, compiled))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("cancelled");

		// Workspace must be safely released back to the pool in finally
		assertThat(pool.idleCount()).isEqualTo(1);
		assertThat(pool.totalAllocated()).isEqualTo(1);
	}

	private static RouteTimetable multiLineTimetable() {
		var calendar = new ServiceCalendar("cal-1", true, true, true, true, true, true, true, SERVICE_DATE, SERVICE_DATE, "Asia/Seoul");
		var route1 = new TransitRoute("route-1", "line-1", "1", "1호선", "up", "Asia/Seoul");
		var route2 = new TransitRoute("route-2", "line-2", "2", "2호선", "up", "Asia/Seoul");

		var trip1 = new TransitTrip("trip-l1", "route-1", "cal-1", "head-1", "0", "LOCAL", 28800);
		var st1 = new TransitStopTime("trip-l1", 1, "sta-1", "line-1", 28800, 28860, 0, 0);
		var st2 = new TransitStopTime("trip-l1", 2, "sta-transfer", "line-1", 29100, 29160, 1, 0);
		var st3 = new TransitStopTime("trip-l1", 3, "sta-2", "line-1", 29400, 29460, 2, 0);

		var trip2 = new TransitTrip("trip-l2", "route-2", "cal-1", "head-2", "0", "LOCAL", 28800);
		var st4 = new TransitStopTime("trip-l2", 1, "sta-transfer", "line-2", 29280, 29340, 0, 0);
		var st5 = new TransitStopTime("trip-l2", 2, "sta-3", "line-2", 29580, 29640, 1, 0);

		var edges = List.of(
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge(
				"entry", "entrance", "platform-sta1", 120, 60, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"),
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge(
				"transfer", "platform-transfer-l1", "platform-transfer-l2", 60, 60, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"),
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge(
				"exit", "platform-sta3", "outside", 60, 40, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE"));
		var evidence = List.of(
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence(
				"entry-evidence", "sta-1", "line-1", "entry", "ENTRY",
				"OFFICIAL_SOURCE", "VERIFIED", true, null),
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence(
				"transfer-evidence", "sta-transfer", "line-2", "transfer", "TRANSFER",
				"OFFICIAL_SOURCE", "VERIFIED", true, null),
			new com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence(
				"exit-evidence", "sta-3", "line-2", "exit", "EXIT",
				"OFFICIAL_SOURCE", "VERIFIED", true, null));
		var transferRule = new com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransferRule(
			"transfer-rule", "sta-transfer", "line-1", "sta-transfer", "line-2",
			"IN_STATION", 60, "transfer", "transfer", "VERIFIED");
		return new RouteTimetable(
			List.of(calendar),
			List.of(),
			List.of(route1, route2),
			List.of(trip1, trip2),
			List.of(st1, st2, st3, st4, st5),
			List.of(),
			List.of(),
			null,
			new RouteAccessData(
				List.of(
					new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("entrance", "sta-1", null, "ENTRANCE"),
					new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("platform-sta1", "sta-1", "line-1", "PLATFORM"),
					new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("platform-transfer-l1", "sta-transfer", "line-1", "PLATFORM"),
					new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("platform-transfer-l2", "sta-transfer", "line-2", "PLATFORM"),
					new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("platform-sta3", "sta-3", "line-2", "PLATFORM"),
					new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode("outside", "sta-3", null, "EXIT")),
				edges,
				List.of(transferRule),
				evidence
			)
		);
	}
}
