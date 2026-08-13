package com.easysubway.journey.adapter.in.web;

import com.easysubway.journey.activation.JourneyActivationCommandParser;
import com.easysubway.journey.activation.JourneyActivationException;
import com.easysubway.journey.activation.JourneyActivationService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("(prod | staging | release | prod-like) & !capacity-evidence")
public final class JourneyActivationController {

	public static final String PATH = "/internal/v1/journey/activation";

	private final JourneyActivationCommandParser parser;
	private final JourneyActivationService activationService;

	public JourneyActivationController(
		JourneyActivationCommandParser parser,
		JourneyActivationService activationService) {
		this.parser = Objects.requireNonNull(parser, "parser");
		this.activationService = Objects.requireNonNull(activationService, "activationService");
	}

	@PostMapping(value = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<?> activate(HttpServletRequest request) {
		try {
			requireJson(request.getContentType());
			byte[] requestBytes = request.getInputStream()
				.readNBytes(JourneyActivationCommandParser.MAX_REQUEST_BYTES + 1);
			return response(HttpStatus.OK, activationService.activate(parser.parse(requestBytes)));
		} catch (JourneyActivationException exception) {
			return failure(exception.kind());
		} catch (IOException exception) {
			return failure(JourneyActivationException.Kind.INVALID_REQUEST);
		}
	}

	private static void requireJson(String contentType) {
		try {
			if (contentType == null) {
				throw new JourneyActivationException(JourneyActivationException.Kind.INVALID_REQUEST);
			}
			MediaType mediaType = MediaType.parseMediaType(contentType);
			if (mediaType.isWildcardType()
				|| mediaType.isWildcardSubtype()
				|| !MediaType.APPLICATION_JSON.isCompatibleWith(mediaType)) {
				throw new JourneyActivationException(JourneyActivationException.Kind.INVALID_REQUEST);
			}
		} catch (IllegalArgumentException exception) {
			throw new JourneyActivationException(JourneyActivationException.Kind.INVALID_REQUEST);
		}
	}

	private static ResponseEntity<?> failure(JourneyActivationException.Kind kind) {
		HttpStatus status = switch (kind) {
			case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
			case CONFLICT -> HttpStatus.CONFLICT;
			case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
		};
		return response(status, new Failure(1, "journey-v3-activation-failure", false, kind.name()));
	}

	private static ResponseEntity<?> response(HttpStatus status, Object body) {
		return ResponseEntity.status(status)
			.cacheControl(CacheControl.noStore())
			.body(body);
	}

	private record Failure(int schemaVersion, String artifactKind, boolean activated, String reason) {
	}
}
