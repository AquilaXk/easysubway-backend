package com.easysubway.route.application.service;

import com.easysubway.journey.application.JourneyProfileRaptorPort;
import com.easysubway.journey.application.JourneyProfileRaptorPort.PlanningResult;
import com.easysubway.journey.application.JourneyProfileResourcePolicy.ProfilePlanningLimits;
import com.easysubway.journey.application.JourneyRaptorQuery;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 한 요청의 순수 planner 실행만 측정한다. serving 또는 oracle 성공 증거가 아니다. */
final class JourneyProfileMeasuredExecution {
	private JourneyProfileMeasuredExecution() { }

	static Observation<PlanningResult> measure(JourneyRaptorQuery query, RaptorRouteBundleRuntimeView runtime,
		ProfilePlanningLimits limits) {
		return capture(query, runtime,
			() -> new JourneyProfileRaptorAdapter().planRuntime(query, runtime, null, limits),
			System::nanoTime, allocationCounter());
	}

	static Observation<PlanningResult> capture(JourneyRaptorQuery query, RaptorRouteBundleRuntimeView runtime,
		Supplier<PlanningResult> calculation, LongSupplier clock, LongSupplier allocations) {
		Observation<PlanningResult> observation = captureCalculation(query, runtime, calculation, clock, allocations);
		if (!query.requestId().equals(observation.result().countSnapshot().requestId())) {
			throw new Unobservable("planner request identity mismatch");
		}
		return observation;
	}

	static Observation<RouteTimetableRaptorPlanner.JourneyPlan> measurePoint(
		JourneyRaptorQuery query, RaptorRouteBundleRuntimeView runtime
	) {
		return capturePoint(query, runtime, System::nanoTime, allocationCounter());
	}

	/** 실제 point 관측만 투영한다. 운영 경계 카운터나 profile 전용 지표를 합성하지 않는다. */
	static Map<String, Object> pointRow(String regionId,
		Observation<RouteTimetableRaptorPlanner.JourneyPlan> observation,
		List<JourneyProfileExactOracle.Candidate> expected) {
		if (regionId == null || regionId.isBlank()) throw new IllegalArgumentException("region is required");
		Objects.requireNonNull(observation, "observation");
		expected = List.copyOf(Objects.requireNonNull(expected, "expected"));
		var actual = observation.result().itineraries().stream().map(JourneyProfileRaptorAdapter::itinerary).toList();
		boolean parity = JourneyProfileOracleComparison.matchesObservableTimetableFrontier(expected, actual);
		if (expected.isEmpty() || !parity) throw new Unobservable("point oracle frontier mismatch");
		int loss = JourneyProfileOracleComparison.requiredObjectiveLoss(expected, actual);
		if (loss != 0) throw new Unobservable("point oracle objective loss");
		var scans = observation.result().scanMetrics();
		return Map.of("regionId", regionId, "queryClass", "POINT",
			"expandedRoutes", scans.expandedRoutes(), "expandedTrips", scans.expandedTrips(),
			"expandedTransfers", scans.expandedTransfers(), "durationNanos", observation.durationNanos(),
			"allocatedBytes", observation.allocatedBytes(), "requiredRepresentativeLoss", loss,
			"oracleParity", parity, "profileMetrics", Map.of("status", "NOT_APPLICABLE"));
	}

	/** 역방향 성공 행은 oracle과 관측 가능한 전체 경로 집합이 일치할 때만 생성한다. */
	static Map<String, Object> reverseRow(String regionId, Observation<PlanningResult> observation,
		List<JourneyProfileExactOracle.Candidate> expected) {
		if (regionId == null || regionId.isBlank()) throw new IllegalArgumentException("region is required");
		Objects.requireNonNull(observation, "observation");
		expected = List.copyOf(Objects.requireNonNull(expected, "expected"));
		if (!(observation.result() instanceof PlanningResult.Planned planned)) {
			throw new Unobservable("reverse planner did not produce a plan");
		}
		String queryClass;
		JourneyProfileRaptorPort.ReversePlan reverse;
		if (planned.temporalPlan() instanceof JourneyProfileRaptorPort.ArriveByPlan plan) {
			queryClass = "ARRIVE_BY";
			reverse = plan.result();
		} else if (planned.temporalPlan() instanceof JourneyProfileRaptorPort.LastConnectionPlan plan) {
			queryClass = "LAST_CONNECTION";
			reverse = plan.result();
		} else {
			throw new Unobservable("reverse observation requires a reverse temporal plan");
		}
		if (!(reverse instanceof JourneyProfileRaptorPort.ReversePlan.Found found)) {
			throw new Unobservable("reverse planner did not find a route");
		}
		boolean parity = JourneyProfileOracleComparison.matchesObservableTimetableFrontier(expected, found.itineraries());
		if (expected.isEmpty() || !parity) throw new Unobservable("reverse oracle frontier mismatch");
		int loss = JourneyProfileOracleComparison.requiredObjectiveLoss(expected, found.itineraries());
		if (loss != 0) throw new Unobservable("reverse oracle objective loss");
		return profileRow(regionId, queryClass, observation, planned, loss, parity);
	}

	/** 각 실제 departure breakpoint의 independent oracle frontier가 모두 보존된 경우에만 행을 만든다. */
	static Map<String, Object> departureRow(
		String regionId,
		Observation<PlanningResult> observation,
		Map<java.time.Instant, List<JourneyProfileExactOracle.Candidate>> expectedByBreakpoint
	) {
		if (regionId == null || regionId.isBlank()) throw new IllegalArgumentException("region is required");
		Objects.requireNonNull(observation, "observation");
		Objects.requireNonNull(expectedByBreakpoint, "expectedByBreakpoint");
		if (!(observation.result() instanceof PlanningResult.Planned planned)
			|| !(planned.temporalPlan() instanceof JourneyProfileRaptorPort.DepartureWindowPlan departure)) {
			throw new Unobservable("departure planner did not produce a departure window plan");
		}
		var expected = new LinkedHashMap<java.time.Instant, List<JourneyProfileExactOracle.Candidate>>();
		for (var entry : expectedByBreakpoint.entrySet()) {
			java.time.Instant readyAt = Objects.requireNonNull(entry.getKey(), "expected breakpoint");
			List<JourneyProfileExactOracle.Candidate> candidates = List.copyOf(
				Objects.requireNonNull(entry.getValue(), "expected candidates"));
			if (candidates.isEmpty()) throw new Unobservable("departure oracle breakpoint is empty");
			expected.put(readyAt, candidates);
		}
		if (expected.isEmpty()) throw new Unobservable("departure oracle breakpoints are required");
		var actual = new LinkedHashMap<java.time.Instant, JourneyProfileRaptorPort.DeparturePoint>();
		for (JourneyProfileRaptorPort.DeparturePoint point : departure.points()) {
			if (actual.put(point.readyAt(), point) != null) {
				throw new Unobservable("duplicate actual departure breakpoint");
			}
		}
		if (!actual.keySet().equals(expected.keySet())) {
			throw new Unobservable("departure oracle breakpoints do not match actual points");
		}
		int totalLoss = 0;
		boolean allParity = true;
		for (var entry : expected.entrySet()) {
			List<JourneyProfileRaptorPort.Itinerary> actualItineraries = actual.get(entry.getKey()).itineraries();
			boolean parity = JourneyProfileOracleComparison.matchesObservableTimetableFrontier(
				entry.getValue(), actualItineraries);
			allParity &= parity;
			if (!parity) throw new Unobservable("departure oracle frontier mismatch");
			int loss = JourneyProfileOracleComparison.requiredObjectiveLoss(entry.getValue(), actualItineraries);
			totalLoss += loss;
			if (loss != 0) {
				throw new Unobservable("departure oracle objective loss");
			}
		}
		return profileRow(regionId, "DEPARTURE_PROFILE", observation, planned, totalLoss, allParity);
	}

	private static Map<String, Object> profileRow(
		String regionId,
		String queryClass,
		Observation<PlanningResult> observation,
		PlanningResult.Planned planned,
		int loss,
		boolean parity
	) {
		Long saturated = planned.countSnapshot().countsByRuleId().get("FAIL_CLOSED_FRONTIER_CAPACITY_V1");
		if (saturated == null) throw new Unobservable("frontier capacity observation is unavailable");
		var metrics = planned.planningMetrics();
		return Map.ofEntries(Map.entry("regionId", regionId), Map.entry("queryClass", queryClass),
			Map.entry("observedWork", metrics.workConsumed()), Map.entry("observedStateLabels", metrics.peakStateLabels()),
			Map.entry("observedDestinationLabels", metrics.peakDestinationLabels()),
			Map.entry("observedBreakpoints", metrics.reservedProfileBreakpoints()),
			Map.entry("durationNanos", observation.durationNanos()), Map.entry("allocatedBytes", observation.allocatedBytes()),
			Map.entry("saturatedStates", saturated), Map.entry("requiredRepresentativeLoss", loss), Map.entry("oracleParity", parity));
	}

	static Observation<RouteTimetableRaptorPlanner.JourneyPlan> capturePoint(
		JourneyRaptorQuery query, RaptorRouteBundleRuntimeView runtime, LongSupplier clock, LongSupplier allocations
	) {
		requirePointQuery(query);
		return captureCalculation(query, runtime, () -> new RouteTimetableRaptorPlanner().journeyItineraries(
			query, runtime.compiledTimetable(), RouteTimetableRaptorPlanner.RealtimeOverlay.empty(),
			new com.easysubway.journey.application.JourneyRequestMeasurement(query.requestId()), query.requestId(),
			runtime.routeBundleSha256(), runtime.generation()), clock, allocations);
	}

	private static LongSupplier allocationCounter() {
		if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean)
			|| !bean.isThreadAllocatedMemorySupported() || !bean.isThreadAllocatedMemoryEnabled()) {
			throw new Unobservable("thread allocation measurement is unavailable");
		}
		long threadId = Thread.currentThread().threadId();
		return () -> bean.getThreadAllocatedBytes(threadId);
	}

	private static void requirePointQuery(JourneyRaptorQuery query) {
		Objects.requireNonNull(query, "query");
		if (!(query.temporalQuery() instanceof JourneyRaptorQuery.DepartAt)) {
			throw new IllegalArgumentException("POINT measurement requires DepartAt");
		}
		if (query.timePolicy() != com.easysubway.journey.application.JourneyRequest.TimePolicy.TIMETABLE_REQUIRED) {
			throw new IllegalArgumentException("POINT measurement requires TIMETABLE_REQUIRED");
		}
	}

	private static <T> Observation<T> captureCalculation(
		JourneyRaptorQuery query, RaptorRouteBundleRuntimeView runtime,
		Supplier<T> calculation, LongSupplier clock, LongSupplier allocations
	) {
		Objects.requireNonNull(query, "query");
		Objects.requireNonNull(runtime, "runtime");
		Objects.requireNonNull(calculation, "calculation");
		Objects.requireNonNull(clock, "clock");
		Objects.requireNonNull(allocations, "allocations");
		long beforeBytes = allocations.getAsLong();
		if (beforeBytes < 0) throw new Unobservable("initial allocation observation is unavailable");
		long started = clock.getAsLong();
		T result = Objects.requireNonNull(calculation.get(), "planning result");
		long elapsed = clock.getAsLong() - started;
		long afterBytes = allocations.getAsLong();
		if (elapsed < 0 || afterBytes < beforeBytes) throw new Unobservable("measurement counter regressed");
		return new Observation<>(query.requestId(), runtime.routeBundleSha256(), runtime.generation(),
			elapsed, afterBytes - beforeBytes, result);
	}

	record Observation<T>(String requestId, String routeBundleSha256, long generation,
		long durationNanos, long allocatedBytes, T result) {
		Observation {
			Objects.requireNonNull(requestId, "requestId");
			Objects.requireNonNull(routeBundleSha256, "routeBundleSha256");
			Objects.requireNonNull(result, "result");
			if (generation < 1 || durationNanos < 0 || allocatedBytes < 0) {
				throw new IllegalArgumentException("invalid measurement");
			}
		}
	}

	static final class Unobservable extends RuntimeException {
		Unobservable(String reason) { super(reason); }
	}
}
