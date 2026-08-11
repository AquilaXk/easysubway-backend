package com.easysubway.journey.adapter.in.web;

import com.easysubway.journey.application.JourneySessionException;
import com.easysubway.journey.application.JourneySessionException.Kind;
import com.easysubway.journey.application.JourneySessionService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnBean(JourneySessionService.class)
final class JourneySessionController {

	private final JourneySessionService sessionService;

	JourneySessionController(JourneySessionService sessionService) {
		this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
	}

	@PostMapping("/api/v3/journeys/session")
	ResponseEntity<JourneySessionResponse> issue(@RequestBody JsonNode request) {
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
