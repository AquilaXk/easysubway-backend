package com.easysubway.route.adapter.in.web;

import com.easysubway.route.application.service.RouteV2SessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class RouteV2SessionController {

	private final RouteV2SessionService sessionService;

	RouteV2SessionController(RouteV2SessionService sessionService) {
		this.sessionService = sessionService;
	}

	@Autowired
	RouteV2SessionController(ObjectProvider<RouteV2SessionService> sessionService) {
		this.sessionService = sessionService.getIfAvailable();
	}

	@PostMapping("/api/v2/routes/session")
	ResponseEntity<RouteV2SessionResponse> issue(@Valid @RequestBody RouteV2SessionRequest request) {
		if (sessionService == null) {
			throw new IllegalStateException("Route V2 session service is unavailable");
		}
		var issued = sessionService.issue(request.integrityToken(), request.clientNonce());
		return ResponseEntity.ok()
			.header(HttpHeaders.CACHE_CONTROL, "private, no-store")
			.body(new RouteV2SessionResponse(
				issued.token(),
				issued.scope(),
				issued.issuedAt().toString(),
				issued.expiresAt().toString()
			));
	}

	private record RouteV2SessionRequest(
		@NotBlank @Size(max = 16_384) String integrityToken,
		@NotBlank String clientNonce
	) {
	}

	private record RouteV2SessionResponse(String token, String scope, String issuedAt, String expiresAt) {
	}
}
