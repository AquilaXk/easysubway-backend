package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class JourneyProfileDeadlineExecutorTest {
	private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

	@Test
	void marksTheCopiedQueryCancelledBeforeSuppressingLateSuccess() {
		var service = new JourneyProfileApplicationService(
			(query, reference, measurement) -> snapshot(),
			(query, snapshot, realtime) -> {
				while (!query.isCancelled()) Thread.onSpinWait();
				throw new IllegalStateException("cancelled");
			},
			Clock.fixed(NOW, ZoneOffset.UTC));
		try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
			var result = new JourneyProfileDeadlineExecutor(service, executor).execute(query(), Duration.ofNanos(1));

			assertThat(result).isInstanceOf(JourneyProfileDeadlineExecutor.TimedOut.class);
		}
	}

	@Test
	void completesWithinTheCallerDeadline() {
		var service = service((query, snapshot, realtime) ->
			new JourneyProfileRaptorPort.DepartureWindowPlan(
				(JourneyRaptorQuery.DepartBetween) query.temporalQuery(), List.of()));
		try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
			var result = new JourneyProfileDeadlineExecutor(service, executor)
				.execute(query(), Duration.ofSeconds(1));

			assertThat(result).isInstanceOf(JourneyProfileDeadlineExecutor.Completed.class);
		}
	}

	@Test
	void rejectsNonPositiveAndOverflowingCallerDeadlines() {
		var service = service((query, snapshot, realtime) ->
			new JourneyProfileRaptorPort.DepartureWindowPlan(
				(JourneyRaptorQuery.DepartBetween) query.temporalQuery(), List.of()));
		try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
			var deadlineExecutor = new JourneyProfileDeadlineExecutor(service, executor);

			assertThatThrownBy(() -> deadlineExecutor.execute(query(), Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> deadlineExecutor.execute(query(), Duration.ofSeconds(Long.MAX_VALUE)))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	private static JourneyProfileApplicationService service(JourneyProfileRaptorPort raptor) {
		return new JourneyProfileApplicationService(
			(query, reference, measurement) -> snapshot(), raptor, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static JourneyRaptorQuery query() {
		return new JourneyRaptorQuery("01ARZ3NDEKTSV4RRFFQ69G5FAV", "station-a", "station-b",
			new JourneyRaptorQuery.DepartBetween(Instant.parse("2026-09-01T00:00:00Z"),
				Instant.parse("2026-09-01T00:01:00Z")), JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.STANDARD, JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false);
	}

	private static ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot() {
		JourneyRaptorRuntimeView runtime = new JourneyRaptorRuntimeView() {
			@Override public String routeBundleSha256() { return "a".repeat(64); }
			@Override public long generation() { return 1; }
		};
		return new ActiveJourneySnapshotPort.ActiveJourneySnapshot("snapshot", "bundle", "a".repeat(64),
			"timetable", "accessibility", 1, runtime, Instant.parse("2026-09-02T00:00:00Z"), true,
			ActiveJourneySnapshotPort.ActiveServingEvidence.unobservable(),
			ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.observed(0, 0));
	}
}
