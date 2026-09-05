package com.easysubway.journey.adapter.in.web;

import com.easysubway.journey.application.JourneyProfileDeadlineExecutor;
import com.easysubway.journey.application.JourneyProfileExecutionDisposition;
import com.easysubway.journey.application.JourneyProfileExecutionResult;
import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneySessionService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
final class JourneyProfileController {

	private final JourneySessionService sessionService;
	private final JourneyProfileDeadlineExecutor deadlineExecutor;
	private final JourneyProfileResourcePolicy resourcePolicy;
	private final int maxRequestBytes;

	JourneyProfileController(
		JourneySessionService sessionService,
		JourneyProfileDeadlineExecutor deadlineExecutor,
		JourneyProfileResourcePolicy resourcePolicy,
		@Value("${easysubway.journey.profile.max-request-bytes}") int maxRequestBytes
	) {
		this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
		this.deadlineExecutor = Objects.requireNonNull(deadlineExecutor, "deadlineExecutor");
		this.resourcePolicy = Objects.requireNonNull(resourcePolicy, "resourcePolicy");
		if (maxRequestBytes <= 0) throw new IllegalArgumentException("maxRequestBytes must be positive");
		this.maxRequestBytes = maxRequestBytes;
	}

	@PostMapping("/api/v3/journeys/profile")
	ResponseEntity<ObjectNode> profile(
		@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
		HttpServletRequest servletRequest
	) {
		String token = JourneySearchController.requireBearerToken(authorization);
		JourneyRaptorQuery query = decode(readRequest(servletRequest));
		sessionService.authorize(token, resourcePolicy.costUnitsFor(query.temporalQuery()));
		JourneyProfileDeadlineExecutor.Outcome outcome = deadlineExecutor.execute(query, resourcePolicy);
		JourneyProfileExecutionResult result = switch (outcome) {
			case JourneyProfileDeadlineExecutor.Completed completed -> completed.result();
			case JourneyProfileDeadlineExecutor.TimedOut ignored -> throw webFailure(query.requestId(), 504,
				"JOURNEY_PROFILE_TIMEOUT");
		};
		return switch (result) {
			case JourneyProfileExecutionResult.Success success -> ResponseEntity.ok()
				.header(HttpHeaders.CACHE_CONTROL, "private, no-store")
				.body(map(query, success));
			case JourneyProfileExecutionResult.Failure failure -> throw disposition(query.requestId(), failure);
		};
	}

	private ObjectNode map(JourneyRaptorQuery query, JourneyProfileExecutionResult.Success success) {
		try {
			return JourneyProfileResponseMapper.map(query, success, resourcePolicy, UUID.randomUUID().toString());
		} catch (JourneyProfileResponseMapper.MappingException exception) {
			throw disposition(query.requestId(), new JourneyProfileExecutionResult.Failure(exception.reason()));
		}
	}

	private byte[] readRequest(HttpServletRequest request) {
		try (InputStream input = request.getInputStream()) {
			byte[] bytes = input.readNBytes(maxRequestBytes);
			if (input.read() != -1) throw invalidTemporalQuery();
			return bytes;
		} catch (IOException exception) {
			throw invalidTemporalQuery();
		}
	}

	private static JourneyRaptorQuery decode(byte[] bytes) {
		try {
			return JourneyProfileRequestDecoder.decode(bytes, () -> Thread.currentThread().isInterrupted());
		} catch (IllegalArgumentException exception) {
			throw invalidTemporalQuery();
		}
	}

	private static RuntimeException disposition(
		String requestId, JourneyProfileExecutionResult.Failure failure
	) {
		return switch (JourneyProfileExecutionDisposition.from(failure)) {
			case JourneyProfileExecutionDisposition.PublicFailure publicFailure -> webFailure(requestId,
				publicFailure.httpStatus(), publicFailure.machineCode().name());
			case JourneyProfileExecutionDisposition.Cancelled ignored -> internalFailure();
			case JourneyProfileExecutionDisposition.InternalFailure ignored -> internalFailure();
		};
	}

	private static JourneySearchController.JourneySearchWebException invalidTemporalQuery() {
		return webFailure(null, 400, "INVALID_TEMPORAL_QUERY");
	}

	private static JourneySearchController.JourneySearchWebException webFailure(
		String requestId, int status, String code
	) {
		return new JourneySearchController.JourneySearchWebException(requestId, status, code);
	}

	private static IllegalStateException internalFailure() {
		return new IllegalStateException("Journey profile execution failed");
	}
}
