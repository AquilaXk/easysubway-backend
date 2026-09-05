package com.easysubway.route.application.service;

import com.easysubway.journey.application.JourneyProfileRaptorPort.PlanningResult;
import com.easysubway.journey.application.JourneyProfileResourcePolicy.ProfilePlanningLimits;
import com.easysubway.journey.application.JourneyRaptorQuery;
import java.lang.management.ManagementFactory;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 한 요청의 순수 planner 실행만 측정한다. serving 또는 oracle 성공 증거가 아니다. */
final class JourneyProfileMeasuredExecution {
	private JourneyProfileMeasuredExecution() { }

	static Observation measure(JourneyRaptorQuery query, RaptorRouteBundleRuntimeView runtime,
		ProfilePlanningLimits limits) {
		if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean)
			|| !bean.isThreadAllocatedMemorySupported() || !bean.isThreadAllocatedMemoryEnabled()) {
			throw new Unobservable("thread allocation measurement is unavailable");
		}
		long threadId = Thread.currentThread().threadId();
		return capture(query, runtime,
			() -> new JourneyProfileRaptorAdapter().planRuntime(query, runtime, null, limits),
			System::nanoTime, () -> bean.getThreadAllocatedBytes(threadId));
	}

	static Observation capture(JourneyRaptorQuery query, RaptorRouteBundleRuntimeView runtime,
		Supplier<PlanningResult> calculation, LongSupplier clock, LongSupplier allocations) {
		Objects.requireNonNull(query, "query");
		Objects.requireNonNull(runtime, "runtime");
		Objects.requireNonNull(calculation, "calculation");
		Objects.requireNonNull(clock, "clock");
		Objects.requireNonNull(allocations, "allocations");
		long beforeBytes = allocations.getAsLong();
		if (beforeBytes < 0) throw new Unobservable("initial allocation observation is unavailable");
		long started = clock.getAsLong();
		PlanningResult result = Objects.requireNonNull(calculation.get(), "planning result");
		long elapsed = clock.getAsLong() - started;
		long afterBytes = allocations.getAsLong();
		if (elapsed < 0 || afterBytes < beforeBytes) throw new Unobservable("measurement counter regressed");
		if (!query.requestId().equals(result.countSnapshot().requestId())) {
			throw new Unobservable("planner request identity mismatch");
		}
		return new Observation(query.requestId(), runtime.routeBundleSha256(), runtime.generation(),
			elapsed, afterBytes - beforeBytes, result);
	}

	record Observation(String requestId, String routeBundleSha256, long generation,
		long durationNanos, long allocatedBytes, PlanningResult result) {
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
