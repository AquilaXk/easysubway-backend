package com.easysubway.journey.activation;

import com.easysubway.journey.bundle.RouteBundleActivationException;
import com.easysubway.journey.bundle.RouteBundleActivationRegistry;
import com.easysubway.journey.readiness.JourneyReadinessProperties;
import com.easysubway.journey.readiness.JourneyReadinessService;
import java.util.Objects;

public final class JourneyActivationService {

	private final RouteBundleActivationRegistry registry;
	private final JourneyReadinessProperties properties;
	private final JourneyReadinessService readinessService;

	public JourneyActivationService(
		RouteBundleActivationRegistry registry,
		JourneyReadinessProperties properties,
		JourneyReadinessService readinessService) {
		this.registry = Objects.requireNonNull(registry, "registry");
		this.properties = Objects.requireNonNull(properties, "properties");
		this.readinessService = Objects.requireNonNull(readinessService, "readinessService");
	}

	public JourneyReadinessService.ActiveReadiness activate(JourneyActivationCommandParser.Command command) {
		Objects.requireNonNull(command, "command");
		var candidate = currentCandidate(command);
		if (!candidate.admissionEvidence().activationRequestIdentity()
			.equals(command.activationRequestIdentity())
			|| !candidate.admissionEvidence().manifestSha256()
				.equals(command.candidateManifestSha256())
			|| candidate.generation() != command.candidateGeneration()
			|| command.candidateGeneration() != nextGeneration(command.expectedActiveGeneration())
			|| properties.trafficGeneration() != command.trafficGeneration()) {
			throw failure(JourneyActivationException.Kind.CONFLICT);
		}
		try {
			var activated = registry.activate(
				command.candidateManifestSha256(), command.expectedActiveGeneration());
			return readinessService.active(activated);
		} catch (RouteBundleActivationException exception) {
			throw mapped(exception);
		}
	}

	private RouteBundleActivationRegistry.CandidateSnapshot currentCandidate(
		JourneyActivationCommandParser.Command command) {
		try {
			return registry.candidateSnapshot();
		} catch (RouteBundleActivationException exception) {
			if (exception.reason() == RouteBundleActivationException.Reason.CANDIDATE_NOT_STAGED) {
				throw classifyAbsentCandidate(command);
			}
			throw mapped(exception);
		}
	}

	private JourneyActivationException classifyAbsentCandidate(
		JourneyActivationCommandParser.Command command) {
		try {
			var active = registry.activeSnapshot();
			if (active.admissionEvidence().manifestSha256().equals(command.candidateManifestSha256())) {
				return failure(JourneyActivationException.Kind.CONFLICT);
			}
		} catch (RouteBundleActivationException ignored) {
			// A missing, stale, or future active snapshot cannot establish already-active identity.
		}
		return failure(JourneyActivationException.Kind.UNAVAILABLE);
	}

	private static long nextGeneration(long expectedActiveGeneration) {
		try {
			return Math.addExact(expectedActiveGeneration, 1);
		} catch (ArithmeticException exception) {
			throw failure(JourneyActivationException.Kind.CONFLICT);
		}
	}

	private static JourneyActivationException mapped(RouteBundleActivationException exception) {
		return switch (exception.reason()) {
			case CANDIDATE_ALREADY_STAGED, CANDIDATE_ALREADY_ACTIVE,
				CANDIDATE_IDENTITY_MISMATCH, ACTIVATION_CONFLICT ->
				failure(JourneyActivationException.Kind.CONFLICT);
			case BUNDLE_UNAVAILABLE, BUNDLE_STALE, BUNDLE_FUTURE, CANDIDATE_NOT_STAGED ->
				failure(JourneyActivationException.Kind.UNAVAILABLE);
		};
	}

	private static JourneyActivationException failure(JourneyActivationException.Kind kind) {
		return new JourneyActivationException(kind);
	}
}
