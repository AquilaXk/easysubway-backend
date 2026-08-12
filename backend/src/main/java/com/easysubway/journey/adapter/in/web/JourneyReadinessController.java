package com.easysubway.journey.adapter.in.web;

import com.easysubway.journey.bundle.RouteBundleActivationException;
import com.easysubway.journey.readiness.JourneyReadinessService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("(prod | staging | release | prod-like) & !capacity-evidence")
public final class JourneyReadinessController {

	public static final String CANDIDATE_PATH = "/internal/v1/journey/readiness/candidate";
	public static final String ACTIVE_PATH = "/internal/v1/journey/readiness/active";

	private final JourneyReadinessService readinessService;

	public JourneyReadinessController(JourneyReadinessService readinessService) {
		this.readinessService = readinessService;
	}

	@GetMapping(value = CANDIDATE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<?> candidate() {
		try {
			return response(HttpStatus.OK, readinessService.candidate());
		} catch (RouteBundleActivationException exception) {
			return unavailable("candidate");
		}
	}

	@GetMapping(value = ACTIVE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<?> active() {
		try {
			return response(HttpStatus.OK, readinessService.active());
		} catch (RouteBundleActivationException exception) {
			return unavailable("active");
		}
	}

	private static ResponseEntity<?> unavailable(String readinessKind) {
		return response(HttpStatus.SERVICE_UNAVAILABLE,
			new Failure(1, "journey-v3-readiness-failure", readinessKind, false, "UNAVAILABLE"));
	}

	private static ResponseEntity<?> response(HttpStatus status, Object body) {
		return ResponseEntity.status(status)
			.cacheControl(CacheControl.noStore())
			.body(body);
	}

	private record Failure(
		int schemaVersion,
		String artifactKind,
		String readinessKind,
		boolean ready,
		String reason) {
	}
}
