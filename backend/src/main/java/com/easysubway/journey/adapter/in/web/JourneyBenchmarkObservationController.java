package com.easysubway.journey.adapter.in.web;

import com.easysubway.journey.application.JourneyApplicationDeadlineExecutor;
import com.easysubway.journey.application.JourneyApplicationDeadlineExecutor.MeasuredCompleted;
import com.easysubway.journey.application.JourneyApplicationDeadlineExecutor.MeasuredOutcome;
import com.easysubway.journey.application.JourneyApplicationDeadlineExecutor.TimedOut;
import com.easysubway.journey.application.JourneyExecutionFailure;
import com.easysubway.journey.application.JourneyExecutionResult;
import com.easysubway.journey.application.JourneyRequest;
import com.fasterxml.jackson.annotation.JsonInclude;
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
	private static final String ALTERNATIVE_COUNT = "alternativeCount";
	private static final String MAX_TRANSFERS = "maxTransfers";
	private static final String MOBILITY_PROFILE = "mobilityProfile";
	private static final String REQUESTED_AT = "requestedAt";
	private static final String REASON_UNAVAILABLE = "UNAVAILABLE";
	private static final String REASON_UNOBSERVABLE = "UNOBSERVABLE";
	private static final String WALKING_PACE = "walkingPace";
	private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	private static final BooleanSupplier NOT_CANCELLED = () -> false;
	private static final Set<String> REQUEST_FIELDS = Set.of(
		"requestId", "originStationId", "destinationStationId", "departure", "timePolicy",
		WALKING_PACE, MOBILITY_PROFILE, "constraintMode", MAX_TRANSFERS, ALTERNATIVE_COUNT);

	private final JourneyApplicationDeadlineExecutor deadlineExecutor;
	public JourneyBenchmarkObservationController(JourneyApplicationDeadlineExecutor deadlineExecutor) {
		this.deadlineExecutor = Objects.requireNonNull(deadlineExecutor, "deadlineExecutor");
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
					new Failure(outcome instanceof TimedOut ? "TIMEOUT" : REASON_UNAVAILABLE));
			}
			return completedResponse(request, completed);
		} catch (RuntimeException exception) {
			return response(HttpStatus.SERVICE_UNAVAILABLE, new Failure(REASON_UNAVAILABLE));
		}
	}

	private static ResponseEntity<?> completedResponse(JourneyRequest request, MeasuredCompleted completed) {
		if (completed.result() instanceof JourneyExecutionResult.Success success) {
			return successResponse(request, completed, success);
		}
		if (completed.result() instanceof JourneyExecutionFailure failure
			&& failure.reason() == JourneyExecutionFailure.Reason.NO_ROUTE) {
			if (failure.executionObservation() == null) return unobservable();
			return observedResponse(request, completed, failure.executionObservation(), OutcomeResponse.noRoute());
		}
		return response(HttpStatus.SERVICE_UNAVAILABLE, new Failure(REASON_UNAVAILABLE));
	}

	private static ResponseEntity<?> successResponse(JourneyRequest request, MeasuredCompleted completed,
		JourneyExecutionResult.Success success) {
		if (success.journeys().size() != 1) return unobservable();
		int transferCount = success.journeys().getFirst().transferCount();
		if (transferCount < 0 || transferCount > 3) return unobservable();
		return observedResponse(request, completed, success.executionObservation(),
			OutcomeResponse.success(transferCount));
	}

	private static ResponseEntity<?> observedResponse(JourneyRequest request, MeasuredCompleted completed,
		JourneyExecutionResult.ExecutionObservation observation, OutcomeResponse outcome) {
		if (!request.requestId().equals(observation.requestId())) {
			return response(HttpStatus.SERVICE_UNAVAILABLE, new Failure("IDENTITY_MISMATCH"));
		}
		if (observation.activeReadinessIdentity() == null
			|| observation.activeServingIdentity().status()
				!= JourneyExecutionResult.ActiveServingIdentity.Status.OBSERVED
			|| observation.boundaryObservation().status()
				!= JourneyExecutionResult.BoundaryObservation.Status.OBSERVED) return unobservable();
		return response(HttpStatus.OK, ObservationResponse.from(observation, completed, outcome));
	}

	private static ResponseEntity<?> unobservable() {
		return response(HttpStatus.SERVICE_UNAVAILABLE, new Failure(REASON_UNOBSERVABLE));
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
				|| !request.path(WALKING_PACE).isTextual()
				|| !request.path(MOBILITY_PROFILE).isTextual()
				|| !request.path("constraintMode").isTextual()
				|| !request.path(MAX_TRANSFERS).isInt()
				|| !request.path(ALTERNATIVE_COUNT).isInt()) throw new InvalidRequest();
			return new JourneyRequest(request.path("requestId").textValue(), request.path("originStationId").textValue(),
				request.path("destinationStationId").textValue(), decodeDeparture(request.path("departure")),
				JourneyRequest.TimePolicy.valueOf(request.path("timePolicy").textValue()),
				JourneyRequest.WalkingPace.valueOf(request.path(WALKING_PACE).textValue()),
				JourneyRequest.MobilityProfile.valueOf(request.path(MOBILITY_PROFILE).textValue()),
				JourneyRequest.ConstraintMode.valueOf(request.path("constraintMode").textValue()),
				request.path(MAX_TRANSFERS).intValue(), request.path(ALTERNATIVE_COUNT).intValue(), NOT_CANCELLED);
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
				if (!hasExactFields(departure, Set.of("mode", REQUESTED_AT))
					|| !departure.path(REQUESTED_AT).isTextual()) throw new InvalidRequest();
				yield new JourneyRequest.Departure.Scheduled(
					OffsetDateTime.parse(departure.path(REQUESTED_AT).textValue()).toInstant());
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
		OutcomeResponse outcome,
		ServiceDayResponse serviceDay,
		com.easysubway.journey.application.JourneyRaptorPort.ScanMetrics scanMetrics,
		JourneyExecutionResult.BoundaryObservation boundaryObservation,
		long executionNanos,
		long allocatedBytes,
		JourneyExecutionResult.ActiveReadinessIdentity activeReadiness,
		JourneyExecutionResult.ActiveServingIdentity activeServingIdentity
	) {
		private static ObservationResponse from(JourneyExecutionResult.ExecutionObservation observation,
			MeasuredCompleted measurement, OutcomeResponse outcome) {
			return new ObservationResponse(observation.requestId(), observation.routeBundleSha256(),
				observation.bundleGeneration(), outcome, ServiceDayResponse.from(observation.serviceDay()), observation.scanMetrics(),
				observation.boundaryObservation(), measurement.executionNanos(), measurement.allocatedBytes(),
				observation.activeReadinessIdentity(), observation.activeServingIdentity());
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	private record OutcomeResponse(String kind, Integer transferCount, String reason) {
		private OutcomeResponse {
			if ("SUCCESS".equals(kind)) {
				if (transferCount == null || transferCount < 0 || transferCount > 3 || reason != null) {
					throw new IllegalArgumentException("success outcome is invalid");
				}
			} else if (!"FAILURE".equals(kind) || transferCount != null || !"NO_ROUTE".equals(reason)) {
				throw new IllegalArgumentException("failure outcome is invalid");
			}
		}

		private static OutcomeResponse success(int transferCount) {
			return new OutcomeResponse("SUCCESS", transferCount, null);
		}

		private static OutcomeResponse noRoute() {
			return new OutcomeResponse("FAILURE", null, "NO_ROUTE");
		}
	}

	private record ServiceDayResponse(String serviceDate, String timezone, String cutoffLocalTime) {
		private static ServiceDayResponse from(JourneyExecutionResult.ServiceDayIdentity serviceDay) {
			return new ServiceDayResponse(serviceDay.serviceDate().toString(), serviceDay.timezone(),
				serviceDay.cutoffLocalTime());
		}
	}

	private record Failure(String reason) {
	}

	private static final class InvalidRequest extends RuntimeException {
	}
}
