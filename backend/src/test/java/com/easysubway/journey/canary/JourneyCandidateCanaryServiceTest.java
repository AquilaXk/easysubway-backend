package com.easysubway.journey.canary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.easysubway.journey.application.ActiveJourneySnapshotPort;
import com.easysubway.journey.application.JourneyCandidate;
import com.easysubway.journey.application.JourneyRaptorPort;
import com.easysubway.journey.application.JourneyRaptorRuntimeView;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.bundle.RouteBundleActivationException;
import com.easysubway.journey.bundle.RouteBundleActivationRegistry;
import com.easysubway.journey.bundle.RouteBundleAdmissionEvidence;
import com.easysubway.journey.bundle.RouteBundleIdentity;
import com.easysubway.journey.bundle.RouteBundleRuntimeView;
import com.easysubway.journey.bundle.VerifiedRouteBundleCandidate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JourneyCandidateCanaryServiceTest {

	private static final Instant CAPTURED_AT = Instant.parse("2026-08-13T03:00:00Z");
	/** 2026-08-13 10:00 KST. CAPTURED_AT(12:00 KST)이 속한 운행일의 대표 출발 시각이다. */
	private static final Instant REPRESENTATIVE_AT = Instant.parse("2026-08-13T01:00:00Z");
	private static final String SHA_A = JourneyCandidateCanaryCommandParserTest.SHA_A;
	private static final JourneyRaptorPort.ScanMetrics OBSERVED_SCAN = new JourneyRaptorPort.ScanMetrics(1, 2, 3);
	private final RouteBundleActivationRegistry registry = mock(RouteBundleActivationRegistry.class);
	private final JourneyRaptorPort raptorPort = mock(JourneyRaptorPort.class);
	private final JourneyCandidateCanaryService service = new JourneyCandidateCanaryService(
		registry, raptorPort, Clock.fixed(CAPTURED_AT, ZoneOffset.UTC));

	@Test
	void plansTheExactStagedRuntimeOnceAndReturnsClosedCanonicalEvidence() {
		var staged = staged(SHA_A, 1);
		when(registry.candidateExecutionSnapshot()).thenReturn(staged);
		when(registry.candidateSnapshot()).thenReturn(candidateProjection(staged));
		when(raptorPort.plan(any(), any(), any(), org.mockito.ArgumentMatchers.isNull(), any()))
			.thenReturn(new JourneyRaptorPort.PlanResult(
				JourneyCandidateCanaryCommandParserTest.REQUEST_ID, List.of(mock(JourneyCandidate.class)), OBSERVED_SCAN,
				JourneyRaptorPort.RouteBoundaryReceipt.observed(0), JourneyRaptorPort.RouteMeasurementReceipt.unobservable(), new com.easysubway.journey.application.JourneyAlternatives.StairFreeAlternative(com.easysubway.journey.application.JourneyAlternatives.StairFreeStatus.NOT_FOUND, com.easysubway.journey.application.JourneyAlternatives.FacilityStatus.UNOBSERVED)));

		var result = service.execute(command(SHA_A, 1));

		assertThat(result.schemaVersion()).isOne();
		assertThat(result.artifactKind()).isEqualTo("journey-v3-candidate-canary-result");
		assertThat(result.canaryRequestIdentity()).isEqualTo("canary-request-236");
		assertThat(result.requestId()).isEqualTo(JourneyCandidateCanaryCommandParserTest.REQUEST_ID);
		assertThat(result.candidateManifestSha256()).isEqualTo(SHA_A);
		assertThat(result.candidateGeneration()).isOne();
		assertThat(result.bundleId()).isEqualTo("bundle-a");
		assertThat(result.bundleReleaseSequence()).isEqualTo(31);
		assertThat(result.queryId()).isEqualTo(JourneyCandidateCanaryCommandParserTest.REQUEST_ID);
		assertThat(result.capturedAt()).isEqualTo(CAPTURED_AT);
		assertThat(result.passed()).isTrue();
		assertThat(result.legacyGraphSuccessCount()).isZero();
		assertThat(result.localRouteInvocationCount()).isZero();
		assertThat(result.staleJourneyServedCount()).isZero();
		assertThat(result.alternateEndpointSuccessCount()).isZero();
		assertThat(result.evidenceSha256()).matches("[0-9a-f]{64}");

		var request = ArgumentCaptor.forClass(JourneyRequest.class);
		var snapshot = ArgumentCaptor.forClass(ActiveJourneySnapshotPort.ActiveJourneySnapshot.class);
		verify(raptorPort).plan(request.capture(), snapshot.capture(), org.mockito.ArgumentMatchers.eq(REPRESENTATIVE_AT),
			org.mockito.ArgumentMatchers.isNull(), any());
		assertThat(request.getValue().departure())
			.isEqualTo(new JourneyRequest.Departure.Scheduled(REPRESENTATIVE_AT));
		assertThat(request.getValue().timePolicy()).isEqualTo(JourneyRequest.TimePolicy.TIMETABLE_REQUIRED);
		assertThat(request.getValue().isCancelled()).isFalse();
		assertThat(snapshot.getValue().routeBundleSha256()).isEqualTo(SHA_A);
		assertThat(snapshot.getValue().generation()).isOne();
		assertThat(snapshot.getValue().runtimeView()).isSameAs(staged.runtimeView());
		verify(registry).candidateExecutionSnapshot();
		verify(registry).candidateSnapshot();
		verify(registry, never()).activate(anyString(), anyLong());
		verify(registry, never()).stage(any(VerifiedRouteBundleCandidate.class), anyLong());
	}

	@Test
	void commandIdentityMismatchConflictsBeforePlannerInvocation() {
		var staged = staged(SHA_A, 1);
		when(registry.candidateExecutionSnapshot()).thenReturn(staged);

		assertKind(JourneyCandidateCanaryException.Kind.CONFLICT,
			() -> service.execute(command("b".repeat(64), 1)));
		assertKind(JourneyCandidateCanaryException.Kind.CONFLICT,
			() -> service.execute(command(SHA_A, 2)));

		verify(raptorPort, never()).plan(any(), any(), any(), any(), any());
		verify(registry, never()).candidateSnapshot();
	}

	@Test
	void absentOrInvalidStagedRuntimeIsUnavailableBeforePlannerInvocation() {
		when(registry.candidateExecutionSnapshot()).thenThrow(candidateNotStaged());
		assertKind(JourneyCandidateCanaryException.Kind.UNAVAILABLE, () -> service.execute(command(SHA_A, 1)));

		org.mockito.Mockito.reset(registry);
		var invalidRuntime = staged(SHA_A, 1);
		when(((JourneyRaptorRuntimeView) invalidRuntime.runtimeView()).routeBundleSha256())
			.thenReturn("b".repeat(64));
		when(registry.candidateExecutionSnapshot()).thenReturn(invalidRuntime);
		assertKind(JourneyCandidateCanaryException.Kind.UNAVAILABLE, () -> service.execute(command(SHA_A, 1)));

		verify(raptorPort, never()).plan(any(), any(), any(), any(), any());
	}

	@Test
	void plannerThrowNullIdentityMismatchAndNoRouteAreUnavailableWithoutRetry() {
		var staged = staged(SHA_A, 1);
		when(registry.candidateExecutionSnapshot()).thenReturn(staged);

		when(raptorPort.plan(any(), any(), any(), any(), any()))
			.thenThrow(new IllegalStateException("synthetic"))
			.thenReturn(
				null,
				new JourneyRaptorPort.PlanResult("other-query", List.of(mock(JourneyCandidate.class)), OBSERVED_SCAN,
					JourneyRaptorPort.RouteBoundaryReceipt.observed(0), JourneyRaptorPort.RouteMeasurementReceipt.unobservable(), new com.easysubway.journey.application.JourneyAlternatives.StairFreeAlternative(com.easysubway.journey.application.JourneyAlternatives.StairFreeStatus.NOT_FOUND, com.easysubway.journey.application.JourneyAlternatives.FacilityStatus.UNOBSERVED)),
				new JourneyRaptorPort.PlanResult(JourneyCandidateCanaryCommandParserTest.REQUEST_ID, List.of(), OBSERVED_SCAN,
					JourneyRaptorPort.RouteBoundaryReceipt.observed(0)));
		assertKind(JourneyCandidateCanaryException.Kind.UNAVAILABLE, () -> service.execute(command(SHA_A, 1)));

		assertKind(JourneyCandidateCanaryException.Kind.UNAVAILABLE, () -> service.execute(command(SHA_A, 1)));

		assertKind(JourneyCandidateCanaryException.Kind.UNAVAILABLE, () -> service.execute(command(SHA_A, 1)));

		assertKind(JourneyCandidateCanaryException.Kind.UNAVAILABLE, () -> service.execute(command(SHA_A, 1)));

		verify(raptorPort, times(4)).plan(any(), any(), any(), any(), any());
		verify(registry, never()).candidateSnapshot();
	}

	@Test
	void candidateChangeAfterPlanningIsAConflictAndNeverReturnsSuccess() {
		var staged = staged(SHA_A, 1);
		when(registry.candidateExecutionSnapshot()).thenReturn(staged);
		when(raptorPort.plan(any(), any(), any(), any(), any())).thenReturn(new JourneyRaptorPort.PlanResult(
			JourneyCandidateCanaryCommandParserTest.REQUEST_ID, List.of(mock(JourneyCandidate.class)), OBSERVED_SCAN,
			JourneyRaptorPort.RouteBoundaryReceipt.observed(0), JourneyRaptorPort.RouteMeasurementReceipt.unobservable(), new com.easysubway.journey.application.JourneyAlternatives.StairFreeAlternative(com.easysubway.journey.application.JourneyAlternatives.StairFreeStatus.NOT_FOUND, com.easysubway.journey.application.JourneyAlternatives.FacilityStatus.UNOBSERVED)));
		when(registry.candidateSnapshot()).thenReturn(new RouteBundleActivationRegistry.CandidateSnapshot(
			2, identity("b"), evidence("b".repeat(64)), CAPTURED_AT, CAPTURED_AT));

		assertKind(JourneyCandidateCanaryException.Kind.CONFLICT, () -> service.execute(command(SHA_A, 1)));

		verify(raptorPort).plan(any(), any(), any(), any(), any());
		verify(registry, never()).activate(anyString(), anyLong());
	}

	@Test
	void candidateExpiryAfterPlanningIsUnavailableRatherThanAStateConflict() {
		var staged = staged(SHA_A, 1);
		var stale = mock(RouteBundleActivationException.class);
		when(stale.reason()).thenReturn(RouteBundleActivationException.Reason.BUNDLE_STALE);
		when(registry.candidateExecutionSnapshot()).thenReturn(staged);
		when(raptorPort.plan(any(), any(), any(), any(), any())).thenReturn(new JourneyRaptorPort.PlanResult(
			JourneyCandidateCanaryCommandParserTest.REQUEST_ID, List.of(mock(JourneyCandidate.class)), OBSERVED_SCAN,
			JourneyRaptorPort.RouteBoundaryReceipt.observed(0), JourneyRaptorPort.RouteMeasurementReceipt.unobservable(), new com.easysubway.journey.application.JourneyAlternatives.StairFreeAlternative(com.easysubway.journey.application.JourneyAlternatives.StairFreeStatus.NOT_FOUND, com.easysubway.journey.application.JourneyAlternatives.FacilityStatus.UNOBSERVED)));
		when(registry.candidateSnapshot()).thenThrow(stale);

		assertKind(JourneyCandidateCanaryException.Kind.UNAVAILABLE, () -> service.execute(command(SHA_A, 1)));

		verify(raptorPort).plan(any(), any(), any(), any(), any());
		verify(registry, never()).activate(anyString(), anyLong());
	}

	@Test
	void nightAndDaytimeRunsOfTheSameBundleAndProbeGiveTheSameVerdictAtTheSameDeparture() {
		var staged = staged(SHA_A, 1);
		when(registry.candidateExecutionSnapshot()).thenReturn(staged);
		when(registry.candidateSnapshot()).thenReturn(candidateProjection(staged));
		var departures = new java.util.ArrayList<Instant>();
		// 실제 planner처럼 자기 운행일의 05:00 이후 24:00 이전 열차만 있다. 심야 wall-clock을 넘기면 후보가 사라진다.
		when(raptorPort.plan(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
			Instant effective = invocation.getArgument(2);
			departures.add(effective);
			int seconds = com.easysubway.journey.application.ServiceDayResolver.resolve(effective)
				.secondsFromServiceDayStart();
			var candidates = seconds >= 5 * 3600 && seconds < 24 * 3600
				? List.of(mock(JourneyCandidate.class)) : List.<JourneyCandidate>of();
			return candidates.isEmpty()
				? new JourneyRaptorPort.PlanResult(JourneyCandidateCanaryCommandParserTest.REQUEST_ID, candidates,
					OBSERVED_SCAN, JourneyRaptorPort.RouteBoundaryReceipt.observed(0))
				: new JourneyRaptorPort.PlanResult(JourneyCandidateCanaryCommandParserTest.REQUEST_ID, candidates,
					OBSERVED_SCAN, JourneyRaptorPort.RouteBoundaryReceipt.observed(0),
					JourneyRaptorPort.RouteMeasurementReceipt.unobservable(),
					new com.easysubway.journey.application.JourneyAlternatives.StairFreeAlternative(
						com.easysubway.journey.application.JourneyAlternatives.StairFreeStatus.NOT_FOUND,
						com.easysubway.journey.application.JourneyAlternatives.FacilityStatus.UNOBSERVED));
		});

		// 같은 운행일(2026-08-13)의 주간 14:00, 심야 23:50, 익일 00:06 KST. 번들 유효 구간 안이다.
		var wallClocks = List.of(
			Instant.parse("2026-08-13T05:00:00Z"),
			Instant.parse("2026-08-13T14:50:00Z"),
			Instant.parse("2026-08-13T15:06:00Z"));
		for (Instant wallClock : wallClocks) {
			var result = serviceAt(wallClock).execute(command(SHA_A, 1));
			assertThat(result.passed()).as("wall clock %s", wallClock).isTrue();
			assertThat(result.capturedAt()).isEqualTo(wallClock);
		}

		assertThat(departures).hasSize(wallClocks.size()).containsOnly(REPRESENTATIVE_AT);
	}

	@Test
	void representativeDepartureFollowsTheServiceDayOfTheRunAndNeverTheWallClockTimeOfDay() {
		var staged = staged(SHA_A, 1);
		when(registry.candidateExecutionSnapshot()).thenReturn(staged);
		when(registry.candidateSnapshot()).thenReturn(candidateProjection(staged));
		var departures = new java.util.ArrayList<Instant>();
		when(raptorPort.plan(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
			departures.add(invocation.getArgument(2));
			return planned();
		});

		// 2026-08-14 02:59 KST는 아직 08-13 운행일, 03:00 KST부터 08-14 운행일이다.
		serviceAt(Instant.parse("2026-08-13T17:59:00Z")).execute(command(SHA_A, 1));
		serviceAt(Instant.parse("2026-08-13T18:00:00Z")).execute(command(SHA_A, 1));

		assertThat(departures).containsExactly(
			Instant.parse("2026-08-13T01:00:00Z"),
			Instant.parse("2026-08-14T01:00:00Z"));
	}

	@Test
	void snapshotFailuresReportSnapshotErrorWithTheFailingProbe() {
		when(registry.candidateExecutionSnapshot()).thenThrow(candidateNotStaged());
		assertUnavailable(JourneyCandidateCanaryException.FailureReason.SNAPSHOT_ERROR, service);

		org.mockito.Mockito.reset(registry);
		var invalidRuntime = staged(SHA_A, 1);
		when(((JourneyRaptorRuntimeView) invalidRuntime.runtimeView()).routeBundleSha256())
			.thenReturn("b".repeat(64));
		when(registry.candidateExecutionSnapshot()).thenReturn(invalidRuntime);
		assertUnavailable(JourneyCandidateCanaryException.FailureReason.SNAPSHOT_ERROR, service);

		verify(raptorPort, never()).plan(any(), any(), any(), any(), any());
	}

	@Test
	void candidateOutsideItsValidityWindowReportsWindowMismatchBeforePlanning() {
		var staged = staged(SHA_A, 1);
		when(registry.candidateExecutionSnapshot()).thenReturn(staged);

		// freshUntil(2026-08-14 12:00 KST) 이후, activeFrom(2026-08-13 11:00 KST) 이전, verifiedAt 이전.
		assertUnavailable(JourneyCandidateCanaryException.FailureReason.WINDOW_MISMATCH,
			serviceAt(Instant.parse("2026-08-14T04:00:00Z")));
		assertUnavailable(JourneyCandidateCanaryException.FailureReason.WINDOW_MISMATCH,
			serviceAt(Instant.parse("2026-08-13T01:30:00Z")));
		assertUnavailable(JourneyCandidateCanaryException.FailureReason.WINDOW_MISMATCH,
			serviceAt(CAPTURED_AT.minusSeconds(10)));

		verify(raptorPort, never()).plan(any(), any(), any(), any(), any());
	}

	@Test
	void planFailuresReportPlanErrorAndEmptyCandidatesReportNoCandidates() {
		var staged = staged(SHA_A, 1);
		when(registry.candidateExecutionSnapshot()).thenReturn(staged);
		when(raptorPort.plan(any(), any(), any(), any(), any()))
			.thenThrow(new IllegalStateException("synthetic"))
			.thenReturn(
				null,
				new JourneyRaptorPort.PlanResult("other-query", List.of(mock(JourneyCandidate.class)), OBSERVED_SCAN,
					JourneyRaptorPort.RouteBoundaryReceipt.observed(0), JourneyRaptorPort.RouteMeasurementReceipt.unobservable(),
					new com.easysubway.journey.application.JourneyAlternatives.StairFreeAlternative(
						com.easysubway.journey.application.JourneyAlternatives.StairFreeStatus.NOT_FOUND,
						com.easysubway.journey.application.JourneyAlternatives.FacilityStatus.UNOBSERVED)),
				new JourneyRaptorPort.PlanResult(JourneyCandidateCanaryCommandParserTest.REQUEST_ID, List.of(),
					OBSERVED_SCAN, JourneyRaptorPort.RouteBoundaryReceipt.observed(0)));

		assertUnavailable(JourneyCandidateCanaryException.FailureReason.PLAN_ERROR, service);
		assertUnavailable(JourneyCandidateCanaryException.FailureReason.PLAN_ERROR, service);
		assertUnavailable(JourneyCandidateCanaryException.FailureReason.PLAN_ERROR, service);
		assertUnavailable(JourneyCandidateCanaryException.FailureReason.NO_CANDIDATES, service);
	}

	@Test
	void candidateStateLostAfterPlanningKeepsItsOwnReason() {
		var staged = staged(SHA_A, 1);
		when(registry.candidateExecutionSnapshot()).thenReturn(staged);
		when(raptorPort.plan(any(), any(), any(), any(), any())).thenReturn(planned());
		var stale = mock(RouteBundleActivationException.class);
		when(stale.reason()).thenReturn(RouteBundleActivationException.Reason.BUNDLE_STALE);
		var absent = mock(RouteBundleActivationException.class);
		when(absent.reason()).thenReturn(RouteBundleActivationException.Reason.BUNDLE_UNAVAILABLE);
		when(registry.candidateSnapshot()).thenThrow(stale).thenThrow(absent);

		assertUnavailable(JourneyCandidateCanaryException.FailureReason.WINDOW_MISMATCH, service);
		assertUnavailable(JourneyCandidateCanaryException.FailureReason.SNAPSHOT_ERROR, service);
	}

	@Test
	void everyUnavailableFailureIsLoggedWithItsReasonAndProbe() {
		var events = new java.util.concurrent.CopyOnWriteArrayList<org.apache.logging.log4j.core.LogEvent>();
		var appender = new org.apache.logging.log4j.core.appender.AbstractAppender(
			"journey-canary-test-appender", null, null, false, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
			@Override
			public void append(org.apache.logging.log4j.core.LogEvent event) {
				events.add(event.toImmutable());
			}
		};
		appender.start();
		var logger = (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager
			.getLogger(JourneyCandidateCanaryService.class);
		logger.addAppender(appender);
		// addAppender가 이 로거 전용 설정을 새로 만들 수 있으므로 appender를 붙인 뒤에 수준을 낮춘다.
		logger.setLevel(org.apache.logging.log4j.Level.ALL);
		try {
			var staged = staged(SHA_A, 1);
			when(registry.candidateExecutionSnapshot()).thenReturn(staged);
			when(raptorPort.plan(any(), any(), any(), any(), any())).thenReturn(
				new JourneyRaptorPort.PlanResult(JourneyCandidateCanaryCommandParserTest.REQUEST_ID, List.of(),
					OBSERVED_SCAN, JourneyRaptorPort.RouteBoundaryReceipt.observed(0)));

			assertUnavailable(JourneyCandidateCanaryException.FailureReason.NO_CANDIDATES, service);

			assertThat(events).hasSize(1);
			var event = events.get(0);
			assertThat(event.getLevel()).isEqualTo(org.apache.logging.log4j.Level.WARN);
			assertThat(event.getMessage().getFormattedMessage())
				.contains("reason=NO_CANDIDATES")
				.contains("probeId=" + JourneyCandidateCanaryCommandParserTest.REQUEST_ID)
				.contains("kind=UNAVAILABLE");
		} finally {
			logger.removeAppender(appender);
			logger.setLevel(null);
			appender.stop();
		}
	}

	private JourneyCandidateCanaryService serviceAt(Instant wallClock) {
		return new JourneyCandidateCanaryService(registry, raptorPort, Clock.fixed(wallClock, ZoneOffset.UTC));
	}

	private static JourneyRaptorPort.PlanResult planned() {
		return new JourneyRaptorPort.PlanResult(
			JourneyCandidateCanaryCommandParserTest.REQUEST_ID, List.of(mock(JourneyCandidate.class)), OBSERVED_SCAN,
			JourneyRaptorPort.RouteBoundaryReceipt.observed(0), JourneyRaptorPort.RouteMeasurementReceipt.unobservable(),
			new com.easysubway.journey.application.JourneyAlternatives.StairFreeAlternative(
				com.easysubway.journey.application.JourneyAlternatives.StairFreeStatus.NOT_FOUND,
				com.easysubway.journey.application.JourneyAlternatives.FacilityStatus.UNOBSERVED));
	}

	private static void assertUnavailable(
		JourneyCandidateCanaryException.FailureReason reason, JourneyCandidateCanaryService target) {
		assertThatThrownBy(() -> target.execute(command(SHA_A, 1)))
			.isInstanceOfSatisfying(JourneyCandidateCanaryException.class, exception -> {
				assertThat(exception.kind()).isEqualTo(JourneyCandidateCanaryException.Kind.UNAVAILABLE);
				assertThat(exception.failureReason()).isEqualTo(reason);
				assertThat(exception.probeId()).isEqualTo(JourneyCandidateCanaryCommandParserTest.REQUEST_ID);
			});
	}

	private static RouteBundleActivationRegistry.CandidateExecutionSnapshot staged(String manifest, long generation) {
		var runtime = mock(TestRuntimeView.class);
		when(runtime.routeBundleSha256()).thenReturn(manifest);
		when(runtime.generation()).thenReturn(generation);
		return new RouteBundleActivationRegistry.CandidateExecutionSnapshot(
			generation, identity("a"), evidence(manifest), runtime,
			CAPTURED_AT.minusSeconds(2), CAPTURED_AT.minusSeconds(1));
	}

	private static RouteBundleActivationRegistry.CandidateSnapshot candidateProjection(
		RouteBundleActivationRegistry.CandidateExecutionSnapshot staged) {
		return new RouteBundleActivationRegistry.CandidateSnapshot(
			staged.generation(), staged.identity(), staged.admissionEvidence(), staged.verifiedAt(), staged.stagedAt());
	}

	private static RouteBundleIdentity identity(String marker) {
		return new RouteBundleIdentity(
			1, "server-route-bundle", "bundle-" + marker, 31,
			"0".repeat(64), "1".repeat(64), "2".repeat(64), "3".repeat(64),
			"4".repeat(64), "5".repeat(64), "6".repeat(64), "7".repeat(64),
			"Asia/Seoul", "2026-08-13T11:00:00.000+09:00", "2026-08-14T12:00:00.000+09:00",
			new RouteBundleIdentity.SchemaCompatibility(3, 3), "route-bundle-key",
			new RouteBundleIdentity.Signature("rsa-sha256-server-route-bundle-v1", "AQID"));
	}

	private static RouteBundleAdmissionEvidence evidence(String manifest) {
		return new RouteBundleAdmissionEvidence(
			manifest, "final", "promotion", "receipt", "activation-request-228");
	}

	private static JourneyCandidateCanaryCommandParser.Command command(String manifest, long generation) {
		return new JourneyCandidateCanaryCommandParser.Command(
			1, "journey-v3-candidate-canary-command", "canary-request-236", manifest, generation,
			JourneyCandidateCanaryCommandParserTest.REQUEST_ID, "station-origin", "station-destination",
			JourneyRequest.MobilityProfile.STEP_FREE, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE, 2, 1);
	}

	private static RouteBundleActivationException candidateNotStaged() {
		try {
			new RouteBundleActivationRegistry(Clock.fixed(CAPTURED_AT, ZoneOffset.UTC)).candidateExecutionSnapshot();
			throw new AssertionError("expected candidate absence");
		} catch (RouteBundleActivationException exception) {
			return exception;
		}
	}

	private static void assertKind(JourneyCandidateCanaryException.Kind kind, Runnable action) {
		assertThatThrownBy(action::run)
			.isInstanceOf(JourneyCandidateCanaryException.class)
			.extracting("kind")
			.isEqualTo(kind);
	}

	private interface TestRuntimeView extends JourneyRaptorRuntimeView, RouteBundleRuntimeView {
	}
}
