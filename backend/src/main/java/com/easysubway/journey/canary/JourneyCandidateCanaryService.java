package com.easysubway.journey.canary;

import com.easysubway.journey.application.ActiveJourneySnapshotPort;
import com.easysubway.journey.application.JourneyRaptorPort;
import com.easysubway.journey.application.JourneyRaptorRuntimeView;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.bundle.RouteBundleActivationException;
import com.easysubway.journey.bundle.RouteBundleActivationRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

public final class JourneyCandidateCanaryService {

	private static final int SCHEMA_VERSION = 1;
	private static final String ARTIFACT_KIND = "journey-v3-candidate-canary-result";
	private final RouteBundleActivationRegistry registry;
	private final JourneyRaptorPort raptorPort;
	private final Clock clock;

	public JourneyCandidateCanaryService(
		RouteBundleActivationRegistry registry,
		JourneyRaptorPort raptorPort,
		Clock clock) {
		this.registry = Objects.requireNonNull(registry, "registry");
		this.raptorPort = Objects.requireNonNull(raptorPort, "raptorPort");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public Result execute(JourneyCandidateCanaryCommandParser.Command command) {
		Objects.requireNonNull(command, "command");
		Instant capturedAt = clock.instant();
		var candidate = currentCandidate();
		if (!candidate.admissionEvidence().manifestSha256().equals(command.candidateManifestSha256())
			|| candidate.generation() != command.candidateGeneration()) {
			throw failure(JourneyCandidateCanaryException.Kind.CONFLICT);
		}
		if (candidate.verifiedAt().isAfter(capturedAt)
			|| capturedAt.isBefore(candidate.identity().activeFromInstant())
			|| !capturedAt.isBefore(candidate.identity().freshUntilInstant())
			|| !(candidate.runtimeView() instanceof JourneyRaptorRuntimeView runtimeView)
			|| !command.candidateManifestSha256().equals(runtimeView.routeBundleSha256())
			|| command.candidateGeneration() != runtimeView.generation()) {
			throw failure(JourneyCandidateCanaryException.Kind.UNAVAILABLE);
		}

		ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot;
		JourneyRequest request;
		try {
			var identity = candidate.identity();
			snapshot = new ActiveJourneySnapshotPort.ActiveJourneySnapshot(
				command.candidateManifestSha256() + ":" + command.candidateGeneration(),
				identity.bundleId(),
				command.candidateManifestSha256(),
				identity.timetableSha256(),
				identity.accessibilitySha256(),
				command.candidateGeneration(),
				runtimeView,
				identity.freshUntilInstant(),
				true,
				ActiveJourneySnapshotPort.ActiveServingEvidence.unobservable(),
				ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.unobservable());
			request = new JourneyRequest(
				command.requestId(),
				command.originStationId(),
				command.destinationStationId(),
				new JourneyRequest.Departure.Now(),
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
				JourneyRequest.WalkingPace.STANDARD,
				command.mobilityProfile(),
				command.constraintMode(),
				command.maxTransfers(),
				command.alternativeCount(),
				() -> false);
		} catch (RuntimeException exception) {
			throw failure(JourneyCandidateCanaryException.Kind.INVALID_REQUEST);
		}

		JourneyRaptorPort.PlanResult plan;
		try {
			plan = raptorPort.plan(request, snapshot, capturedAt, null);
		} catch (RuntimeException exception) {
			throw failure(JourneyCandidateCanaryException.Kind.UNAVAILABLE);
		}
		if (plan == null
			|| !command.requestId().equals(plan.queryId())
			|| plan.candidates().isEmpty()) {
			throw failure(JourneyCandidateCanaryException.Kind.UNAVAILABLE);
		}

		requireStillStaged(command);
		var identity = candidate.identity();
		String evidenceSha256 = evidenceSha256(
			"schemaVersion", SCHEMA_VERSION,
			"artifactKind", ARTIFACT_KIND,
			"canaryRequestIdentity", command.canaryRequestIdentity(),
			"requestId", command.requestId(),
			"candidateManifestSha256", command.candidateManifestSha256(),
			"candidateGeneration", command.candidateGeneration(),
			"bundleId", identity.bundleId(),
			"bundleReleaseSequence", identity.releaseSequence(),
			"queryId", plan.queryId(),
			"capturedAt", capturedAt,
			"passed", true,
			"legacyGraphSuccessCount", 0,
			"localRouteInvocationCount", 0,
			"staleJourneyServedCount", 0,
			"alternateEndpointSuccessCount", 0);
		return new Result(
			SCHEMA_VERSION,
			ARTIFACT_KIND,
			command.canaryRequestIdentity(),
			command.requestId(),
			command.candidateManifestSha256(),
			command.candidateGeneration(),
			identity.bundleId(),
			identity.releaseSequence(),
			plan.queryId(),
			capturedAt,
			true,
			0,
			0,
			0,
			0,
			evidenceSha256);
	}

	private RouteBundleActivationRegistry.CandidateExecutionSnapshot currentCandidate() {
		try {
			return registry.candidateExecutionSnapshot();
		} catch (RouteBundleActivationException exception) {
			throw failure(JourneyCandidateCanaryException.Kind.UNAVAILABLE);
		}
	}

	private void requireStillStaged(JourneyCandidateCanaryCommandParser.Command command) {
		try {
			var current = registry.candidateSnapshot();
			if (current.generation() != command.candidateGeneration()
				|| !current.admissionEvidence().manifestSha256().equals(command.candidateManifestSha256())) {
				throw failure(JourneyCandidateCanaryException.Kind.CONFLICT);
			}
		} catch (RouteBundleActivationException exception) {
			var kind = switch (exception.reason()) {
				case BUNDLE_UNAVAILABLE, BUNDLE_STALE, BUNDLE_FUTURE ->
					JourneyCandidateCanaryException.Kind.UNAVAILABLE;
				case CANDIDATE_ALREADY_STAGED, CANDIDATE_ALREADY_ACTIVE,
					CANDIDATE_NOT_STAGED, CANDIDATE_IDENTITY_MISMATCH, ACTIVATION_CONFLICT ->
					JourneyCandidateCanaryException.Kind.CONFLICT;
			};
			throw failure(kind);
		}
	}

	private static String evidenceSha256(Object... values) {
		var canonical = new StringBuilder();
		for (Object value : values) {
			byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
			canonical.append(bytes.length).append(':').append(new String(bytes, StandardCharsets.UTF_8));
		}
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static JourneyCandidateCanaryException failure(JourneyCandidateCanaryException.Kind kind) {
		return new JourneyCandidateCanaryException(kind);
	}

	public record Result(
		int schemaVersion,
		String artifactKind,
		String canaryRequestIdentity,
		String requestId,
		String candidateManifestSha256,
		long candidateGeneration,
		String bundleId,
		long bundleReleaseSequence,
		String queryId,
		Instant capturedAt,
		boolean passed,
		long legacyGraphSuccessCount,
		long localRouteInvocationCount,
		long staleJourneyServedCount,
		long alternateEndpointSuccessCount,
		String evidenceSha256) {
	}
}
