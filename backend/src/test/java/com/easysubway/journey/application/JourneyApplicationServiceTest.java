package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
		List<String> plannerCandidates = new ArrayList<>(List.of("candidate-1", "candidate-2"));
		fakes.candidates = plannerCandidates;

		JourneyExecutionResult result = fakes.service().execute(request(JourneyRequest.TimePolicy.TIMETABLE_REQUIRED));
		plannerCandidates.clear();

		assertThat(result).isInstanceOf(JourneyExecutionResult.Success.class);
		JourneyExecutionResult.Success success = (JourneyExecutionResult.Success) result;
		assertThat(success.source()).isEqualTo(JourneyExecutionResult.Source.SERVER_TIMETABLE_RAPTOR);
		assertThat(success.bundleIdentity()).isEqualTo("bundle-1");
		assertThat(success.realtimeIdentity()).isNull();
		assertThat(success.candidates()).containsExactly("candidate-1", "candidate-2");
		assertThatThrownBy(() -> success.candidates().add("candidate-3"))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThat(fakes.snapshotCalls).isEqualTo(1);
		assertThat(fakes.realtimeCalls).isZero();
		assertThat(fakes.raptorCalls).isEqualTo(1);
		assertThat(fakes.lastEffectiveInstant).isEqualTo(EFFECTIVE_INSTANT);
		assertThat(fakes.clock.instantCalls).isEqualTo(1);
	}

	@Test
	void executesRealtimeRequestUsingTheSameSnapshotAndEffectiveInstantAtEveryPort() {
		Fakes fakes = new Fakes();
		JourneyRequest request = request(JourneyRequest.TimePolicy.REALTIME_REQUIRED);

		JourneyExecutionResult result = fakes.service().execute(request);

		assertThat(result).isInstanceOf(JourneyExecutionResult.Success.class);
		JourneyExecutionResult.Success success = (JourneyExecutionResult.Success) result;
		assertThat(success.realtimeIdentity()).isEqualTo("realtime-1");
		assertThat(fakes.snapshotCalls).isEqualTo(1);
		assertThat(fakes.realtimeCalls).isEqualTo(1);
		assertThat(fakes.raptorCalls).isEqualTo(1);
		assertThat(fakes.clock.instantCalls).isEqualTo(1);
		assertThat(fakes.lastSnapshot).isSameAs(SNAPSHOT);
		assertThat(fakes.lastRealtime).isSameAs(REALTIME);
		assertThat(fakes.effectiveInstants).containsExactly(EFFECTIVE_INSTANT, EFFECTIVE_INSTANT, EFFECTIVE_INSTANT);
		assertThat(fakes.requests).hasSize(2);
		assertThat(fakes.requests.get(0)).isSameAs(request);
		assertThat(fakes.requests.get(1)).isSameAs(request);
	}

	@Test
	void executesScheduledRequestWithItsExactRequestedAtAtEveryPort() {
		Fakes fakes = new Fakes();
		Instant requestedAt = Instant.parse("2026-08-12T03:04:05Z");
		JourneyRequest request = request(new JourneyRequest.Departure.Scheduled(requestedAt),
			JourneyRequest.TimePolicy.REALTIME_REQUIRED, fakes.cancelled);

		JourneyExecutionResult result = fakes.service().execute(request);

		assertThat(result).isInstanceOf(JourneyExecutionResult.Success.class);
		assertThat(fakes.snapshotCalls).isEqualTo(1);
		assertThat(fakes.realtimeCalls).isEqualTo(1);
		assertThat(fakes.raptorCalls).isEqualTo(1);
		assertThat(fakes.clock.instantCalls).isEqualTo(1);
		assertThat(fakes.effectiveInstants).containsExactly(requestedAt, requestedAt, requestedAt);
		assertThat(fakes.requests).hasSize(2);
		assertThat(fakes.requests.get(0)).isSameAs(request);
		assertThat(fakes.requests.get(1)).isSameAs(request);
	}

	@Test
	void stopsBeforeRealtimeAndRaptorWhenSnapshotIsUnavailable() {
		for (boolean throwsFailure : List.of(false, true)) {
			Fakes fakes = new Fakes();
			fakes.snapshot = null;
			if (throwsFailure) {
				fakes.snapshotFailure = new IllegalStateException("internal snapshot detail");
			}

			assertFailure(fakes.service().execute(request(JourneyRequest.TimePolicy.REALTIME_REQUIRED)),
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

		assertFailure(fakes.service().execute(request(JourneyRequest.TimePolicy.REALTIME_REQUIRED)),
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

			JourneyExecutionResult result = fakes.service().execute(request(JourneyRequest.TimePolicy.REALTIME_REQUIRED));

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
			assertFailure(unavailable.service().execute(request(JourneyRequest.TimePolicy.REALTIME_REQUIRED)),
				JourneyExecutionFailure.Reason.REALTIME_UNAVAILABLE);
			assertThat(unavailable.realtimeCalls).isEqualTo(1);
			assertThat(unavailable.raptorCalls).isZero();
		}
	}

	@Test
	void mapsPlannerExceptionNullAndEmptyOutputToClosedFailures() {
		Fakes exception = new Fakes();
		exception.raptorFailure = new IllegalStateException("boom");
		assertFailure(exception.service().execute(request(JourneyRequest.TimePolicy.TIMETABLE_REQUIRED)),
			JourneyExecutionFailure.Reason.RAPTOR_FAILED);

		Fakes nullOutput = new Fakes();
		nullOutput.candidates = null;
		assertFailure(nullOutput.service().execute(request(JourneyRequest.TimePolicy.TIMETABLE_REQUIRED)),
			JourneyExecutionFailure.Reason.RAPTOR_FAILED);

		Fakes emptyOutput = new Fakes();
		emptyOutput.candidates = List.of();
		assertFailure(emptyOutput.service().execute(request(JourneyRequest.TimePolicy.TIMETABLE_REQUIRED)),
			JourneyExecutionFailure.Reason.NO_ROUTE);
	}

	@Test
	void rejectsBlankRequiredIdentitiesAndInvalidSuccessValues() {
		assertThat(new JourneyExecutionResult.Success(
			JourneyExecutionResult.Source.SERVER_TIMETABLE_RAPTOR, "bundle-1", null, List.of("candidate-1"))
			.realtimeIdentity()).isNull();
		assertThat(new JourneyExecutionResult.Success(
			JourneyExecutionResult.Source.SERVER_TIMETABLE_RAPTOR, "bundle-1", "realtime-1", List.of("candidate-1"))
			.realtimeIdentity()).isEqualTo("realtime-1");
		assertThatThrownBy(() -> new JourneyRequest("bad-request-id", "station-origin", "station-destination",
			new JourneyRequest.Departure.Now(), JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ActiveJourneySnapshotPort.ActiveJourneySnapshot(" ", "bundle-1", 1, true))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyRealtimePort.RealtimeObservation(" ", "bundle-1", true))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyExecutionResult.Success(null, "bundle-1", null, List.of("candidate-1")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyExecutionResult.Success(
			JourneyExecutionResult.Source.SERVER_TIMETABLE_RAPTOR, " ", null, List.of("candidate-1")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyExecutionResult.Success(
			JourneyExecutionResult.Source.SERVER_TIMETABLE_RAPTOR, "bundle-1", "", List.of("candidate-1")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyExecutionResult.Success(
			JourneyExecutionResult.Source.SERVER_TIMETABLE_RAPTOR, "bundle-1", " \t", List.of("candidate-1")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyExecutionResult.Success(
			JourneyExecutionResult.Source.SERVER_TIMETABLE_RAPTOR, "bundle-1", null, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsInvalidJourneySearchCommandWithoutPortMutation() {
		Fakes fakes = new Fakes();
		List<java.util.function.Supplier<JourneyRequest>> invalidCommands = List.of(
			() -> command("not-a-ulid", "station-origin", "station-destination", new JourneyRequest.Departure.Now(),
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.MobilityProfile.STANDARD,
				JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false),
			() -> command("01K1Y000000000000000000000", " ", "station-destination", new JourneyRequest.Departure.Now(),
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.MobilityProfile.STANDARD,
				JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false),
			() -> command("01K1Y000000000000000000000", "station-origin", " ", new JourneyRequest.Departure.Now(),
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.MobilityProfile.STANDARD,
				JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false),
			() -> command("01K1Y000000000000000000000", "station-origin", "station-destination", null,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.MobilityProfile.STANDARD,
				JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false),
			() -> {
				new JourneyRequest.Departure.Scheduled(null);
				return null;
			},
			() -> command("01K1Y000000000000000000000", "station-origin", "station-destination", new JourneyRequest.Departure.Now(),
				null, JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false),
			() -> command("01K1Y000000000000000000000", "station-origin", "station-destination", new JourneyRequest.Departure.Now(),
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, null, JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false),
			() -> command("01K1Y000000000000000000000", "station-origin", "station-destination", new JourneyRequest.Departure.Now(),
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.MobilityProfile.STANDARD, null, 0, 1, () -> false),
			() -> command("01K1Y000000000000000000000", "station-origin", "station-destination", new JourneyRequest.Departure.Now(),
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.MobilityProfile.STANDARD,
				JourneyRequest.ConstraintMode.NONE, -1, 1, () -> false),
			() -> command("01K1Y000000000000000000000", "station-origin", "station-destination", new JourneyRequest.Departure.Now(),
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.MobilityProfile.STANDARD,
				JourneyRequest.ConstraintMode.NONE, 4, 1, () -> false),
			() -> command("01K1Y000000000000000000000", "station-origin", "station-destination", new JourneyRequest.Departure.Now(),
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.MobilityProfile.STANDARD,
				JourneyRequest.ConstraintMode.NONE, 0, 0, () -> false),
			() -> command("01K1Y000000000000000000000", "station-origin", "station-destination", new JourneyRequest.Departure.Now(),
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.MobilityProfile.STANDARD,
				JourneyRequest.ConstraintMode.NONE, 0, 4, () -> false),
			() -> command("01K1Y000000000000000000000", "station-origin", "station-destination", new JourneyRequest.Departure.Now(),
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.MobilityProfile.NO_STAIRS,
				JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false),
			() -> command("01K1Y000000000000000000000", "station-origin", "station-destination", new JourneyRequest.Departure.Now(),
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.MobilityProfile.STANDARD,
				JourneyRequest.ConstraintMode.NONE, 0, 1, null)
		);

		for (java.util.function.Supplier<JourneyRequest> invalidCommand : invalidCommands) {
			assertThatThrownBy(invalidCommand::get).isInstanceOf(RuntimeException.class);
		}
		JourneyRequest noStairsStepFree = command("01K1Y000000000000000000000", "station-origin", "station-destination",
			new JourneyRequest.Departure.Now(), JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.MobilityProfile.NO_STAIRS, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE, 0, 1, () -> false);
		assertThat(noStairsStepFree.mobilityProfile()).isEqualTo(JourneyRequest.MobilityProfile.NO_STAIRS);
		assertThat(noStairsStepFree.constraintMode()).isEqualTo(JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE);
		assertThat(fakes.snapshotCalls).isZero();
		assertThat(fakes.realtimeCalls).isZero();
		assertThat(fakes.raptorCalls).isZero();
	}

	@Test
	void mapsNullRaptorCandidateToClosedFailureWithoutReturningSuccess() {
		Fakes fakes = new Fakes();
		fakes.candidates = new ArrayList<>();
		fakes.candidates.add("candidate-1");
		fakes.candidates.add(null);

		assertFailure(fakes.service().execute(request(JourneyRequest.TimePolicy.TIMETABLE_REQUIRED)),
			JourneyExecutionFailure.Reason.RAPTOR_FAILED);
		assertThat(fakes.snapshotCalls).isEqualTo(1);
		assertThat(fakes.realtimeCalls).isZero();
		assertThat(fakes.raptorCalls).isEqualTo(1);
	}

	@Test
	void cancellationBeforeEveryReturnedBoundaryAndAfterRaptorCannotBecomeSuccess() {
		Fakes beforeSnapshot = new Fakes();
		beforeSnapshot.cancelled.set(true);
		assertFailure(beforeSnapshot.service().execute(request(JourneyRequest.TimePolicy.REALTIME_REQUIRED,
			beforeSnapshot.cancelled)), JourneyExecutionFailure.Reason.CANCELLED);
		assertThat(beforeSnapshot.snapshotCalls).isZero();
		assertThat(beforeSnapshot.realtimeCalls).isZero();
		assertThat(beforeSnapshot.raptorCalls).isZero();

		assertCancelled(fakes -> fakes.cancelAfterSnapshot = true, 1, 0, 0);
		assertCancelled(fakes -> fakes.cancelAfterRealtime = true, 1, 1, 0);
		assertCancelled(fakes -> fakes.cancelAfterRaptor = true, 1, 1, 1);
	}

	@Test
	void cancellationSetByAThrowingPortWinsOverItsPortFailure() {
		assertCancellationWinsOverPortFailure(fakes -> fakes.cancelAndFailSnapshot = true, 1, 0, 0);
		assertCancellationWinsOverPortFailure(fakes -> fakes.cancelAndFailRealtime = true, 1, 1, 0);
		assertCancellationWinsOverPortFailure(fakes -> fakes.cancelAndFailRaptor = true, 1, 1, 1);
	}

	private static void assertCancelled(
		java.util.function.Consumer<Fakes> configure,
		int expectedSnapshotCalls,
		int expectedRealtimeCalls,
		int expectedRaptorCalls
	) {
		Fakes fakes = new Fakes();
		configure.accept(fakes);
		assertFailure(fakes.service().execute(request(JourneyRequest.TimePolicy.REALTIME_REQUIRED, fakes.cancelled)),
			JourneyExecutionFailure.Reason.CANCELLED);
		assertThat(fakes.snapshotCalls).isEqualTo(expectedSnapshotCalls);
		assertThat(fakes.realtimeCalls).isEqualTo(expectedRealtimeCalls);
		assertThat(fakes.raptorCalls).isEqualTo(expectedRaptorCalls);
	}

	private static void assertCancellationWinsOverPortFailure(
		java.util.function.Consumer<Fakes> configure,
		int expectedSnapshotCalls,
		int expectedRealtimeCalls,
		int expectedRaptorCalls
	) {
		Fakes fakes = new Fakes();
		configure.accept(fakes);
		assertFailure(fakes.service().execute(request(JourneyRequest.TimePolicy.REALTIME_REQUIRED, fakes.cancelled)),
			JourneyExecutionFailure.Reason.CANCELLED);
		assertThat(fakes.snapshotCalls).isEqualTo(expectedSnapshotCalls);
		assertThat(fakes.realtimeCalls).isEqualTo(expectedRealtimeCalls);
		assertThat(fakes.raptorCalls).isEqualTo(expectedRaptorCalls);
	}

	private static JourneyRequest request(JourneyRequest.TimePolicy timePolicy) {
		return request(timePolicy, new AtomicBoolean());
	}

	private static JourneyRequest request(JourneyRequest.TimePolicy timePolicy, AtomicBoolean cancelled) {
		return request(new JourneyRequest.Departure.Now(), timePolicy, cancelled);
	}

	private static JourneyRequest request(
		JourneyRequest.Departure departure,
		JourneyRequest.TimePolicy timePolicy,
		AtomicBoolean cancelled
	) {
		return command("01K1Y000000000000000000000", "station-origin", "station-destination", departure,
			timePolicy, JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 0, 1, cancelled::get);
	}

	private static JourneyRequest command(
		String requestId,
		String originStationId,
		String destinationStationId,
		JourneyRequest.Departure departure,
		JourneyRequest.TimePolicy timePolicy,
		JourneyRequest.MobilityProfile mobilityProfile,
		JourneyRequest.ConstraintMode constraintMode,
		int maxTransfers,
		int alternativeCount,
		java.util.function.BooleanSupplier cancellationSignal
	) {
		return new JourneyRequest(requestId, originStationId, destinationStationId, departure, timePolicy,
			mobilityProfile, constraintMode, maxTransfers, alternativeCount, cancellationSignal);
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
		private boolean cancelAndFailSnapshot;
		private boolean cancelAndFailRealtime;
		private boolean cancelAndFailRaptor;
		private int snapshotCalls;
		private int realtimeCalls;
		private int raptorCalls;
		private ActiveJourneySnapshotPort.ActiveJourneySnapshot lastSnapshot;
		private JourneyRealtimePort.RealtimeObservation lastRealtime;
		private Instant lastEffectiveInstant;
		private final List<Instant> effectiveInstants = new ArrayList<>();
		private JourneyRequest lastRequest;
		private final List<JourneyRequest> requests = new ArrayList<>();
		private final CountingClock clock = new CountingClock(EFFECTIVE_INSTANT);

		private JourneyApplicationService service() {
			return new JourneyApplicationService(effectiveInstant -> {
				snapshotCalls++;
				record(effectiveInstant);
				if (cancelAndFailSnapshot) {
					cancelled.set(true);
					throw new IllegalStateException("snapshot failure after cancellation");
				}
				if (snapshotFailure != null) {
					throw snapshotFailure;
				}
				if (cancelAfterSnapshot) {
					cancelled.set(true);
				}
				return snapshot;
			}, (request, activeSnapshot, effectiveInstant) -> {
				realtimeCalls++;
				record(request);
				lastSnapshot = activeSnapshot;
				record(effectiveInstant);
				if (cancelAndFailRealtime) {
					cancelled.set(true);
					throw new IllegalStateException("realtime failure after cancellation");
				}
				if (realtimeFailure != null) {
					throw realtimeFailure;
				}
				if (cancelAfterRealtime) {
					cancelled.set(true);
				}
				return realtime;
			}, (request, activeSnapshot, effectiveInstant, realtimeObservation) -> {
				raptorCalls++;
				record(request);
				lastSnapshot = activeSnapshot;
				lastRealtime = realtimeObservation;
				record(effectiveInstant);
				if (cancelAndFailRaptor) {
					cancelled.set(true);
					throw new IllegalStateException("raptor failure after cancellation");
				}
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

		private void record(JourneyRequest request) {
			lastRequest = request;
			requests.add(request);
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
