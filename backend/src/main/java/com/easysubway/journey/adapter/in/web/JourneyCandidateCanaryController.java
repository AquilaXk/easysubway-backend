package com.easysubway.journey.adapter.in.web;

import com.easysubway.journey.canary.JourneyCandidateCanaryCommandParser;
import com.easysubway.journey.canary.JourneyCandidateCanaryException;
import com.easysubway.journey.canary.JourneyCandidateCanaryService;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("(prod | staging | release | prod-like) & !capacity-evidence")
@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
public final class JourneyCandidateCanaryController {

	public static final String PATH = "/internal/v1/journey/canary";

	private final JourneyCandidateCanaryCommandParser parser;
	private final JourneyCandidateCanaryService service;

	public JourneyCandidateCanaryController(
		JourneyCandidateCanaryCommandParser parser,
		JourneyCandidateCanaryService service) {
		this.parser = Objects.requireNonNull(parser, "parser");
		this.service = Objects.requireNonNull(service, "service");
	}

	@PostMapping(value = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<?> canary(HttpServletRequest request) {
		try {
			requireJson(request.getContentType());
			byte[] requestBytes = request.getInputStream()
				.readNBytes(JourneyCandidateCanaryCommandParser.MAX_REQUEST_BYTES + 1);
			return response(HttpStatus.OK, service.execute(parser.parse(requestBytes)));
		} catch (JourneyCandidateCanaryException exception) {
			return failure(exception);
		} catch (IOException exception) {
			return failure(new JourneyCandidateCanaryException(JourneyCandidateCanaryException.Kind.INVALID_REQUEST));
		}
	}

	private static void requireJson(String contentType) {
		try {
			if (contentType == null) {
				throw new JourneyCandidateCanaryException(JourneyCandidateCanaryException.Kind.INVALID_REQUEST);
			}
			MediaType mediaType = MediaType.parseMediaType(contentType);
			if (mediaType.isWildcardType()
				|| mediaType.isWildcardSubtype()
				|| !MediaType.APPLICATION_JSON.getType().equals(mediaType.getType())
				|| !MediaType.APPLICATION_JSON.getSubtype().equals(mediaType.getSubtype())) {
				throw new JourneyCandidateCanaryException(JourneyCandidateCanaryException.Kind.INVALID_REQUEST);
			}
		} catch (IllegalArgumentException exception) {
			throw new JourneyCandidateCanaryException(JourneyCandidateCanaryException.Kind.INVALID_REQUEST);
		}
	}

	private static ResponseEntity<?> failure(JourneyCandidateCanaryException exception) {
		var kind = exception.kind();
		HttpStatus status = switch (kind) {
			case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
			case CONFLICT -> HttpStatus.CONFLICT;
			case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
		};
		return response(status, new Failure(
			1, "journey-v3-candidate-canary-failure", false, kind.name(),
			exception.failureReason() == null ? null : exception.failureReason().name(),
			exception.probeId()));
	}

	private static ResponseEntity<?> response(HttpStatus status, Object body) {
		return ResponseEntity.status(status)
			.cacheControl(CacheControl.noStore())
			.body(body);
	}

	/** {@code failureReason}·{@code probeId}는 추가 필드다. 값이 없으면 직렬화하지 않아 기존 응답과 같다. */
	private record Failure(
		int schemaVersion,
		String artifactKind,
		boolean passed,
		String reason,
		@JsonInclude(JsonInclude.Include.NON_NULL) String failureReason,
		@JsonInclude(JsonInclude.Include.NON_NULL) String probeId) {
	}
}
