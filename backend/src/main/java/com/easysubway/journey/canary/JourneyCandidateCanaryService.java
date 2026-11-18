package com.easysubway.journey.canary;

import com.easysubway.journey.application.ActiveJourneySnapshotPort;
import com.easysubway.journey.application.JourneyRaptorPort;
import com.easysubway.journey.application.JourneyRaptorRuntimeView;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.JourneyRequestMeasurement;
import com.easysubway.journey.application.ServiceDayResolver;
import com.easysubway.journey.bundle.RouteBundleActivationException;
import com.easysubway.journey.bundle.RouteBundleActivationRegistry;
import com.easysubway.journey.bundle.RouteBundleIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.HexFormat;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Journey 후보 활성화 canary.
 *
 * <p>출발 시각은 실행 시각(wall-clock)이 아니라 번들 기준이다. 번들 유효 구간 [activeFrom, freshUntil) 안에서 처음 오는
 * {@link #REPRESENTATIVE_DEPARTURE_LOCAL_TIME}(KST)이다. 그래서 같은 번들·probe는 막차 이후 심야에 실행해도 주간과 같은
 * 판정을 받고, 항상 번들이 서비스하는 시각을 조회한다. 유효 구간 안에 그 시각이 없으면 {@code WINDOW_MISMATCH}다.
 * 번들 유효 구간 자체는 여전히 실제 실행 시각으로 검증한다.</p>
 *
 * <p>단일 대표 시각 smoke다. 다른 시간대(출퇴근·심야 등)의 시간표 결함은 이 canary로 잡지 못한다.</p>
 */
public final class JourneyCandidateCanaryService {

	/** 모든 노선이 운행 중인 주간 대표 시각. {@code Asia/Seoul} 로컬 시각이며 번들 유효 구간에서 처음 오는 것을 쓴다. */
	public static final LocalTime REPRESENTATIVE_DEPARTURE_LOCAL_TIME = LocalTime.of(10, 0);
	private static final Logger LOG = LoggerFactory.getLogger(JourneyCandidateCanaryService.class);
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
		var candidate = currentCandidate(command);
		if (!candidate.admissionEvidence().manifestSha256().equals(command.candidateManifestSha256())
			|| candidate.generation() != command.candidateGeneration()) {
			throw failure(JourneyCandidateCanaryException.Kind.CONFLICT);
		}
		if (candidate.verifiedAt().isAfter(capturedAt)
			|| capturedAt.isBefore(candidate.identity().activeFromInstant())
			|| !capturedAt.isBefore(candidate.identity().freshUntilInstant())) {
			throw unavailable(command, JourneyCandidateCanaryException.FailureReason.WINDOW_MISMATCH, null);
		}
		if (!(candidate.runtimeView() instanceof JourneyRaptorRuntimeView runtimeView)
			|| !command.candidateManifestSha256().equals(runtimeView.routeBundleSha256())
			|| command.candidateGeneration() != runtimeView.generation()) {
			throw unavailable(command, JourneyCandidateCanaryException.FailureReason.SNAPSHOT_ERROR, null);
		}
		Instant departureAt = representativeDeparture(candidate.identity(), command);

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
				new JourneyRequest.Departure.Scheduled(departureAt),
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
			plan = raptorPort.plan(request, snapshot, departureAt, null,
				new JourneyRequestMeasurement(request.requestId()));
		} catch (RuntimeException exception) {
			throw unavailable(command, JourneyCandidateCanaryException.FailureReason.PLAN_ERROR, exception);
		}
		if (plan == null || !command.requestId().equals(plan.queryId())) {
			throw unavailable(command, JourneyCandidateCanaryException.FailureReason.PLAN_ERROR, null);
		}
		if (plan.candidates().isEmpty()) {
			throw unavailable(command, JourneyCandidateCanaryException.FailureReason.NO_CANDIDATES, null);
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

	/** 번들 유효 구간 [activeFrom, freshUntil) 안에서 처음 오는 대표 시각. 실행 시각에 의존하지 않는다. */
	private static Instant representativeDeparture(
		RouteBundleIdentity identity, JourneyCandidateCanaryCommandParser.Command command) {
		Instant activeFrom = identity.activeFromInstant();
		Instant departure = activeFrom.atZone(ServiceDayResolver.ZONE).toLocalDate()
			.atTime(REPRESENTATIVE_DEPARTURE_LOCAL_TIME)
			.atZone(ServiceDayResolver.ZONE)
			.toInstant();
		if (departure.isBefore(activeFrom)) {
			departure = departure.atZone(ServiceDayResolver.ZONE).plusDays(1).toInstant();
		}
		if (!departure.isBefore(identity.freshUntilInstant())) {
			throw unavailable(command, JourneyCandidateCanaryException.FailureReason.WINDOW_MISMATCH, null);
		}
		return departure;
	}

	private RouteBundleActivationRegistry.CandidateExecutionSnapshot currentCandidate(
		JourneyCandidateCanaryCommandParser.Command command) {
		try {
			return registry.candidateExecutionSnapshot();
		} catch (RouteBundleActivationException exception) {
			throw unavailable(command, lookupFailureReason(exception), exception);
		}
	}

	/** 번들 만료·미래는 유효 구간 불일치, 그 밖의 조회 실패는 snapshot 오류다. requireStillStaged와 같은 기준이다. */
	private static JourneyCandidateCanaryException.FailureReason lookupFailureReason(
		RouteBundleActivationException exception) {
		return switch (exception.reason()) {
			case BUNDLE_STALE, BUNDLE_FUTURE -> JourneyCandidateCanaryException.FailureReason.WINDOW_MISMATCH;
			default -> JourneyCandidateCanaryException.FailureReason.SNAPSHOT_ERROR;
		};
	}

	private void requireStillStaged(JourneyCandidateCanaryCommandParser.Command command) {
		try {
			var current = registry.candidateSnapshot();
			if (current.generation() != command.candidateGeneration()
				|| !current.admissionEvidence().manifestSha256().equals(command.candidateManifestSha256())) {
				throw failure(JourneyCandidateCanaryException.Kind.CONFLICT);
			}
		} catch (RouteBundleActivationException exception) {
			throw switch (exception.reason()) {
				case BUNDLE_UNAVAILABLE, BUNDLE_STALE, BUNDLE_FUTURE ->
					unavailable(command, lookupFailureReason(exception), exception);
				case CANDIDATE_ALREADY_STAGED, CANDIDATE_ALREADY_ACTIVE,
					CANDIDATE_NOT_STAGED, CANDIDATE_IDENTITY_MISMATCH, ACTIVATION_CONFLICT ->
					failure(JourneyCandidateCanaryException.Kind.CONFLICT);
			};
		}
	}

	/** 구분된 UNAVAILABLE 실패. 사유와 실패한 probe id를 로그에 남기고 응답으로 전달한다. 원인 예외는 클래스 이름만 남긴다. */
	private static JourneyCandidateCanaryException unavailable(
		JourneyCandidateCanaryCommandParser.Command command,
		JourneyCandidateCanaryException.FailureReason reason,
		RuntimeException cause) {
		var kind = JourneyCandidateCanaryException.Kind.UNAVAILABLE;
		LOG.warn("Journey candidate canary failed kind={} reason={} probeId={} candidateGeneration={} causeClass={}",
			kind, reason, command.requestId(), command.candidateGeneration(),
			cause == null ? "none" : cause.getClass().getSimpleName());
		return new JourneyCandidateCanaryException(kind, reason, command.requestId());
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
