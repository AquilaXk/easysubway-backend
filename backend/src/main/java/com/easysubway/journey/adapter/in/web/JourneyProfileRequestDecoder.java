package com.easysubway.journey.adapter.in.web;

import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

final class JourneyProfileRequestDecoder {

	private static final Set<String> REQUEST_FIELDS = Set.of(
		"requestId", "originStationId", "destinationStationId", "temporalQuery", "timePolicy",
		"walkingPace", "mobilityProfile", "constraintMode", "maxTransfers", "alternativeCount");
	private static final Set<String> DEPART_BETWEEN_FIELDS = Set.of(
		"kind", "earliestReadyAt", "latestReadyAt");
	private static final Set<String> ARRIVE_BY_FIELDS = Set.of(
		"kind", "earliestReadyAt", "arrivalDeadline");
	private static final Set<String> LAST_CONNECTION_FIELDS = Set.of("kind", "serviceDate");
	private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

	private JourneyProfileRequestDecoder() {
	}

	static JourneyRaptorQuery decode(byte[] bytes, BooleanSupplier cancellationSignal) {
		if (bytes == null || bytes.length == 0 || cancellationSignal == null) throw invalidRequest();
		try {
			JsonNode root = JSON.readTree(bytes);
			if (!hasExactFields(root, REQUEST_FIELDS)) throw invalidRequest();
			return new JourneyRaptorQuery(
				requireText(root, "requestId"),
				requireText(root, "originStationId"),
				requireText(root, "destinationStationId"),
				decodeTemporalQuery(root.get("temporalQuery")),
				parseEnum(root, "timePolicy", JourneyRequest.TimePolicy.class),
				parseEnum(root, "walkingPace", JourneyRequest.WalkingPace.class),
				parseEnum(root, "mobilityProfile", JourneyRequest.MobilityProfile.class),
				parseEnum(root, "constraintMode", JourneyRequest.ConstraintMode.class),
				requireInt(root, "maxTransfers"),
				requireInt(root, "alternativeCount"),
				cancellationSignal);
		} catch (IOException | RuntimeException exception) {
			throw invalidRequest();
		}
	}

	private static JourneyRaptorQuery.TemporalQuery decodeTemporalQuery(JsonNode temporalQuery) {
		if (temporalQuery == null || !temporalQuery.isObject() || !temporalQuery.path("kind").isTextual()) {
			throw invalidRequest();
		}
		return switch (temporalQuery.path("kind").textValue()) {
			case "DEPART_BETWEEN" -> {
				requireExactFields(temporalQuery, DEPART_BETWEEN_FIELDS);
				yield new JourneyRaptorQuery.DepartBetween(
					parseOffsetDateTime(temporalQuery, "earliestReadyAt"),
					parseOffsetDateTime(temporalQuery, "latestReadyAt"));
			}
			case "ARRIVE_BY" -> {
				requireExactFields(temporalQuery, ARRIVE_BY_FIELDS);
				yield new JourneyRaptorQuery.ArriveBy(
					parseOffsetDateTime(temporalQuery, "earliestReadyAt"),
					parseOffsetDateTime(temporalQuery, "arrivalDeadline"));
			}
			case "LAST_CONNECTION" -> {
				requireExactFields(temporalQuery, LAST_CONNECTION_FIELDS);
				yield new JourneyRaptorQuery.LastConnection(
					LocalDate.parse(requireText(temporalQuery, "serviceDate")));
			}
			default -> throw invalidRequest();
		};
	}

	private static java.time.Instant parseOffsetDateTime(JsonNode root, String field) {
		return OffsetDateTime.parse(requireText(root, field)).toInstant();
	}

	private static <E extends Enum<E>> E parseEnum(JsonNode root, String field, Class<E> type) {
		return Enum.valueOf(type, requireText(root, field));
	}

	private static String requireText(JsonNode root, String field) {
		JsonNode value = root.get(field);
		if (value == null || !value.isTextual()) throw invalidRequest();
		return value.textValue();
	}

	private static int requireInt(JsonNode root, String field) {
		JsonNode value = root.get(field);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw invalidRequest();
		return value.intValue();
	}

	private static void requireExactFields(JsonNode value, Set<String> expected) {
		if (!hasExactFields(value, expected)) throw invalidRequest();
	}

	private static boolean hasExactFields(JsonNode value, Set<String> expected) {
		if (value == null || !value.isObject() || value.size() != expected.size()) return false;
		var actual = new HashSet<String>();
		value.fieldNames().forEachRemaining(actual::add);
		return actual.equals(expected);
	}

	private static IllegalArgumentException invalidRequest() {
		return new IllegalArgumentException("invalid Journey profile request");
	}
}
