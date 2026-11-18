package com.easysubway.journey.adapter.in.web;

import com.easysubway.journey.analytics.JourneySearchKind;
import com.easysubway.journey.analytics.JourneySearchLatencyMetrics;
import com.easysubway.journey.analytics.JourneySearchRecorder;
import com.easysubway.journey.application.JourneyProfileDeadlineExecutor;
import com.easysubway.journey.application.JourneyProfileExecutionDisposition;
import com.easysubway.journey.application.JourneyProfileExecutionResult;
import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyRaptorPruningInventoryV1;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneySessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
final class JourneyProfileController {

	public static final int DEFAULT_MAX_REQUEST_BYTES = 65_536;

	private final JourneySessionService sessionService;
	private final JourneyProfileDeadlineExecutor deadlineExecutor;
	private final JourneyProfileResourcePolicy resourcePolicy;
	private final JourneySearchRecorder recorder;
	private final JourneySearchLatencyMetrics latency;
	private final int maxRequestBytes;

	@Autowired
	JourneyProfileController(
		JourneySessionService sessionService,
		JourneyProfileDeadlineExecutor deadlineExecutor,
		JourneyProfileResourcePolicy resourcePolicy,
		@Value("${easysubway.journey.profile.max-request-bytes:65536}") int maxRequestBytes,
		JourneySearchRecorder recorder,
		JourneySearchLatencyMetrics latency
	) {
		this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
		this.deadlineExecutor = Objects.requireNonNull(deadlineExecutor, "deadlineExecutor");
		this.resourcePolicy = Objects.requireNonNull(resourcePolicy, "resourcePolicy");
		this.recorder = Objects.requireNonNull(recorder, "recorder");
		this.latency = Objects.requireNonNull(latency, "latency");
		if (maxRequestBytes <= 0) throw new IllegalArgumentException("maxRequestBytes must be positive");
		this.maxRequestBytes = maxRequestBytes;
	}

	@PostMapping("/api/v3/journeys/profile")
	ResponseEntity<ObjectNode> profile(
		@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
		HttpServletRequest servletRequest
	) {
		long startedNanos = latency.start();
		String token = JourneySearchController.requireBearerToken(authorization);
		JourneyRaptorQuery query = decode(readRequest(servletRequest));
		sessionService.authorize(token, resourcePolicy.costUnitsFor(query.temporalQuery()));
		// 모드는 요청을 해석한 직후 한 번만 정한다. 이후 모든 종료 지점이 같은 모드 태그를 쓴다.
		JourneySearchKind kind = JourneySearchKind.of(query.temporalQuery());
		JourneyProfileDeadlineExecutor.Outcome outcome = null;
		try {
			outcome = deadlineExecutor.execute(query, resourcePolicy);
		} catch (RuntimeException exception) {
			// 실행 실패와 null 결과는 같은 503으로 닫고 같은 방식으로 기록한다.
		}
		if (outcome == null) {
			throw recorded(query, kind, startedNanos, null, serviceUnavailable(query.requestId()));
		}
		try {
			return respond(query, kind, outcome, startedNanos);
		} catch (RuntimeException exception) {
			// recorded()가 던진 실패는 이미 기록했다. 그 밖의 예상하지 못한 예외는 error로 한 번만 센다.
			if (!(exception instanceof JourneySearchController.JourneySearchWebException)) {
				latency.recordError(kind, startedNanos);
			}
			throw exception;
		}
	}

	private ResponseEntity<ObjectNode> respond(
		JourneyRaptorQuery query, JourneySearchKind kind, JourneyProfileDeadlineExecutor.Outcome outcome, long startedNanos
	) {
		JourneyProfileExecutionResult result = switch (outcome) {
			case JourneyProfileDeadlineExecutor.Completed completed -> completed.result();
			case JourneyProfileDeadlineExecutor.TimedOut ignored -> throw recorded(query, kind, startedNanos, null,
				webFailure(query.requestId(), 504, "JOURNEY_PROFILE_TIMEOUT"));
		};
		return switch (result) {
			case JourneyProfileExecutionResult.Success success -> {
				ObjectNode body = map(query, kind, success, startedNanos);
				List<String> tags = objectiveTags(body);
				latency.recordSuccess(kind, startedNanos);
				recorder.recordProfileSuccess(query, success, tags);
				yield ResponseEntity.ok()
					.header(HttpHeaders.CACHE_CONTROL, "private, no-store")
					.body(body);
			}
			case JourneyProfileExecutionResult.Failure failure -> throw recorded(query, kind, startedNanos,
				failure.countSnapshot(), disposition(query.requestId(), failure));
		};
	}

	private ObjectNode map(
		JourneyRaptorQuery query, JourneySearchKind kind, JourneyProfileExecutionResult.Success success, long startedNanos
	) {
		try {
			return JourneyProfileResponseMapper.map(query, success, resourcePolicy, UUID.randomUUID().toString());
		} catch (JourneyProfileResponseMapper.MappingException exception) {
			throw recorded(query, kind, startedNanos, success.countSnapshot(),
				disposition(query.requestId(), new JourneyProfileExecutionResult.Failure(exception.reason())));
		}
	}

	private static List<String> objectiveTags(ObjectNode body) {
		List<String> tags = new ArrayList<>();
		for (JsonNode journey : body.path("journeys")) {
			for (JsonNode tag : journey.path("objectiveTags")) tags.add(tag.asText());
		}
		return tags;
	}

	private JourneySearchController.JourneySearchWebException recorded(
		JourneyRaptorQuery query,
		JourneySearchKind kind,
		long startedNanos,
		JourneyRaptorPruningInventoryV1.CountSnapshot countSnapshot,
		JourneySearchController.JourneySearchWebException exception
	) {
		latency.recordFailure(kind, startedNanos, exception.httpStatus(), exception.machineCode());
		recorder.recordProfileFailure(query, exception.httpStatus(), exception.machineCode(), countSnapshot);
		return exception;
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

	private static JourneySearchController.JourneySearchWebException disposition(
		String requestId, JourneyProfileExecutionResult.Failure failure
	) {
		return switch (JourneyProfileExecutionDisposition.from(failure)) {
			case JourneyProfileExecutionDisposition.PublicFailure publicFailure -> webFailure(requestId,
				publicFailure.httpStatus(), publicFailure.machineCode().name());
			case JourneyProfileExecutionDisposition.Cancelled ignored -> serviceUnavailable(requestId);
			case JourneyProfileExecutionDisposition.InternalFailure ignored -> serviceUnavailable(requestId);
		};
	}

	private static JourneySearchController.JourneySearchWebException invalidTemporalQuery() {
		return webFailure(null, 400, "INVALID_TEMPORAL_QUERY");
	}

	private static JourneySearchController.JourneySearchWebException serviceUnavailable(String requestId) {
		return webFailure(requestId, 503, "ROUTE_SERVICE_UNAVAILABLE");
	}

	private static JourneySearchController.JourneySearchWebException webFailure(
		String requestId, int status, String code
	) {
		return new JourneySearchController.JourneySearchWebException(requestId, status, code);
	}
}
