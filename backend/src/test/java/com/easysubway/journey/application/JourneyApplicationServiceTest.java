package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class JourneyApplicationServiceTest {

	private static final Instant CAPTURED_AT = Instant.parse("2026-08-11T00:00:00Z");
	private static final Instant VALID_UNTIL = Instant.parse("2026-08-11T00:10:00Z");
	private static final String ROUTE_BUNDLE_SHA = "a".repeat(64);
	private static final JourneyRaptorPort.ScanMetrics OBSERVED_SCAN = new JourneyRaptorPort.ScanMetrics(1, 2, 3);
	private static final JourneyExecutionResult.ActiveReadinessIdentity ACTIVE_READINESS_IDENTITY =
		new JourneyExecutionResult.ActiveReadinessIdentity(
			1, "journey-v3-active-readiness", "backend-a", "d".repeat(64),
			"sha256:" + "f".repeat(64), "1".repeat(64), "2".repeat(64), ROUTE_BUNDLE_SHA,
			"bundle-1", 1, 1, "Asia/Seoul", "03:00", 1, true, false,
			VALID_UNTIL, CAPTURED_AT.minusSeconds(60), "3".repeat(64));
	private static final JourneyExecutionResult.ActiveServingIdentity ACTIVE_SERVING_IDENTITY =
		new JourneyExecutionResult.ActiveServingIdentity(
			JourneyExecutionResult.ActiveServingIdentity.Status.OBSERVED,
			"b".repeat(64), "c".repeat(64), "sha256:" + "d".repeat(64), "e".repeat(40), "03:00");
	private static final ActiveJourneySnapshotPort.RequestExecutionIdentity REQUEST_EXECUTION_IDENTITY =
		new ActiveJourneySnapshotPort.RequestExecutionIdentity(
			"01K1Y000000000000000000000", ROUTE_BUNDLE_SHA, 1,
			ACTIVE_READINESS_IDENTITY, ACTIVE_SERVING_IDENTITY);
	private static final ActiveJourneySnapshotPort.ActiveJourneySnapshot SNAPSHOT = snapshot(VALID_UNTIL, true);
	private static final JourneyRealtimePort.RealtimeObservation REALTIME = realtime(
		Instant.parse("2026-08-11T00:08:00Z"), ROUTE_BUNDLE_SHA, true
	);

	@Test
	void executesTimetableRequestWithCompletePinnedIdentityAndNoRealtime() {
		Fakes fakes = new Fakes();
		List<JourneyCandidate> plannerCandidates = new ArrayList<>(List.of(
			candidate("journey-1", JourneyCandidate.TimeSource.TIMETABLE),
			candidate("journey-2", JourneyCandidate.TimeSource.TIMETABLE)
		));
		fakes.planResult = new JourneyRaptorPort.PlanResult("query-1", plannerCandidates, OBSERVED_SCAN,
			JourneyRaptorPort.RouteBoundaryReceipt.observed(0));

		JourneyRequest request = request(JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, 2);
		JourneyExecutionResult result = fakes.service().execute(request);
		plannerCandidates.clear();

		assertThat(result).isInstanceOf(JourneyExecutionResult.Success.class);
		JourneyExecutionResult.Success success = (JourneyExecutionResult.Success) result;
		assertThat(success.contractVersion()).isEqualTo("JOURNEY_SEARCH_V3");
		assertThat(success.source()).isEqualTo(JourneyExecutionResult.Source.SERVER_TIMETABLE_RAPTOR);
		assertThat(success.requestId()).isEqualTo("01K1Y000000000000000000000");
		assertThat(success.queryId()).isEqualTo("query-1");
		assertThat(success.calculatedAt()).isEqualTo(CAPTURED_AT);
		assertThat(success.validUntil()).isEqualTo(VALID_UNTIL);
		assertThat(success.effectiveDepartureTime()).isEqualTo(CAPTURED_AT);
		assertThat(success.serviceDate()).isEqualTo(LocalDate.parse("2026-08-11"));
		assertThat(success.serviceTimezone()).isEqualTo("Asia/Seoul");
		assertThat(success.executionObservation().activeServingIdentity())
			.isEqualTo(JourneyExecutionResult.ActiveServingIdentity.unobservable());
		assertThat(success.executionObservation().activeReadinessIdentity()).isNull();
		assertThat(success.executionObservation().boundaryObservation())
			.isEqualTo(JourneyExecutionResult.BoundaryObservation.unobservable());
		assertThat(success.sourceIdentity()).isEqualTo(new JourneyExecutionResult.SourceIdentity(
			"bundle-1", ROUTE_BUNDLE_SHA, "timetable-1", "accessibility-1", null
		));
		assertThat(success.requestPolicy()).isEqualTo(new JourneyExecutionResult.RequestPolicy(
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE,
			0,
			2
		));
		assertThat(success.journeys()).extracting(JourneyCandidate::journeyId)
			.containsExactly("journey-1", "journey-2");
		assertThatThrownBy(() -> success.journeys().add(candidate(
			"journey-3", JourneyCandidate.TimeSource.TIMETABLE
		))).isInstanceOf(UnsupportedOperationException.class);
		assertThat(fakes.snapshotCalls).isEqualTo(1);
		assertThat(fakes.realtimeCalls).isZero();
		assertThat(fakes.raptorCalls).isEqualTo(1);
		assertThat(fakes.lastEffectiveInstant).isEqualTo(CAPTURED_AT);
		assertThat(fakes.lastSnapshotRequest).isSameAs(request);
		assertThat(fakes.clock.instantCalls).isEqualTo(1);
	}

	@Test
	void bindsMeasurementToTheSameSnapshotAndRaptorRequestIdentity() {
		Fakes fakes = new Fakes();
		fakes.snapshot = snapshot(
			VALID_UNTIL,
			true,
			ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.observed(0, 0),
			ActiveJourneySnapshotPort.SnapshotMeasurementReceipt.observed(
				REQUEST_EXECUTION_IDENTITY, 0, 0, 0));
		fakes.planResult = new JourneyRaptorPort.PlanResult(
			"query-1",
			List.of(candidate("journey-1", JourneyCandidate.TimeSource.TIMETABLE)),
			OBSERVED_SCAN,
			JourneyRaptorPort.RouteBoundaryReceipt.observed(0),
			JourneyRaptorPort.RouteMeasurementReceipt.observed(REQUEST_EXECUTION_IDENTITY, 0));

		var result = fakes.service().execute(request(JourneyRequest.TimePolicy.TIMETABLE_REQUIRED));

		assertThat(result).isInstanceOfSatisfying(JourneyExecutionResult.Success.class, success -> {
			assertThat(success.executionObservation().activeReadinessIdentity())
				.isEqualTo(ACTIVE_READINESS_IDENTITY);
			assertThat(success.executionObservation().activeServingIdentity()).isEqualTo(ACTIVE_SERVING_IDENTITY);
			assertThat(success.executionObservation().boundaryObservation())
				.isEqualTo(JourneyExecutionResult.BoundaryObservation.observed(0, 0, 0, 0));
		});
	}

	@Test
	void keepsOrdinaryServingSuccessfulWhenMeasurementIdentitiesDoNotMatch() {
		var otherIdentity = new ActiveJourneySnapshotPort.RequestExecutionIdentity(
			"01K1Y000000000000000000001", ROUTE_BUNDLE_SHA, 1,
			ACTIVE_READINESS_IDENTITY, ACTIVE_SERVING_IDENTITY);
		Fakes fakes = new Fakes();
		fakes.snapshot = snapshot(
			VALID_UNTIL,
			true,
			ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.observed(0, 0),
			ActiveJourneySnapshotPort.SnapshotMeasurementReceipt.observed(
				REQUEST_EXECUTION_IDENTITY, 0, 0, 0));
		fakes.planResult = new JourneyRaptorPort.PlanResult(
			"query-1",
			List.of(candidate("journey-1", JourneyCandidate.TimeSource.TIMETABLE)),
			OBSERVED_SCAN,
			JourneyRaptorPort.RouteBoundaryReceipt.observed(0),
			JourneyRaptorPort.RouteMeasurementReceipt.observed(otherIdentity, 0));

		var result = fakes.service().execute(request(JourneyRequest.TimePolicy.TIMETABLE_REQUIRED));

		assertThat(result).isInstanceOfSatisfying(JourneyExecutionResult.Success.class, success -> {
			assertThat(success.executionObservation().activeReadinessIdentity()).isNull();
			assertThat(success.executionObservation().activeServingIdentity())
				.isEqualTo(JourneyExecutionResult.ActiveServingIdentity.unobservable());
			assertThat(success.executionObservation().boundaryObservation())
				.isEqualTo(JourneyExecutionResult.BoundaryObservation.unobservable());
		});
	}

	@Test
	void executesRealtimeRequestUsingTheSameSnapshotAndMinimumValidity() {
		Fakes fakes = new Fakes();
		fakes.planResult = planResult(JourneyCandidate.TimeSource.REALTIME);
		JourneyRequest request = request(JourneyRequest.TimePolicy.REALTIME_REQUIRED);

		JourneyExecutionResult result = fakes.service().execute(request);

		assertThat(result).isInstanceOf(JourneyExecutionResult.Success.class);
		JourneyExecutionResult.Success success = (JourneyExecutionResult.Success) result;
		assertThat(success.sourceIdentity().realtimeSnapshotId()).isEqualTo("realtime-1");
		assertThat(success.executionObservation().boundaryObservation())
			.isEqualTo(JourneyExecutionResult.BoundaryObservation.unobservable());
		assertThat(success.validUntil()).isEqualTo(REALTIME.validUntil());
		assertThat(success.journeys()).allSatisfy(candidate -> {
			assertThat(candidate.timeSource()).isEqualTo(JourneyCandidate.TimeSource.REALTIME);
			assertThat(candidate.realtimeDepartureTime()).isNotNull();
			assertThat(candidate.realtimeArrivalTime()).isNotNull();
		});
		assertThat(fakes.snapshotCalls).isEqualTo(1);
		assertThat(fakes.realtimeCalls).isEqualTo(1);
		assertThat(fakes.raptorCalls).isEqualTo(1);
		assertThat(fakes.clock.instantCalls).isEqualTo(1);
		assertThat(fakes.lastSnapshot).isSameAs(SNAPSHOT);
		assertThat(fakes.lastRealtime).isSameAs(REALTIME);
		assertThat(fakes.effectiveInstants).containsExactly(CAPTURED_AT, CAPTURED_AT, CAPTURED_AT);
		assertThat(fakes.requests).containsExactly(request, request);
	}

	@Test
	void failsClosedWhenAnyRequiredTimetableReceiptIsUnobservable() {
		Fakes missingSnapshotReceipt = new Fakes();
		missingSnapshotReceipt.snapshot = snapshot(VALID_UNTIL, true,
			ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.unobservable());

		assertFailure(missingSnapshotReceipt.service().execute(request(JourneyRequest.TimePolicy.TIMETABLE_REQUIRED)),
			JourneyExecutionFailure.Reason.RAPTOR_FAILED);

		Fakes missingRouteReceipt = new Fakes();
		missingRouteReceipt.planResult = planResult(
			JourneyCandidate.TimeSource.TIMETABLE,
			JourneyRaptorPort.RouteBoundaryReceipt.unobservable());

		assertFailure(missingRouteReceipt.service().execute(request(JourneyRequest.TimePolicy.TIMETABLE_REQUIRED)),
			JourneyExecutionFailure.Reason.RAPTOR_FAILED);
	}

	@Test
	void executesScheduledRequestWithItsExactRequestedAtAndServiceDate() {
		Fakes fakes = new Fakes();
		Instant requestedAt = Instant.parse("2026-08-12T03:04:05Z");
		fakes.snapshot = snapshot(requestedAt.plusSeconds(600), true);
		fakes.realtime = realtime(requestedAt.plusSeconds(480), ROUTE_BUNDLE_SHA, true);
		fakes.planResult = planResult(JourneyCandidate.TimeSource.REALTIME);
		JourneyRequest request = request(new JourneyRequest.Departure.Scheduled(requestedAt),
			JourneyRequest.TimePolicy.REALTIME_REQUIRED, 1, fakes.cancelled);

		JourneyExecutionResult result = fakes.service().execute(request);

		assertThat(result).isInstanceOf(JourneyExecutionResult.Success.class);
		JourneyExecutionResult.Success success = (JourneyExecutionResult.Success) result;
		assertThat(success.effectiveDepartureTime()).isEqualTo(requestedAt);
		assertThat(success.serviceDate()).isEqualTo(LocalDate.parse("2026-08-12"));
		assertThat(fakes.snapshotCalls).isEqualTo(1);
		assertThat(fakes.realtimeCalls).isEqualTo(1);
		assertThat(fakes.raptorCalls).isEqualTo(1);
		assertThat(fakes.clock.instantCalls).isEqualTo(1);
		assertThat(fakes.effectiveInstants).containsExactly(requestedAt, requestedAt, requestedAt);
	}

	@Test
	void resolvesServiceDateAtTheSharedSeoulThreeAmBoundary() {
		Fakes fakes = new Fakes();
		Instant beforeCutoff = Instant.parse("2026-08-11T17:59:00Z");
		fakes.snapshot = snapshot(beforeCutoff.plusSeconds(600), true);
		fakes.planResult = planResult(JourneyCandidate.TimeSource.TIMETABLE);

		JourneyExecutionResult.Success success = (JourneyExecutionResult.Success) fakes.service().execute(
			request(new JourneyRequest.Departure.Scheduled(beforeCutoff),
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, 1, fakes.cancelled));

		assertThat(success.serviceDayIdentity().serviceDate()).isEqualTo(LocalDate.parse("2026-08-11"));
		assertThat(success.serviceDayIdentity().timezone()).isEqualTo("Asia/Seoul");
		assertThat(success.serviceDayIdentity().cutoffLocalTime()).isEqualTo("03:00");
	}

	@Test
	void resolvesExactlyThreeAmAsTheCurrentSeoulServiceDate() {
		Fakes fakes = new Fakes();
		Instant atCutoff = Instant.parse("2026-08-11T18:00:00Z");
		fakes.snapshot = snapshot(atCutoff.plusSeconds(600), true);
		fakes.planResult = planResult(JourneyCandidate.TimeSource.TIMETABLE);

		JourneyExecutionResult.Success success = (JourneyExecutionResult.Success) fakes.service().execute(
			request(new JourneyRequest.Departure.Scheduled(atCutoff),
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, 1, fakes.cancelled));

		assertThat(success.serviceDayIdentity().serviceDate()).isEqualTo(LocalDate.parse("2026-08-12"));
		assertThat(success.serviceDayIdentity().cutoffLocalTime()).isEqualTo("03:00");
	}

	@Test
	void stopsBeforeRealtimeAndRaptorWhenSnapshotIsUnavailable() {
		for (boolean throwsFailure : List.of(false, true)) {
			Fakes fakes = new Fakes();
			fakes.snapshot = null;
			if (throwsFailure) fakes.snapshotFailure = new IllegalStateException("internal snapshot detail");

			assertFailure(fakes.service().execute(request(JourneyRequest.TimePolicy.REALTIME_REQUIRED)),
				JourneyExecutionFailure.Reason.ACTIVE_SNAPSHOT_UNAVAILABLE);
			assertThat(fakes.snapshotCalls).isEqualTo(1);
			assertThat(fakes.realtimeCalls).isZero();
			assertThat(fakes.raptorCalls).isZero();
		}
	}

	@Test
	void stopsBeforeRealtimeAndRaptorWhenSnapshotIsStaleOrExpired() {
		for (ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot : List.of(
			snapshot(VALID_UNTIL, false),
			snapshot(CAPTURED_AT, true)
		)) {
			Fakes fakes = new Fakes();
			fakes.snapshot = snapshot;

			assertFailure(fakes.service().execute(request(JourneyRequest.TimePolicy.REALTIME_REQUIRED)),
				JourneyExecutionFailure.Reason.ACTIVE_SNAPSHOT_STALE);
			assertThat(fakes.realtimeCalls).isZero();
			assertThat(fakes.raptorCalls).isZero();
		}
	}

	@Test
	void stopsBeforeRaptorWhenRealtimeIsUnavailableStaleExpiredOrMismatched() {
		List<JourneyRealtimePort.RealtimeObservation> observations = new ArrayList<>();
		observations.add(realtime(VALID_UNTIL, ROUTE_BUNDLE_SHA, false));
		observations.add(realtime(CAPTURED_AT, ROUTE_BUNDLE_SHA, true));
		observations.add(realtime(VALID_UNTIL, "b".repeat(64), true));
		for (JourneyRealtimePort.RealtimeObservation observation : observations) {
			Fakes fakes = new Fakes();
			fakes.realtime = observation;
			JourneyExecutionFailure.Reason expected = observation.fresh()
				&& observation.validUntil().isAfter(CAPTURED_AT)
				? JourneyExecutionFailure.Reason.REALTIME_IDENTITY_MISMATCH
				: JourneyExecutionFailure.Reason.REALTIME_STALE;

			assertFailure(fakes.service().execute(request(JourneyRequest.TimePolicy.REALTIME_REQUIRED)), expected);
			assertThat(fakes.raptorCalls).isZero();
		}
		for (boolean throwsFailure : List.of(false, true)) {
			Fakes unavailable = new Fakes();
			unavailable.realtime = null;
			if (throwsFailure) unavailable.realtimeFailure = new IllegalStateException("internal realtime detail");
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
		nullOutput.planResult = null;
		nullOutput.returnNullPlan = true;
		assertFailure(nullOutput.service().execute(request(JourneyRequest.TimePolicy.TIMETABLE_REQUIRED)),
			JourneyExecutionFailure.Reason.RAPTOR_FAILED);

		Fakes emptyOutput = new Fakes();
		emptyOutput.planResult = new JourneyRaptorPort.PlanResult("query-1", List.of(), OBSERVED_SCAN,
			JourneyRaptorPort.RouteBoundaryReceipt.observed(0));
		assertFailure(emptyOutput.service().execute(request(JourneyRequest.TimePolicy.TIMETABLE_REQUIRED)),
			JourneyExecutionFailure.Reason.NO_ROUTE);
	}

	@Test
	void rejectsDuplicateOverLimitAndModeMismatchedPlannerOutput() {
		Fakes duplicate = new Fakes();
		duplicate.planResult = new JourneyRaptorPort.PlanResult("query-1", List.of(
			candidate("journey-1", JourneyCandidate.TimeSource.TIMETABLE),
			candidate("journey-1", JourneyCandidate.TimeSource.TIMETABLE)
		), OBSERVED_SCAN, JourneyRaptorPort.RouteBoundaryReceipt.observed(0));
		assertFailure(duplicate.service().execute(request(JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, 2)),
			JourneyExecutionFailure.Reason.RAPTOR_FAILED);

		Fakes overLimit = new Fakes();
		overLimit.planResult = new JourneyRaptorPort.PlanResult("query-1", List.of(
			candidate("journey-1", JourneyCandidate.TimeSource.TIMETABLE),
			candidate("journey-2", JourneyCandidate.TimeSource.TIMETABLE)
		), OBSERVED_SCAN, JourneyRaptorPort.RouteBoundaryReceipt.observed(0));
		assertFailure(overLimit.service().execute(request(JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, 1)),
			JourneyExecutionFailure.Reason.RAPTOR_FAILED);

		Fakes mismatchedMode = new Fakes();
		mismatchedMode.planResult = planResult(JourneyCandidate.TimeSource.REALTIME);
		assertFailure(mismatchedMode.service().execute(request(JourneyRequest.TimePolicy.TIMETABLE_REQUIRED)),
			JourneyExecutionFailure.Reason.RAPTOR_FAILED);
	}

	@Test
	void closedSuccessRejectsInvalidRequestIdentity() {
		assertThatThrownBy(() -> new JourneyExecutionResult.Success(
			"not-a-ulid",
			"query-1",
			CAPTURED_AT,
			VALID_UNTIL,
			CAPTURED_AT,
			LocalDate.parse("2026-08-11"),
			1,
			OBSERVED_SCAN,
			new JourneyExecutionResult.SourceIdentity(
				"bundle-1", ROUTE_BUNDLE_SHA, "timetable-1", "accessibility-1", null
			),
			new JourneyExecutionResult.RequestPolicy(
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
				JourneyRequest.WalkingPace.STANDARD,
				JourneyRequest.MobilityProfile.STANDARD,
				JourneyRequest.ConstraintMode.NONE,
				0,
				1
			),
			List.of(candidate("journey-1", JourneyCandidate.TimeSource.TIMETABLE)),
			new JourneyExecutionResult.BoundaryObservation(
				JourneyExecutionResult.BoundaryObservation.Status.OBSERVED, 0L, 0L, 0L, 0L)
		)).isInstanceOf(IllegalArgumentException.class);
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
			() -> command("01K1Y000000000000000000000", "station-origin", "station-destination",
				new JourneyRequest.Departure.Now(), null, JourneyRequest.MobilityProfile.STANDARD,
				JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false),
			() -> command("01K1Y000000000000000000000", "station-origin", "station-destination",
				new JourneyRequest.Departure.Now(), JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, null,
				JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false),
			() -> command("01K1Y000000000000000000000", "station-origin", "station-destination",
				new JourneyRequest.Departure.Now(), JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
				JourneyRequest.MobilityProfile.STANDARD, null, 0, 1, () -> false),
			() -> command("01K1Y000000000000000000000", "station-origin", "station-destination",
				new JourneyRequest.Departure.Now(), JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
				JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, -1, 1, () -> false),
			() -> command("01K1Y000000000000000000000", "station-origin", "station-destination",
				new JourneyRequest.Departure.Now(), JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
				JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 4, 1, () -> false),
			() -> command("01K1Y000000000000000000000", "station-origin", "station-destination",
				new JourneyRequest.Departure.Now(), JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
				JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 0, 0, () -> false),
			() -> command("01K1Y000000000000000000000", "station-origin", "station-destination",
				new JourneyRequest.Departure.Now(), JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
				JourneyRequest.MobilityProfile.NO_STAIRS, JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false),
			() -> command("01K1Y000000000000000000000", "station-origin", "station-destination",
				new JourneyRequest.Departure.Now(), JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
				JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 0, 1, null)
		);

		for (java.util.function.Supplier<JourneyRequest> invalidCommand : invalidCommands) {
			assertThatThrownBy(invalidCommand::get).isInstanceOf(RuntimeException.class);
		}
		JourneyRequest noStairsStepFree = command("01K1Y000000000000000000000", "station-origin",
			"station-destination", new JourneyRequest.Departure.Now(), JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.MobilityProfile.NO_STAIRS, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE, 0, 1,
			() -> false);
		assertThat(noStairsStepFree.mobilityProfile()).isEqualTo(JourneyRequest.MobilityProfile.NO_STAIRS);
		assertThat(fakes.snapshotCalls).isZero();
		assertThat(fakes.realtimeCalls).isZero();
		assertThat(fakes.raptorCalls).isZero();
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
		return request(timePolicy, 1);
	}

	private static JourneyRequest request(JourneyRequest.TimePolicy timePolicy, int alternativeCount) {
		return request(new JourneyRequest.Departure.Now(), timePolicy, alternativeCount, new AtomicBoolean());
	}

	private static JourneyRequest request(JourneyRequest.TimePolicy timePolicy, AtomicBoolean cancelled) {
		return request(new JourneyRequest.Departure.Now(), timePolicy, 1, cancelled);
	}

	private static JourneyRequest request(
		JourneyRequest.Departure departure,
		JourneyRequest.TimePolicy timePolicy,
		int alternativeCount,
		AtomicBoolean cancelled
	) {
		return command("01K1Y000000000000000000000", "station-origin", "station-destination", departure,
			timePolicy, JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 0,
			alternativeCount, cancelled::get);
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
			JourneyRequest.WalkingPace.STANDARD, mobilityProfile, constraintMode, maxTransfers,
			alternativeCount, cancellationSignal);
	}

	private static ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot(Instant validUntil, boolean fresh) {
		return snapshot(validUntil, fresh, ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.observed(0, 0));
	}

	private static ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot(
		Instant validUntil,
		boolean fresh,
		ActiveJourneySnapshotPort.SnapshotBoundaryReceipt boundaryReceipt
	) {
		return snapshot(validUntil, fresh, boundaryReceipt,
			ActiveJourneySnapshotPort.SnapshotMeasurementReceipt.unobservable());
	}

	private static ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot(
		Instant validUntil,
		boolean fresh,
		ActiveJourneySnapshotPort.SnapshotBoundaryReceipt boundaryReceipt,
		ActiveJourneySnapshotPort.SnapshotMeasurementReceipt measurementReceipt
	) {
		var servingEvidence = measurementReceipt.status()
			== ActiveJourneySnapshotPort.SnapshotMeasurementReceipt.Status.OBSERVED
			? ActiveJourneySnapshotPort.ActiveServingEvidence.observed("b".repeat(64), "c".repeat(64))
			: ActiveJourneySnapshotPort.ActiveServingEvidence.unobservable();
		return new ActiveJourneySnapshotPort.ActiveJourneySnapshot(
			"snapshot-1", "bundle-1", ROUTE_BUNDLE_SHA, "timetable-1", "accessibility-1", 1,
			new TestRuntimeView(ROUTE_BUNDLE_SHA, 1), validUntil, fresh,
			servingEvidence, boundaryReceipt, measurementReceipt
		);
	}

	private static JourneyRealtimePort.RealtimeObservation realtime(
		Instant validUntil,
		String routeBundleSha,
		boolean fresh
	) {
		return new JourneyRealtimePort.RealtimeObservation(
			"realtime-1", routeBundleSha, new TestRealtimeView("realtime-1", routeBundleSha, 1),
			validUntil, fresh);
	}

	private static JourneyRaptorPort.PlanResult planResult(JourneyCandidate.TimeSource timeSource) {
		return planResult(timeSource, JourneyRaptorPort.RouteBoundaryReceipt.observed(0));
	}

	private static JourneyRaptorPort.PlanResult planResult(
		JourneyCandidate.TimeSource timeSource,
		JourneyRaptorPort.RouteBoundaryReceipt boundaryReceipt
	) {
		return new JourneyRaptorPort.PlanResult(
			"query-1", List.of(candidate("journey-1", timeSource)), OBSERVED_SCAN, boundaryReceipt);
	}

	private static JourneyCandidate candidate(String journeyId, JourneyCandidate.TimeSource timeSource) {
		Instant plannedDeparture = Instant.parse("2026-08-11T00:01:00Z");
		Instant plannedArrival = Instant.parse("2026-08-11T00:06:00Z");
		Instant realtimeDeparture = timeSource == JourneyCandidate.TimeSource.REALTIME
			? plannedDeparture.plusSeconds(30) : null;
		Instant realtimeArrival = timeSource == JourneyCandidate.TimeSource.REALTIME
			? plannedArrival.plusSeconds(30) : null;
		return new JourneyCandidate(
			journeyId,
			plannedDeparture,
			plannedArrival,
			realtimeDeparture,
			realtimeArrival,
			300,
			0,
			50,
			timeSource,
			new JourneyCandidate.Accessibility(true, List.of("STEP_FREE_PATH")),
			List.of(
				new JourneyCandidate.Entry("station-origin", 30),
				new JourneyCandidate.Ride(
					"line-1", "trip-1", "station-direction", "station-origin", "station-destination",
					plannedDeparture, plannedArrival, realtimeDeparture, realtimeArrival
				),
				new JourneyCandidate.Exit("station-destination", 20)
			)
		);
	}

	private record TestRuntimeView(String routeBundleSha256, long generation)
		implements JourneyRaptorRuntimeView {
	}

	private record TestRealtimeView(String identity, String routeBundleSha256, long generation)
		implements JourneyRaptorRealtimeView {
	}

	private static void assertFailure(JourneyExecutionResult result, JourneyExecutionFailure.Reason reason) {
		assertThat(result).isEqualTo(new JourneyExecutionFailure(reason));
	}

	private static final class Fakes {
		private final AtomicBoolean cancelled = new AtomicBoolean();
		private ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot = SNAPSHOT;
		private JourneyRealtimePort.RealtimeObservation realtime = REALTIME;
		private JourneyRaptorPort.PlanResult planResult;
		private RuntimeException snapshotFailure;
		private RuntimeException realtimeFailure;
		private RuntimeException raptorFailure;
		private boolean returnNullPlan;
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
		private JourneyRequest lastSnapshotRequest;
		private JourneyRealtimePort.RealtimeObservation lastRealtime;
		private Instant lastEffectiveInstant;
		private final List<Instant> effectiveInstants = new ArrayList<>();
		private final List<JourneyRequest> requests = new ArrayList<>();
		private final CountingClock clock = new CountingClock(CAPTURED_AT);

		private JourneyApplicationService service() {
			return new JourneyApplicationService(new ActiveJourneySnapshotPort() {
				@Override
				public ActiveJourneySnapshot requireActive(Instant effectiveInstant) {
					throw new AssertionError("service must use the request-aware snapshot boundary");
				}

				@Override
				public ActiveJourneySnapshot requireActive(JourneyRequest request, Instant effectiveInstant) {
					snapshotCalls++;
					lastSnapshotRequest = request;
					record(effectiveInstant);
					if (cancelAndFailSnapshot) {
						cancelled.set(true);
						throw new IllegalStateException("snapshot failure after cancellation");
					}
					if (snapshotFailure != null) throw snapshotFailure;
					if (cancelAfterSnapshot) cancelled.set(true);
					return snapshot;
				}

			}, (request, activeSnapshot, effectiveInstant) -> {
				realtimeCalls++;
				record(request);
				lastSnapshot = activeSnapshot;
				record(effectiveInstant);
				if (cancelAndFailRealtime) {
					cancelled.set(true);
					throw new IllegalStateException("realtime failure after cancellation");
				}
				if (realtimeFailure != null) throw realtimeFailure;
				if (cancelAfterRealtime) cancelled.set(true);
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
				if (cancelAfterRaptor) cancelled.set(true);
				if (raptorFailure != null) throw raptorFailure;
				if (returnNullPlan) return null;
				return planResult == null ? planResultFor(request.timePolicy()) : planResult;
			}, clock);
		}

		private JourneyRaptorPort.PlanResult planResultFor(JourneyRequest.TimePolicy timePolicy) {
			JourneyCandidate.TimeSource source = timePolicy == JourneyRequest.TimePolicy.REALTIME_REQUIRED
				? JourneyCandidate.TimeSource.REALTIME : JourneyCandidate.TimeSource.TIMETABLE;
			return JourneyApplicationServiceTest.planResult(source);
		}

		private void record(Instant effectiveInstant) {
			lastEffectiveInstant = effectiveInstant;
			effectiveInstants.add(effectiveInstant);
		}

		private void record(JourneyRequest request) {
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
