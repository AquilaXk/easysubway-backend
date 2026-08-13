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
	private static final String SHA_A = JourneyCandidateCanaryCommandParserTest.SHA_A;
	private final RouteBundleActivationRegistry registry = mock(RouteBundleActivationRegistry.class);
	private final JourneyRaptorPort raptorPort = mock(JourneyRaptorPort.class);
	private final JourneyCandidateCanaryService service = new JourneyCandidateCanaryService(
		registry, raptorPort, Clock.fixed(CAPTURED_AT, ZoneOffset.UTC));

	@Test
	void plansTheExactStagedRuntimeOnceAndReturnsClosedCanonicalEvidence() {
		var staged = staged(SHA_A, 1);
		when(registry.candidateExecutionSnapshot()).thenReturn(staged);
		when(registry.candidateSnapshot()).thenReturn(candidateProjection(staged));
		when(raptorPort.plan(any(), any(), any(), org.mockito.ArgumentMatchers.isNull()))
			.thenReturn(new JourneyRaptorPort.PlanResult(
				JourneyCandidateCanaryCommandParserTest.REQUEST_ID, List.of(mock(JourneyCandidate.class))));

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
		verify(raptorPort).plan(request.capture(), snapshot.capture(), org.mockito.ArgumentMatchers.eq(CAPTURED_AT),
			org.mockito.ArgumentMatchers.isNull());
		assertThat(request.getValue().departure()).isInstanceOf(JourneyRequest.Departure.Now.class);
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

		verify(raptorPort, never()).plan(any(), any(), any(), any());
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

		verify(raptorPort, never()).plan(any(), any(), any(), any());
	}

	@Test
	void plannerThrowNullIdentityMismatchAndNoRouteAreUnavailableWithoutRetry() {
		var staged = staged(SHA_A, 1);
		when(registry.candidateExecutionSnapshot()).thenReturn(staged);

		when(raptorPort.plan(any(), any(), any(), any()))
			.thenThrow(new IllegalStateException("synthetic"))
			.thenReturn(
				null,
				new JourneyRaptorPort.PlanResult("other-query", List.of(mock(JourneyCandidate.class))),
				new JourneyRaptorPort.PlanResult(JourneyCandidateCanaryCommandParserTest.REQUEST_ID, List.of()));
		assertKind(JourneyCandidateCanaryException.Kind.UNAVAILABLE, () -> service.execute(command(SHA_A, 1)));

		assertKind(JourneyCandidateCanaryException.Kind.UNAVAILABLE, () -> service.execute(command(SHA_A, 1)));

		assertKind(JourneyCandidateCanaryException.Kind.UNAVAILABLE, () -> service.execute(command(SHA_A, 1)));

		assertKind(JourneyCandidateCanaryException.Kind.UNAVAILABLE, () -> service.execute(command(SHA_A, 1)));

		verify(raptorPort, times(4)).plan(any(), any(), any(), any());
		verify(registry, never()).candidateSnapshot();
	}

	@Test
	void candidateChangeAfterPlanningIsAConflictAndNeverReturnsSuccess() {
		var staged = staged(SHA_A, 1);
		when(registry.candidateExecutionSnapshot()).thenReturn(staged);
		when(raptorPort.plan(any(), any(), any(), any())).thenReturn(new JourneyRaptorPort.PlanResult(
			JourneyCandidateCanaryCommandParserTest.REQUEST_ID, List.of(mock(JourneyCandidate.class))));
		when(registry.candidateSnapshot()).thenReturn(new RouteBundleActivationRegistry.CandidateSnapshot(
			2, identity("b"), evidence("b".repeat(64)), CAPTURED_AT, CAPTURED_AT));

		assertKind(JourneyCandidateCanaryException.Kind.CONFLICT, () -> service.execute(command(SHA_A, 1)));

		verify(raptorPort).plan(any(), any(), any(), any());
		verify(registry, never()).activate(anyString(), anyLong());
	}

	@Test
	void candidateExpiryAfterPlanningIsUnavailableRatherThanAStateConflict() {
		var staged = staged(SHA_A, 1);
		var stale = mock(RouteBundleActivationException.class);
		when(stale.reason()).thenReturn(RouteBundleActivationException.Reason.BUNDLE_STALE);
		when(registry.candidateExecutionSnapshot()).thenReturn(staged);
		when(raptorPort.plan(any(), any(), any(), any())).thenReturn(new JourneyRaptorPort.PlanResult(
			JourneyCandidateCanaryCommandParserTest.REQUEST_ID, List.of(mock(JourneyCandidate.class))));
		when(registry.candidateSnapshot()).thenThrow(stale);

		assertKind(JourneyCandidateCanaryException.Kind.UNAVAILABLE, () -> service.execute(command(SHA_A, 1)));

		verify(raptorPort).plan(any(), any(), any(), any());
		verify(registry, never()).activate(anyString(), anyLong());
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
