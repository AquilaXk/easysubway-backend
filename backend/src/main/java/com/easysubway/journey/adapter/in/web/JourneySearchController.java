package com.easysubway.journey.adapter.in.web;

import com.easysubway.journey.application.JourneyApplicationService;
import com.easysubway.journey.application.JourneyExecutionDisposition;
import com.easysubway.journey.application.JourneyExecutionFailure;
import com.easysubway.journey.application.JourneyExecutionResult;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.JourneySessionException;
import com.easysubway.journey.application.JourneySessionService;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
final class JourneySearchController {

	private static final ObjectMapper REQUEST_JSON = new ObjectMapper(JsonFactory.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	private static final Pattern SESSION_TOKEN = Pattern.compile("^[A-Za-z0-9_-]+$");
	private static final BooleanSupplier NOT_CANCELLED = () -> false;
	private static final Set<String> REQUEST_FIELDS = Set.of(
		"requestId", "originStationId", "destinationStationId", "departure", "timePolicy",
		"mobilityProfile", "constraintMode", "maxTransfers", "alternativeCount"
	);

	private final JourneySessionService sessionService;
	private final JourneyApplicationService applicationService;

	JourneySearchController(JourneySessionService sessionService, JourneyApplicationService applicationService) {
		this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
		this.applicationService = Objects.requireNonNull(applicationService, "applicationService");
	}

	@PostMapping("/api/v3/journeys/search")
	ResponseEntity<JourneySearchResponseMapper.JourneySearchResponse> search(
		@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
		HttpServletRequest servletRequest
	) {
		sessionService.authorize(requireBearerToken(authorization));
		JourneyRequest request = decodeRequest(readRequest(servletRequest));
		JourneyExecutionResult result;
		try {
			result = applicationService.execute(request);
		} catch (RuntimeException exception) {
			throw serviceUnavailable(request.requestId());
		}
		if (result == null) throw serviceUnavailable(request.requestId());

		return switch (result) {
			case JourneyExecutionResult.Success success -> ResponseEntity.ok()
				.header(HttpHeaders.CACHE_CONTROL, "private, no-store")
				.body(JourneySearchResponseMapper.map(success));
			case JourneyExecutionFailure failure -> throw publicFailure(request.requestId(), failure);
		};
	}

	private static String requireBearerToken(String authorization) {
		if (authorization == null) {
			throw new JourneySessionException(JourneySessionException.Kind.SESSION_REQUIRED);
		}
		int separator = authorization.indexOf(' ');
		if (separator < 1 || !authorization.substring(0, separator).equalsIgnoreCase("Bearer")) {
			throw new JourneySessionException(JourneySessionException.Kind.SESSION_REQUIRED);
		}
		int tokenStart = separator;
		while (tokenStart < authorization.length() && authorization.charAt(tokenStart) == ' ') tokenStart++;
		String token = authorization.substring(tokenStart);
		if (!SESSION_TOKEN.matcher(token).matches()) {
			throw new JourneySessionException(JourneySessionException.Kind.SESSION_REQUIRED);
		}
		return token;
	}

	private static byte[] readRequest(HttpServletRequest request) {
		try {
			return request.getInputStream().readAllBytes();
		} catch (IOException exception) {
			throw invalidRequest();
		}
	}

	private static JourneyRequest decodeRequest(byte[] requestBytes) {
		try {
			JsonNode request = REQUEST_JSON.readTree(requestBytes);
			if (!hasExactFields(request, REQUEST_FIELDS)
				|| !request.path("requestId").isTextual()
				|| !request.path("originStationId").isTextual()
				|| !request.path("destinationStationId").isTextual()
				|| !request.path("timePolicy").isTextual()
				|| !request.path("mobilityProfile").isTextual()
				|| !request.path("constraintMode").isTextual()
				|| !request.path("maxTransfers").isInt()
				|| !request.path("alternativeCount").isInt()) {
				throw invalidRequest();
			}
			return new JourneyRequest(
				request.path("requestId").textValue(),
				request.path("originStationId").textValue(),
				request.path("destinationStationId").textValue(),
				decodeDeparture(request.path("departure")),
				JourneyRequest.TimePolicy.valueOf(request.path("timePolicy").textValue()),
				JourneyRequest.MobilityProfile.valueOf(request.path("mobilityProfile").textValue()),
				JourneyRequest.ConstraintMode.valueOf(request.path("constraintMode").textValue()),
				request.path("maxTransfers").intValue(),
				request.path("alternativeCount").intValue(),
				NOT_CANCELLED
			);
		} catch (JourneySearchWebException exception) {
			throw exception;
		} catch (IOException | RuntimeException exception) {
			throw invalidRequest();
		}
	}

	private static JourneyRequest.Departure decodeDeparture(JsonNode departure) {
		if (departure == null || !departure.isObject() || !departure.path("mode").isTextual()) {
			throw invalidRequest();
		}
		return switch (departure.path("mode").textValue()) {
			case "NOW" -> {
				if (!hasExactFields(departure, Set.of("mode"))) throw invalidRequest();
				yield new JourneyRequest.Departure.Now();
			}
			case "SCHEDULED" -> {
				if (!hasExactFields(departure, Set.of("mode", "requestedAt"))
					|| !departure.path("requestedAt").isTextual()) {
					throw invalidRequest();
				}
				yield new JourneyRequest.Departure.Scheduled(
					OffsetDateTime.parse(departure.path("requestedAt").textValue()).toInstant()
				);
			}
			default -> throw invalidRequest();
		};
	}

	private static boolean hasExactFields(JsonNode value, Set<String> expected) {
		if (value == null || !value.isObject() || value.size() != expected.size()) return false;
		var actual = new java.util.HashSet<String>();
		value.fieldNames().forEachRemaining(actual::add);
		return actual.equals(expected);
	}

	private static JourneySearchWebException publicFailure(String requestId, JourneyExecutionFailure failure) {
		JourneyExecutionDisposition disposition = JourneyExecutionDisposition.from(failure);
		return switch (disposition) {
			case JourneyExecutionDisposition.PublicFailure publicFailure -> new JourneySearchWebException(
				requestId,
				publicFailure.httpStatus(),
				publicFailure.machineCode().name()
			);
			case JourneyExecutionDisposition.Cancelled ignored -> serviceUnavailable(requestId);
		};
	}

	private static JourneySearchWebException invalidRequest() {
		return new JourneySearchWebException(null, 400, "INVALID_JOURNEY_REQUEST");
	}

	private static JourneySearchWebException serviceUnavailable(String requestId) {
		return new JourneySearchWebException(requestId, 503, "ROUTE_SERVICE_UNAVAILABLE");
	}

	static final class JourneySearchWebException extends RuntimeException {
		private final String requestId;
		private final int httpStatus;
		private final String machineCode;

		JourneySearchWebException(String requestId, int httpStatus, String machineCode) {
			super(Objects.requireNonNull(machineCode, "machineCode"));
			this.requestId = requestId;
			this.httpStatus = httpStatus;
			this.machineCode = machineCode;
		}

		String requestId() {
			return requestId;
		}

		int httpStatus() {
			return httpStatus;
		}

		String machineCode() {
			return machineCode;
		}
	}
}
