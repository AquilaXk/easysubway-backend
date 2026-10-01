package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.JourneyRaptorPruningInventoryV1;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@DisplayName("Profile search allocation and runtime performance benchmark")
@EnabledIfEnvironmentVariable(named = "EASYSUBWAY_BENCHMARK", matches = "true")
class RouteTimetableRaptorPlannerProfilePerfTest {

	private static final long SEED = 20260929L;
	private static final int WARMUP_ITERATIONS = 20;
	private static final int MEASURE_ITERATIONS = 50;

	private final RouteTimetableRaptorPlanner planner = new RouteTimetableRaptorPlanner();

	@Test
	@DisplayName("격자 시간표 프로필 탐색 지연 시간 및 메모리 할당량 측정")
	void benchmarkGridProfileSearch() {
		var timetable = RouteTimetableRaptorPlannerProfileGoldenTest.generateGridTimetable(SEED);
		var compiled = planner.compile(timetable);
		var policy = RouteTimetableRaptorPlannerProfileGoldenTest.testPolicy().profilePlanningLimits();

		var query = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5F00",
			"station-0-0",
			"station-5-5",
			new JourneyRaptorQuery.DepartBetween(
				RouteTimetableRaptorPlannerProfileGoldenTest.instantAt(21_600),
				RouteTimetableRaptorPlannerProfileGoldenTest.instantAt(25_200)),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE,
			2,
			1,
			() -> false);

		// Warmup
		for (int i = 0; i < WARMUP_ITERATIONS; i++) {
			var observations = new JourneyProfilePruningObservationAccumulator(
				String.format("01ARZ3NDEKTSV4RRFFQ69G5%03d", i), JourneyRaptorPruningInventoryV1.FORWARD_RANGE_RAPTOR);
			var profile = planner.departureProfile(
				query, compiled, RouteTimetableRaptorPlanner.RealtimeOverlay.empty(), policy, observations);
			assertThat(profile).isNotEmpty();
		}

		long[] elapsedNanos = new long[MEASURE_ITERATIONS];
		long[] allocatedBytes = new long[MEASURE_ITERATIONS];
		var threadMx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		long threadId = Thread.currentThread().threadId();

		for (int i = 0; i < MEASURE_ITERATIONS; i++) {
			var observations = new JourneyProfilePruningObservationAccumulator(
				String.format("01ARZ3NDEKTSV4RRFFQ69G6%03d", i), JourneyRaptorPruningInventoryV1.FORWARD_RANGE_RAPTOR);

			long allocBefore = threadMx.getThreadAllocatedBytes(threadId);
			long startNano = System.nanoTime();

			var profile = planner.departureProfile(
				query, compiled, RouteTimetableRaptorPlanner.RealtimeOverlay.empty(), policy, observations);

			long endNano = System.nanoTime();
			long allocAfter = threadMx.getThreadAllocatedBytes(threadId);

			elapsedNanos[i] = endNano - startNano;
			allocatedBytes[i] = allocAfter - allocBefore;

			assertThat(profile).isNotEmpty();
		}

		Arrays.sort(elapsedNanos);
		Arrays.sort(allocatedBytes);

		long p50Nanos = elapsedNanos[MEASURE_ITERATIONS / 2];
		long p95Nanos = elapsedNanos[(int) (MEASURE_ITERATIONS * 0.95)];
		long p50Alloc = allocatedBytes[MEASURE_ITERATIONS / 2];
		long p95Alloc = allocatedBytes[(int) (MEASURE_ITERATIONS * 0.95)];

		System.out.printf("BENCHMARK RESULT: p50=%d ns (%.2f ms), p95=%d ns (%.2f ms), alloc_p50=%d bytes, alloc_p95=%d bytes%n",
			p50Nanos, p50Nanos / 1_000_000.0, p95Nanos, p95Nanos / 1_000_000.0, p50Alloc, p95Alloc);
	}
}
