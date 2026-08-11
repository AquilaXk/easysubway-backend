package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class JourneyApplicationServiceTest {

	private static final Instant EFFECTIVE_INSTANT = Instant.parse("2026-08-11T00:00:00Z");
	private static final ActiveJourneySnapshotPort.ActiveJourneySnapshot SNAPSHOT =
		new ActiveJourneySnapshotPort.ActiveJourneySnapshot("snapshot-1", "bundle-1", 1, true);
	private static final JourneyRealtimePort.RealtimeObservation REALTIME =
		new JourneyRealtimePort.RealtimeObservation("realtime-1", "bundle-1", true);

	@Test
	void executesTimetableRequestWithPinnedSnapshotAndNoRealtime() {
		Fakes fakes = new Fakes();

		JourneyExecutionResult result = fakes.service().execute(request(JourneyRequest.Mode.TIMETABLE_REQUIRED));

		assertThat(result).isInstanceOf(JourneyExecutionResult.Success.class);
		JourneyExecutionResult.Success success = (JourneyExecutionResult.Success) result;
		assertThat(success.source()).isEqualTo(JourneyExecutionResult.Source.SERVER_TIMETABLE_RAPTOR);
		assertThat(success.bundleIdentity()).isEqualTo("bundle-1");
		assertThat(success.realtimeIdentity()).isNull();
		assertThat(success.candidates()).containsExactly("candidate-1", "candidate-2");
		assertThat(fakes.snapshotCalls).isEqualTo(1);
		assertThat(fakes.realtimeCalls).isZero();
		assertThat(fakes.raptorCalls).isEqualTo(1);
		assertThat(fakes.lastEffectiveInstant).isEqualTo(EFFECTIVE_INSTANT);
		assertThat(fakes.clock.instantCalls).isEqualTo(1);
	}

	@Test
	void executesRealtimeRequestUsingTheSameSnapshotAndEffectiveInstantAtEveryPort() {
		Fakes fakes = new Fakes();

		JourneyExecutionResult result = fakes.service().execute(request(JourneyRequest.Mode.REALTIME_REQUIRED));

		assertThat(result).isInstanceOf(JourneyExecutionResult.Success.class);
		JourneyExecutionResult.Success success = (JourneyExecutionResult.Success) result;
		assertThat(success.realtimeIdentity()).isEqualTo("realtime-1");
		assertThat(fakes.snapshotCalls).isEqualTo(1);
		assertThat(fakes.realtimeCalls).isEqualTo(1);
		assertThat(fakes.raptorCalls).isEqualTo(1);
		assertThat(fakes.lastSnapshot).isSameAs(SNAPSHOT);
		assertThat(fakes.lastRealtime).isSameAs(REALTIME);
		assertThat(fakes.effectiveInstants).containsOnly(EFFECTIVE_INSTANT);
	}

	@Test
	void stopsBeforeRealtimeAndRaptorWhenSnapshotIsUnavailable() {
		for (boolean throwsFailure : List.of(false, true)) {
			Fakes fakes = new Fakes();
			fakes.snapshot = null;
			if (throwsFailure) {
				fakes.snapshotFailure = new IllegalStateException("internal snapshot detail");
			}

			assertFailure(fakes.service().execute(request(JourneyRequest.Mode.REALTIME_REQUIRED)),
				JourneyExecutionFailure.Reason.ACTIVE_SNAPSHOT_UNAVAILABLE);
			assertThat(fakes.snapshotCalls).isEqualTo(1);
			assertThat(fakes.realtimeCalls).isZero();
			assertThat(fakes.raptorCalls).isZero();
		}
	}

	@Test
	void stopsBeforeRealtimeAndRaptorWhenSnapshotIsStale() {
		Fakes fakes = new Fakes();
		fakes.snapshot = new ActiveJourneySnapshotPort.ActiveJourneySnapshot("snapshot-1", "bundle-1", 1, false);

		assertFailure(fakes.service().execute(request(JourneyRequest.Mode.REALTIME_REQUIRED)),
			JourneyExecutionFailure.Reason.ACTIVE_SNAPSHOT_STALE);
		assertThat(fakes.realtimeCalls).isZero();
		assertThat(fakes.raptorCalls).isZero();
	}

	@Test
	void stopsBeforeRaptorWhenRealtimeIsUnavailableStaleOrMismatched() {
		for (JourneyRealtimePort.RealtimeObservation observation : List.of(
			new JourneyRealtimePort.RealtimeObservation("realtime-1", "bundle-1", false),
			new JourneyRealtimePort.RealtimeObservation("realtime-1", "other-bundle", true))) {
			Fakes fakes = new Fakes();
			fakes.realtime = observation;

			JourneyExecutionResult result = fakes.service().execute(request(JourneyRequest.Mode.REALTIME_REQUIRED));

			assertFailure(result, observation.fresh()
				? JourneyExecutionFailure.Reason.REALTIME_IDENTITY_MISMATCH
				: JourneyExecutionFailure.Reason.REALTIME_STALE);
			assertThat(fakes.raptorCalls).isZero();
		}
		for (boolean throwsFailure : List.of(false, true)) {
			Fakes unavailable = new Fakes();
			unavailable.realtime = null;
			if (throwsFailure) {
				unavailable.realtimeFailure = new IllegalStateException("internal realtime detail");
			}
			assertFailure(unavailable.service().execute(request(JourneyRequest.Mode.REALTIME_REQUIRED)),
				JourneyExecutionFailure.Reason.REALTIME_UNAVAILABLE);
			assertThat(unavailable.realtimeCalls).isEqualTo(1);
			assertThat(unavailable.raptorCalls).isZero();
		}
	}

	@Test
	void mapsPlannerExceptionNullAndEmptyOutputToClosedFailures() {
		Fakes exception = new Fakes();
		exception.raptorFailure = new IllegalStateException("boom");
		assertFailure(exception.service().execute(request(JourneyRequest.Mode.TIMETABLE_REQUIRED)),
			JourneyExecutionFailure.Reason.RAPTOR_FAILED);

		Fakes nullOutput = new Fakes();
		nullOutput.candidates = null;
		assertFailure(nullOutput.service().execute(request(JourneyRequest.Mode.TIMETABLE_REQUIRED)),
			JourneyExecutionFailure.Reason.RAPTOR_FAILED);

		Fakes emptyOutput = new Fakes();
		emptyOutput.candidates = List.of();
		assertFailure(emptyOutput.service().execute(request(JourneyRequest.Mode.TIMETABLE_REQUIRED)),
			JourneyExecutionFailure.Reason.NO_ROUTE);
	}

	@Test
	void cancellationBeforeEveryReturnedBoundaryAndAfterRaptorCannotBecomeSuccess() {
		Fakes beforeSnapshot = new Fakes();
		beforeSnapshot.cancelled.set(true);
		assertFailure(beforeSnapshot.service().execute(request(JourneyRequest.Mode.REALTIME_REQUIRED,
			beforeSnapshot.cancelled)), JourneyExecutionFailure.Reason.CANCELLED);
		assertThat(beforeSnapshot.snapshotCalls).isZero();
		assertThat(beforeSnapshot.realtimeCalls).isZero();
		assertThat(beforeSnapshot.raptorCalls).isZero();

		assertCancelled(fakes -> fakes.cancelAfterSnapshot = true, 1, 0, 0);
		assertCancelled(fakes -> fakes.cancelAfterRealtime = true, 1, 1, 0);
		assertCancelled(fakes -> fakes.cancelAfterRaptor = true, 1, 1, 1);
	}

	private static void assertCancelled(
		java.util.function.Consumer<Fakes> configure,
		int expectedSnapshotCalls,
		int expectedRealtimeCalls,
		int expectedRaptorCalls
	) {
		Fakes fakes = new Fakes();
		configure.accept(fakes);
		assertFailure(fakes.service().execute(request(JourneyRequest.Mode.REALTIME_REQUIRED, fakes.cancelled)),
			JourneyExecutionFailure.Reason.CANCELLED);
		assertThat(fakes.snapshotCalls).isEqualTo(expectedSnapshotCalls);
		assertThat(fakes.realtimeCalls).isEqualTo(expectedRealtimeCalls);
		assertThat(fakes.raptorCalls).isEqualTo(expectedRaptorCalls);
	}

	private static JourneyRequest request(JourneyRequest.Mode mode) {
		return request(mode, new AtomicBoolean());
	}

	private static JourneyRequest request(JourneyRequest.Mode mode, AtomicBoolean cancelled) {
		return new JourneyRequest("request-1", "station-origin", "station-destination", mode, cancelled::get);
	}

	private static void assertFailure(JourneyExecutionResult result, JourneyExecutionFailure.Reason reason) {
		assertThat(result).isEqualTo(new JourneyExecutionFailure(reason));
	}

	private static final class Fakes {
		private final AtomicBoolean cancelled = new AtomicBoolean();
		private ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot = SNAPSHOT;
		private JourneyRealtimePort.RealtimeObservation realtime = REALTIME;
		private List<String> candidates = List.of("candidate-1", "candidate-2");
		private RuntimeException snapshotFailure;
		private RuntimeException realtimeFailure;
		private RuntimeException raptorFailure;
		private boolean cancelAfterSnapshot;
		private boolean cancelAfterRealtime;
		private boolean cancelAfterRaptor;
		private int snapshotCalls;
		private int realtimeCalls;
		private int raptorCalls;
		private ActiveJourneySnapshotPort.ActiveJourneySnapshot lastSnapshot;
		private JourneyRealtimePort.RealtimeObservation lastRealtime;
		private Instant lastEffectiveInstant;
		private final List<Instant> effectiveInstants = new ArrayList<>();
		private final CountingClock clock = new CountingClock(EFFECTIVE_INSTANT);

		private JourneyApplicationService service() {
			return new JourneyApplicationService(effectiveInstant -> {
				snapshotCalls++;
				record(effectiveInstant);
				if (snapshotFailure != null) {
					throw snapshotFailure;
				}
				if (cancelAfterSnapshot) {
					cancelled.set(true);
				}
				return snapshot;
			}, (request, activeSnapshot, effectiveInstant) -> {
				realtimeCalls++;
				lastSnapshot = activeSnapshot;
				record(effectiveInstant);
				if (realtimeFailure != null) {
					throw realtimeFailure;
				}
				if (cancelAfterRealtime) {
					cancelled.set(true);
				}
				return realtime;
			}, (request, activeSnapshot, effectiveInstant, realtimeObservation) -> {
				raptorCalls++;
				lastSnapshot = activeSnapshot;
				lastRealtime = realtimeObservation;
				record(effectiveInstant);
				if (cancelAfterRaptor) {
					cancelled.set(true);
				}
				if (raptorFailure != null) {
					throw raptorFailure;
				}
				return candidates;
			}, clock);
		}

		private void record(Instant effectiveInstant) {
			lastEffectiveInstant = effectiveInstant;
			effectiveInstants.add(effectiveInstant);
		}
	}

	private static final class CountingClock extends Clock {
		private final Instant instant;
		private int instantCalls;

		private CountingClock(Instant instant) {
			this.instant = instant;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			instantCalls++;
			return instant;
		}
	}
}
