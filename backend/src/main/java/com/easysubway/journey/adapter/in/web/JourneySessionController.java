package com.easysubway.journey.adapter.in.web;

import com.easysubway.journey.application.JourneySessionException;
import com.easysubway.journey.application.JourneySessionException.Kind;
import com.easysubway.journey.application.JourneySessionService;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "easysubway.journey-v3.session-web.enabled", havingValue = "true")
final class JourneySessionController {

	static final int DEFAULT_MAX_REQUEST_BYTES = 65536;

	private static final ObjectMapper REQUEST_JSON = new ObjectMapper(JsonFactory.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

	private final JourneySessionService sessionService;
	private final int maxRequestBytes;

	JourneySessionController(JourneySessionService sessionService) {
		this(sessionService, DEFAULT_MAX_REQUEST_BYTES);
	}

	@Autowired
	JourneySessionController(
		JourneySessionService sessionService,
		@Value("${easysubway.journey.session.max-request-bytes:65536}") int maxRequestBytes
	) {
		if (maxRequestBytes <= 0) {
			throw new IllegalArgumentException("maxRequestBytes must be positive");
		}
		this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
		this.maxRequestBytes = maxRequestBytes;
	}

	@PostMapping("/api/v3/journeys/session")
	ResponseEntity<JourneySessionResponse> issue(HttpServletRequest httpRequest) {
		byte[] requestBytes = readRequest(httpRequest);
		JsonNode request = parseRequest(requestBytes);
		if (!validRequestShape(request)) {
			throw new JourneySessionException(Kind.INVALID_REQUEST);
		}
		var issued = sessionService.issue(
			request.path("integrityToken").textValue(),
			request.path("clientNonce").textValue()
		);
		return ResponseEntity.ok()
			.header(HttpHeaders.CACHE_CONTROL, "private, no-store")
			.body(new JourneySessionResponse(
				issued.token(),
				issued.scope(),
				issued.issuedAt().toString(),
				issued.expiresAt().toString()
			));
	}

	private byte[] readRequest(HttpServletRequest request) {
		long contentLength = request.getContentLengthLong();
		if (contentLength > maxRequestBytes) {
			throw new JourneySessionException(Kind.INVALID_REQUEST);
		}
		try (InputStream input = request.getInputStream()) {
			byte[] bytes = input.readNBytes(maxRequestBytes);
			if (input.read() != -1) {
				throw new JourneySessionException(Kind.INVALID_REQUEST);
			}
			return bytes;
		} catch (IOException exception) {
			throw new JourneySessionException(Kind.INVALID_REQUEST);
		}
	}

	private static JsonNode parseRequest(byte[] requestBytes) {
		try {
			return REQUEST_JSON.readTree(requestBytes);
		} catch (IOException exception) {
			throw new JourneySessionException(Kind.INVALID_REQUEST);
		}
	}

	private static boolean validRequestShape(JsonNode request) {
		return request != null
			&& request.isObject()
			&& request.size() == 2
			&& request.path("integrityToken").isTextual()
			&& request.path("clientNonce").isTextual();
	}

	private record JourneySessionResponse(String token, String scope, String issuedAt, String expiresAt) {
	}
}
