package com.easysubway.journey.adapter.in.web;

import com.easysubway.journey.application.JourneySessionException;
import com.easysubway.journey.application.JourneySessionService;
import com.easysubway.journey.application.StationTimetableSearchService;
import com.easysubway.journey.application.StationTimetableSearchService.Failure;
import com.easysubway.journey.application.StationTimetableSearchService.FailureException;
import com.easysubway.journey.application.StationTimetableSearchService.Selector;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
final class StationTimetableSearchController {
	static final String PATH = "/api/v3/station-timetables/search";
	private static final ObjectMapper REQUEST_JSON = new ObjectMapper(JsonFactory.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	private static final Pattern SESSION_TOKEN = Pattern.compile("^[A-Za-z0-9_-]+$");
	private static final Set<String> REQUEST_FIELDS = Set.of("stationId", "lineId", "selector");
	private static final DateTimeFormatter SEOUL_RFC3339_SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX");

	private final JourneySessionService sessionService;
	private final StationTimetableSearchService service;

	StationTimetableSearchController(JourneySessionService sessionService, StationTimetableSearchService service) {
		this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
		this.service = Objects.requireNonNull(service, "service");
	}

	@PostMapping(value = PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<Response> search(
		@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
		HttpServletRequest servletRequest
	) {
		sessionService.authorize(requireBearerToken(authorization));
		StationTimetableSearchService.SearchRequest request = decode(readRequest(servletRequest));
		try {
			return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(map(service.search(request)));
		} catch (FailureException exception) {
			throw new StationTimetableSearchWebException(status(exception.failure()), exception.failure().name());
		}
	}

	private static Response map(StationTimetableSearchService.SearchResult result) {
		return new Response("STATION_TIMETABLE_SEARCH_V3", result.stationId(), result.lineId(), selector(result.selector()),
			result.resolvedDayType().name(), StationTimetableSearchService.SERVICE_ZONE.getId(),
			result.directionGroups().stream().map(group -> new DirectionGroup(
				group.directionName(), group.departures().stream().map(departure -> new Departure(
					departure.serviceDate().toString(), departure.secondsFromServiceDayStart(), departure.departureAt()
						.atZone(StationTimetableSearchService.SERVICE_ZONE).format(SEOUL_RFC3339_SECONDS),
					departure.servicePattern(), departure.serviceClass())).toList())).toList(),
			new SourceIdentity(result.sourceIdentity().timetableArtifactId(), result.sourceIdentity().timetableSnapshotSha256(),
				result.sourceIdentity().canonicalStationVersion(), result.sourceIdentity().canonicalStationSetSha256(),
				result.sourceIdentity().sourceLineageSha256(), result.sourceIdentity().evidenceHash(),
				result.sourceIdentity().freshUntil().toString()));
	}

	private static SelectorResponse selector(Selector selector) {
		return switch (selector) {
			case Selector.ServiceDateSelector value -> new ServiceDateSelectorResponse("SERVICE_DATE", value.serviceDate().toString());
			case Selector.DayTypeSelector value -> new DayTypeSelectorResponse("DAY_TYPE", value.dayType().name(), value.referenceDate().toString());
			case Selector.NextDeparturesSelector value -> new NextDeparturesSelectorResponse("NEXT_DEPARTURES", value.asOf().toString(), value.horizonDays());
		};
	}

	private static int status(Failure failure) {
		return switch (failure) {
			case INVALID_JOURNEY_REQUEST -> 400;
			case STATION_LINE_NOT_FOUND, TIMETABLE_NOT_COVERED -> 404;
			case TIMETABLE_UNAVAILABLE, TIMETABLE_STALE, TIMETABLE_IDENTITY_MISMATCH -> 503;
		};
	}

	private static StationTimetableSearchService.SearchRequest decode(byte[] bytes) {
		try {
			JsonNode request = REQUEST_JSON.readTree(bytes);
			if (!exact(request, REQUEST_FIELDS) || !request.path("stationId").isTextual() || !request.path("lineId").isTextual()) {
				throw invalid();
			}
			return new StationTimetableSearchService.SearchRequest(
				request.path("stationId").textValue(), request.path("lineId").textValue(), decodeSelector(request.path("selector")));
		} catch (StationTimetableSearchWebException exception) {
			throw exception;
		} catch (IOException | RuntimeException exception) {
			throw invalid();
		}
	}

	private static Selector decodeSelector(JsonNode selector) {
		if (selector == null || !selector.isObject() || !selector.path("kind").isTextual()) throw invalid();
		return switch (selector.path("kind").textValue()) {
			case "SERVICE_DATE" -> {
				if (!exact(selector, Set.of("kind", "serviceDate")) || !selector.path("serviceDate").isTextual()) throw invalid();
				yield new Selector.ServiceDateSelector(LocalDate.parse(selector.path("serviceDate").textValue()));
			}
			case "DAY_TYPE" -> {
				if (!exact(selector, Set.of("kind", "dayType", "referenceDate")) || !selector.path("dayType").isTextual()
					|| !selector.path("referenceDate").isTextual()) throw invalid();
				yield new Selector.DayTypeSelector(
					StationTimetableSearchService.DayType.valueOf(selector.path("dayType").textValue()),
					LocalDate.parse(selector.path("referenceDate").textValue()));
			}
			case "NEXT_DEPARTURES" -> {
				if (!exact(selector, Set.of("kind", "asOf", "horizonDays")) || !selector.path("asOf").isTextual()
					|| !selector.path("horizonDays").isInt()) throw invalid();
				yield new Selector.NextDeparturesSelector(Instant.parse(selector.path("asOf").textValue()), selector.path("horizonDays").intValue());
			}
			default -> throw invalid();
		};
	}

	private static byte[] readRequest(HttpServletRequest request) {
		try { return request.getInputStream().readAllBytes(); } catch (IOException exception) { throw invalid(); }
	}
	private static String requireBearerToken(String authorization) {
		if (authorization == null) throw new JourneySessionException(JourneySessionException.Kind.SESSION_REQUIRED);
		int separator = authorization.indexOf(' ');
		if (separator < 1 || !authorization.substring(0, separator).equalsIgnoreCase("Bearer")) {
			throw new JourneySessionException(JourneySessionException.Kind.SESSION_REQUIRED);
		}
		String token = authorization.substring(separator).trim();
		if (!SESSION_TOKEN.matcher(token).matches()) throw new JourneySessionException(JourneySessionException.Kind.SESSION_REQUIRED);
		return token;
	}
	private static boolean exact(JsonNode node, Set<String> expected) {
		if (node == null || !node.isObject() || node.size() != expected.size()) return false;
		Set<String> actual = new HashSet<>(); node.fieldNames().forEachRemaining(actual::add); return actual.equals(expected);
	}
	private static StationTimetableSearchWebException invalid() { return new StationTimetableSearchWebException(400, "INVALID_JOURNEY_REQUEST"); }

	static final class StationTimetableSearchWebException extends RuntimeException {
		private final int httpStatus; private final String machineCode;
		StationTimetableSearchWebException(int httpStatus, String machineCode) { super(machineCode); this.httpStatus = httpStatus; this.machineCode = machineCode; }
		int httpStatus() { return httpStatus; } String machineCode() { return machineCode; }
	}

	record Response(String contractVersion, String stationId, String lineId, SelectorResponse selector, String resolvedDayType,
		String serviceTimezone, List<DirectionGroup> directionGroups, SourceIdentity sourceIdentity) { }
	sealed interface SelectorResponse permits ServiceDateSelectorResponse, DayTypeSelectorResponse, NextDeparturesSelectorResponse { }
	record ServiceDateSelectorResponse(String kind, String serviceDate) implements SelectorResponse { }
	record DayTypeSelectorResponse(String kind, String dayType, String referenceDate) implements SelectorResponse { }
	record NextDeparturesSelectorResponse(String kind, String asOf, int horizonDays) implements SelectorResponse { }
	record DirectionGroup(String directionName, List<Departure> departures) { }
	record Departure(String serviceDate, int secondsFromServiceDayStart, String departureAt, String servicePattern, String serviceClass) { }
	record SourceIdentity(String timetableArtifactId, String timetableSnapshotSha256, String canonicalStationVersion,
		String canonicalStationSetSha256, String sourceLineageSha256, String evidenceHash, String freshUntil) { }
}
