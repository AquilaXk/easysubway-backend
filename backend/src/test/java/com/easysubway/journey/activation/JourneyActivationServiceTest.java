package com.easysubway.journey.activation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.easysubway.journey.bundle.ActiveRouteBundleSnapshot;
import com.easysubway.journey.bundle.RouteBundleActivationException;
import com.easysubway.journey.bundle.RouteBundleActivationRegistry;
import com.easysubway.journey.bundle.RouteBundleAdmissionEvidence;
import com.easysubway.journey.bundle.RouteBundleIdentity;
import com.easysubway.journey.readiness.JourneyReadinessProperties;
import com.easysubway.journey.readiness.JourneyReadinessService;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class JourneyActivationServiceTest {

	private static final String SHA_A = "a".repeat(64);
	private final RouteBundleActivationRegistry registry = mock(RouteBundleActivationRegistry.class);
	private final JourneyReadinessProperties properties = mock(JourneyReadinessProperties.class);
	private final JourneyReadinessService readinessService = mock(JourneyReadinessService.class);
	private final JourneyActivationService service = new JourneyActivationService(registry, properties, readinessService);

	@Test
	void validatesTheCandidateTupleThenActivatesExactlyOnceAndReturnsActiveReadiness() {
		var candidate = candidate();
		var activeSnapshot = mock(ActiveRouteBundleSnapshot.class);
		var activeReadiness = mock(JourneyReadinessService.ActiveReadiness.class);
		when(registry.candidateSnapshot()).thenReturn(candidate);
		when(properties.trafficGeneration()).thenReturn(31L);
		when(registry.activate(SHA_A, 0)).thenReturn(activeSnapshot);
		when(readinessService.active(activeSnapshot)).thenReturn(activeReadiness);

		assertThat(service.activate(command())).isSameAs(activeReadiness);

		verify(registry).candidateSnapshot();
		verify(registry).activate(SHA_A, 0);
		verify(readinessService).active(activeSnapshot);
	}

	@Test
	void everyTupleOrTrafficMismatchIsAConflictBeforeActivation() {
		when(registry.candidateSnapshot()).thenReturn(candidate());
		when(properties.trafficGeneration()).thenReturn(31L);

		assertKind(JourneyActivationException.Kind.CONFLICT, () -> service.activate(new JourneyActivationCommandParser.Command(
			1, "journey-v3-activation-command", "other", SHA_A, 1, 0, 31)));
		assertKind(JourneyActivationException.Kind.CONFLICT, () -> service.activate(new JourneyActivationCommandParser.Command(
			1, "journey-v3-activation-command", "activation-request-228", "b".repeat(64), 1, 0, 31)));
		assertKind(JourneyActivationException.Kind.CONFLICT, () -> service.activate(new JourneyActivationCommandParser.Command(
			1, "journey-v3-activation-command", "activation-request-228", SHA_A, 2, 1, 31)));
		assertKind(JourneyActivationException.Kind.CONFLICT, () -> service.activate(new JourneyActivationCommandParser.Command(
			1, "journey-v3-activation-command", "activation-request-228", SHA_A, 1, 0, 32)));

		verify(registry, never()).activate(SHA_A, 0);
		verify(readinessService, never()).active(org.mockito.ArgumentMatchers.any(ActiveRouteBundleSnapshot.class));
	}

	@Test
	void missingCandidateIsUnavailableWithoutDiagnosticActivation() {
		when(registry.candidateSnapshot()).thenThrow(candidateNotStaged());
		when(registry.activeSnapshot()).thenThrow(bundleUnavailable());

		assertKind(JourneyActivationException.Kind.UNAVAILABLE, () -> service.activate(command()));

		verify(registry, never()).activate(SHA_A, 0);
		verify(readinessService, never()).active(org.mockito.ArgumentMatchers.any(ActiveRouteBundleSnapshot.class));
	}

	@Test
	void sameManifestAlreadyActiveIsAConflictWithoutDiagnosticActivation() {
		when(registry.candidateSnapshot()).thenThrow(candidateNotStaged());
		var active = mock(ActiveRouteBundleSnapshot.class);
		when(active.admissionEvidence()).thenReturn(evidence());
		when(registry.activeSnapshot()).thenReturn(active);

		assertKind(JourneyActivationException.Kind.CONFLICT, () -> service.activate(command()));

		verify(registry, never()).activate(SHA_A, 0);
		verify(readinessService, never()).active(org.mockito.ArgumentMatchers.any(ActiveRouteBundleSnapshot.class));
	}

	private static RouteBundleActivationRegistry.CandidateSnapshot candidate() {
		return new RouteBundleActivationRegistry.CandidateSnapshot(
			1, mock(RouteBundleIdentity.class), evidence(), Clock.systemUTC().instant(), Clock.systemUTC().instant());
	}

	private static RouteBundleAdmissionEvidence evidence() {
		return new RouteBundleAdmissionEvidence(SHA_A, "final", "promotion", "receipt", "activation-request-228");
	}

	private static JourneyActivationCommandParser.Command command() {
		return new JourneyActivationCommandParser.Command(
			1, "journey-v3-activation-command", "activation-request-228", SHA_A, 1, 0, 31);
	}

	private static RouteBundleActivationException candidateNotStaged() {
		return registryFailure(() -> new RouteBundleActivationRegistry(Clock.systemUTC()).candidateSnapshot());
	}

	private static RouteBundleActivationException bundleUnavailable() {
		return registryFailure(() -> new RouteBundleActivationRegistry(Clock.systemUTC()).activeSnapshot());
	}

	private static RouteBundleActivationException registryFailure(Runnable action) {
		try {
			action.run();
			throw new AssertionError("expected registry failure");
		} catch (RouteBundleActivationException exception) {
			return exception;
		}
	}

	private static void assertKind(JourneyActivationException.Kind kind, Runnable action) {
		assertThatThrownBy(action::run)
			.isInstanceOf(JourneyActivationException.class)
			.extracting("kind")
			.isEqualTo(kind);
	}
}
