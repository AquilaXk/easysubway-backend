package com.easysubway.journey.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.JourneyRaptorQuery;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class JourneyProfileRequestDecoderTest {

	private static final BooleanSupplier CANCELLED = () -> true;

	@Test
	void decodesDepartBetweenWithCommonFieldsOffsetInstantsAndCallerCancellation() {
		JourneyRaptorQuery query = decode(temporal(
			"{\"kind\":\"DEPART_BETWEEN\",\"earliestReadyAt\":\"2026-09-01T09:00:00+09:00\","
				+ "\"latestReadyAt\":\"2026-09-01T09:05:00+09:00\"}"));

		assertThat(query.requestId()).isEqualTo("01ARZ3NDEKTSV4RRFFQ69G5FAV");
		assertThat(query.originStationId()).isEqualTo("station-a");
		assertThat(query.destinationStationId()).isEqualTo("station-b");
		assertThat(query.timePolicy().name()).isEqualTo("TIMETABLE_REQUIRED");
		assertThat(query.walkingPace().name()).isEqualTo("STANDARD");
		assertThat(query.mobilityProfile().name()).isEqualTo("STEP_FREE");
		assertThat(query.constraintMode().name()).isEqualTo("REQUIRE_STEP_FREE");
		assertThat(query.maxTransfers()).isEqualTo(2);
		assertThat(query.alternativeCount()).isEqualTo(3);
		assertThat(query.cancellationSignal()).isSameAs(CANCELLED);
		assertThat(query.isCancelled()).isTrue();
		assertThat(query.temporalQuery()).isEqualTo(new JourneyRaptorQuery.DepartBetween(
			Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-01T00:05:00Z")));
	}

	@Test
	void decodesArriveByWithoutAddingDepartBetweenWholeSecondRules() {
		JourneyRaptorQuery query = decode(temporal(
			"{\"kind\":\"ARRIVE_BY\",\"earliestReadyAt\":\"2026-09-01T09:00:00.500+09:00\","
				+ "\"arrivalDeadline\":\"2026-09-01T09:05:00.250+09:00\"}"));

		assertThat(query.temporalQuery()).isEqualTo(new JourneyRaptorQuery.ArriveBy(
			Instant.parse("2026-09-01T00:00:00.500Z"), Instant.parse("2026-09-01T00:05:00.250Z")));
	}

	@Test
	void decodesLastConnectionServiceDate() {
		JourneyRaptorQuery query = decode(temporal("{\"kind\":\"LAST_CONNECTION\",\"serviceDate\":\"2026-09-01\"}"));

		assertThat(query.temporalQuery()).isEqualTo(
			new JourneyRaptorQuery.LastConnection(LocalDate.of(2026, 9, 1)));
	}

	@Test
	void rejectsDuplicateExtraMissingAndTrailingJson() {
		assertInvalid(temporal("{\"kind\":\"LAST_CONNECTION\",\"serviceDate\":\"2026-09-01\"}")
			.replace("\"originStationId\"", "\"requestId\":\"01ARZ3NDEKTSV4RRFFQ69G5FAV\",\"originStationId\""));
		assertInvalid(temporal("{\"kind\":\"LAST_CONNECTION\",\"serviceDate\":\"2026-09-01\",\"extra\":true}"));
		assertInvalid(temporal("{\"kind\":\"LAST_CONNECTION\",\"serviceDate\":\"2026-09-01\"}")
			.replace(",\"alternativeCount\":3", ""));
		assertInvalid(temporal("{\"kind\":\"LAST_CONNECTION\",\"serviceDate\":\"2026-09-01\"}") + " []");
	}

	@Test
	void rejectsWrongTypesOverflowUnsupportedTemporalLocalTimestampsInvalidDatesAndBounds() {
		assertInvalid(temporal("{\"kind\":\"LAST_CONNECTION\",\"serviceDate\":\"2026-09-01\"}")
			.replace("\"maxTransfers\":2", "\"maxTransfers\":\"2\""));
		assertInvalid(temporal("{\"kind\":\"LAST_CONNECTION\",\"serviceDate\":\"2026-09-01\"}")
			.replace("\"maxTransfers\":2", "\"maxTransfers\":2147483648"));
		assertInvalid(temporal("{\"kind\":\"DEPART_AT\",\"readyAt\":\"2026-09-01T09:00:00+09:00\"}"));
		assertInvalid(temporal("{\"kind\":\"DEPART_BETWEEN\",\"earliestReadyAt\":\"2026-09-01T09:00:00\","
			+ "\"latestReadyAt\":\"2026-09-01T09:05:00+09:00\"}"));
		assertInvalid(temporal("{\"kind\":\"LAST_CONNECTION\",\"serviceDate\":\"2026-02-30\"}"));
		assertInvalid(temporal("{\"kind\":\"DEPART_BETWEEN\",\"earliestReadyAt\":\"2026-09-01T09:05:00+09:00\","
			+ "\"latestReadyAt\":\"2026-09-01T09:00:00+09:00\"}"));
		assertInvalid(temporal("{\"kind\":\"LAST_CONNECTION\",\"serviceDate\":\"2026-09-01\"}")
			.replace("\"mobilityProfile\":\"STEP_FREE\"", "\"mobilityProfile\":\"NO_STAIRS\"")
			.replace("\"constraintMode\":\"REQUIRE_STEP_FREE\"", "\"constraintMode\":\"NONE\""));
	}

	private static JourneyRaptorQuery decode(String json) {
		return JourneyProfileRequestDecoder.decode(json.getBytes(StandardCharsets.UTF_8), CANCELLED);
	}

	private static void assertInvalid(String json) {
		assertThatThrownBy(() -> decode(json)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("invalid Journey profile request");
	}

	private static String temporal(String temporalQuery) {
		return "{" + "\"requestId\":\"01ARZ3NDEKTSV4RRFFQ69G5FAV\","
			+ "\"originStationId\":\"station-a\",\"destinationStationId\":\"station-b\","
			+ "\"temporalQuery\":" + temporalQuery + ",\"timePolicy\":\"TIMETABLE_REQUIRED\","
			+ "\"walkingPace\":\"STANDARD\",\"mobilityProfile\":\"STEP_FREE\","
			+ "\"constraintMode\":\"REQUIRE_STEP_FREE\",\"maxTransfers\":2,\"alternativeCount\":3}";
	}
}
