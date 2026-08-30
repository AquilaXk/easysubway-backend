package com.easysubway.journey.adapter.in.web;

import com.easysubway.journey.application.JourneyApplicationDeadlineExecutor;
import com.easysubway.journey.application.JourneyApplicationDeadlineExecutor.MeasuredCompleted;
import com.easysubway.journey.application.JourneyApplicationDeadlineExecutor.MeasuredOutcome;
import com.easysubway.journey.application.JourneyApplicationDeadlineExecutor.TimedOut;
import com.easysubway.journey.application.JourneyExecutionResult;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.readiness.JourneyReadinessService;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Readiness-protected, request-bound measurement surface for the deployed Journey V3 path. */
@RestController
@Profile("(prod | staging | release | prod-like) & !capacity-evidence")
@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
public final class JourneyBenchmarkObservationController {

	public static final String PATH = "/internal/v1/journey/benchmark-observation";
	private static final int MAX_REQUEST_BYTES = 4_096;
	private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	private static final BooleanSupplier NOT_CANCELLED = () -> false;
	private static final Set<String> REQUEST_FIELDS = Set.of(
		"requestId", "originStationId", "destinationStationId", "departure", "timePolicy",
		"walkingPace", "mobilityProfile", "constraintMode", "maxTransfers", "alternativeCount");

	private final JourneyApplicationDeadlineExecutor deadlineExecutor;
	private final JourneyReadinessService readinessService;

	public JourneyBenchmarkObservationController(JourneyApplicationDeadlineExecutor deadlineExecutor,
		JourneyReadinessService readinessService) {
		this.deadlineExecutor = Objects.requireNonNull(deadlineExecutor, "deadlineExecutor");
		this.readinessService = Objects.requireNonNull(readinessService, "readinessService");
	}

	@PostMapping(value = PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<?> observe(HttpServletRequest servletRequest) {
		JourneyRequest request;
		try {
			request = decode(read(servletRequest));
		} catch (InvalidRequest exception) {
			return response(HttpStatus.BAD_REQUEST, new Failure("INVALID_REQUEST"));
		}
		try {
			MeasuredOutcome outcome = deadlineExecutor.executeMeasured(request);
			if (!(outcome instanceof MeasuredCompleted completed)) {
				return response(outcome instanceof TimedOut ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.SERVICE_UNAVAILABLE,
					new Failure(outcome instanceof TimedOut ? "TIMEOUT" : "UNAVAILABLE"));
			}
			if (!(completed.result() instanceof JourneyExecutionResult.Success success)) {
				return response(HttpStatus.SERVICE_UNAVAILABLE, new Failure("UNAVAILABLE"));
			}
			var observation = success.executionObservation();
			if (!request.requestId().equals(observation.requestId())) {
				return response(HttpStatus.SERVICE_UNAVAILABLE, new Failure("IDENTITY_MISMATCH"));
			}
			var activeReadiness = readinessService.active();
			if (!matches(activeReadiness, observation)) {
				return response(HttpStatus.SERVICE_UNAVAILABLE, new Failure("IDENTITY_MISMATCH"));
			}
			return response(HttpStatus.OK, ObservationResponse.from(observation, completed, activeReadiness));
		} catch (RuntimeException exception) {
			return response(HttpStatus.SERVICE_UNAVAILABLE, new Failure("UNAVAILABLE"));
		}
	}

	private static boolean matches(JourneyReadinessService.ActiveReadiness readiness,
		JourneyExecutionResult.ExecutionObservation observation) {
		return readiness.servingReady() && !readiness.draining()
			&& readiness.routeBundleManifestSha256().equals(observation.routeBundleSha256())
			&& readiness.generation() == observation.bundleGeneration()
			&& readiness.serviceTimezone().equals(observation.serviceDay().timezone())
			&& readiness.serviceDayCutoff().equals(observation.serviceDay().cutoffLocalTime());
	}

	private static ResponseEntity<?> response(HttpStatus status, Object body) {
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
	}

	private static byte[] read(HttpServletRequest request) {
		try {
			byte[] bytes = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
			if (bytes.length > MAX_REQUEST_BYTES) throw new InvalidRequest();
			return bytes;
		} catch (IOException exception) {
			throw new InvalidRequest();
		}
	}

	private static JourneyRequest decode(byte[] requestBytes) {
		try {
			JsonNode request = JSON.readTree(requestBytes);
			if (!hasExactFields(request, REQUEST_FIELDS)
				|| !request.path("requestId").isTextual()
				|| !request.path("originStationId").isTextual()
				|| !request.path("destinationStationId").isTextual()
				|| !request.path("timePolicy").isTextual()
				|| !request.path("walkingPace").isTextual()
				|| !request.path("mobilityProfile").isTextual()
				|| !request.path("constraintMode").isTextual()
				|| !request.path("maxTransfers").isInt()
				|| !request.path("alternativeCount").isInt()) throw new InvalidRequest();
			return new JourneyRequest(request.path("requestId").textValue(), request.path("originStationId").textValue(),
				request.path("destinationStationId").textValue(), decodeDeparture(request.path("departure")),
				JourneyRequest.TimePolicy.valueOf(request.path("timePolicy").textValue()),
				JourneyRequest.WalkingPace.valueOf(request.path("walkingPace").textValue()),
				JourneyRequest.MobilityProfile.valueOf(request.path("mobilityProfile").textValue()),
				JourneyRequest.ConstraintMode.valueOf(request.path("constraintMode").textValue()),
				request.path("maxTransfers").intValue(), request.path("alternativeCount").intValue(), NOT_CANCELLED);
		} catch (IOException | RuntimeException exception) {
			throw new InvalidRequest();
		}
	}

	private static JourneyRequest.Departure decodeDeparture(JsonNode departure) {
		if (departure == null || !departure.isObject() || !departure.path("mode").isTextual()) throw new InvalidRequest();
		return switch (departure.path("mode").textValue()) {
			case "NOW" -> {
				if (!hasExactFields(departure, Set.of("mode"))) throw new InvalidRequest();
				yield new JourneyRequest.Departure.Now();
			}
			case "SCHEDULED" -> {
				if (!hasExactFields(departure, Set.of("mode", "requestedAt"))
					|| !departure.path("requestedAt").isTextual()) throw new InvalidRequest();
				yield new JourneyRequest.Departure.Scheduled(OffsetDateTime.parse(departure.path("requestedAt").textValue()).toInstant());
			}
			default -> throw new InvalidRequest();
		};
	}

	private static boolean hasExactFields(JsonNode value, Set<String> expected) {
		if (value == null || !value.isObject() || value.size() != expected.size()) return false;
		var actual = new HashSet<String>();
		value.fieldNames().forEachRemaining(actual::add);
		return actual.equals(expected);
	}

	private record ObservationResponse(
		String requestId,
		String routeBundleSha256,
		long bundleGeneration,
		JourneyExecutionResult.ServiceDayIdentity serviceDay,
		com.easysubway.journey.application.JourneyRaptorPort.ScanMetrics scanMetrics,
		JourneyExecutionResult.BoundaryObservation boundaryObservation,
		long executionNanos,
		long allocatedBytes,
		JourneyReadinessService.ActiveReadiness activeReadiness
	) {
		private static ObservationResponse from(JourneyExecutionResult.ExecutionObservation observation,
			MeasuredCompleted measurement, JourneyReadinessService.ActiveReadiness activeReadiness) {
			return new ObservationResponse(observation.requestId(), observation.routeBundleSha256(),
				observation.bundleGeneration(), observation.serviceDay(), observation.scanMetrics(),
				observation.boundaryObservation(), measurement.executionNanos(), measurement.allocatedBytes(),
				activeReadiness);
		}
	}

	private record Failure(String reason) {
	}

	private static final class InvalidRequest extends RuntimeException {
	}
}
