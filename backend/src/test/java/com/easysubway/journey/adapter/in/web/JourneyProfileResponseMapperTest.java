package com.easysubway.journey.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.JourneyProfileExecutionResult;
import com.easysubway.journey.application.JourneyProfileRaptorPort;
import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyRaptorPort;
import com.easysubway.journey.application.JourneyRaptorPruningInventoryV1;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class JourneyProfileResponseMapperTest {

	@Test
	void exposesOnlyTheFixedSanitizedMappingFailure() {
		var exception = new JourneyProfileResponseMapper.MappingException(
			JourneyProfileExecutionResult.Reason.RAPTOR_FAILED);

		assertThat(exception).hasMessage("invalid Journey profile response");
		assertThat(exception.reason()).isEqualTo(JourneyProfileExecutionResult.Reason.RAPTOR_FAILED);
	}

	@Test
	void mapsClosedDepartureProfileWithActualIdentities() {
		var query = query(new JourneyRaptorQuery.DepartBetween(START, START.plusSeconds(60)));
		var plan = new JourneyProfileRaptorPort.DepartureWindowPlan(
			(JourneyRaptorQuery.DepartBetween) query.temporalQuery(),
			java.util.List.of(new JourneyProfileRaptorPort.DeparturePoint(DATE, START,
				java.util.List.of(itinerary(true)), new JourneyRaptorPort.ScanMetrics(1, 1, 1))));

		var json = JourneyProfileResponseMapper.map(query, success(query, plan), policy(), "server-query");

		var fields = new java.util.HashSet<String>();
		json.fieldNames().forEachRemaining(fields::add);
		assertThat(fields).containsExactlyInAnyOrder("contractVersion", "requestId", "queryId",
			"calculatedAt", "validUntil", "temporalQuery", "serviceDays", "sourceIdentity",
			"algorithmIdentity", "frontierPolicyIdentity", "resourcePolicyIdentity",
			"journeys", "summary", "profileSegments");
		assertThat(json.path("temporalQuery").path("kind").asText()).isEqualTo("DEPART_BETWEEN");
		assertThat(json.path("sourceIdentity").path("routeBundleGeneration").asText()).isEqualTo("7");
		assertThat(json.path("sourceIdentity").path("realtimeSnapshotId").isNull()).isTrue();
		assertThat(json.path("resourcePolicyIdentity").path("resourcePolicySha256").asText())
			.isEqualTo("a".repeat(64));
		assertThat(json.path("journeys").get(0).path("journey").path("timeSource").asText())
			.isEqualTo("TIMETABLE");
	}

	@Test
	void mapsReverseProfilesWithoutDepartureSegments() {
		var arriveBy = query(new JourneyRaptorQuery.ArriveBy(START, START.plusSeconds(600)));
		var arriveJson = JourneyProfileResponseMapper.map(arriveBy, success(arriveBy,
			new JourneyProfileRaptorPort.ArriveByPlan((JourneyRaptorQuery.ArriveBy) arriveBy.temporalQuery(),
				new JourneyProfileRaptorPort.ReversePlan.Found(java.util.List.of(itinerary(true))))), policy(), "arrive");
		var last = query(new JourneyRaptorQuery.LastConnection(DATE));
		var lastJson = JourneyProfileResponseMapper.map(last, success(last,
			new JourneyProfileRaptorPort.LastConnectionPlan((JourneyRaptorQuery.LastConnection) last.temporalQuery(),
				new JourneyProfileRaptorPort.ReversePlan.Found(java.util.List.of(itinerary(true))), START.plusSeconds(600))), policy(), "last");

		assertThat(arriveJson.has("profileSegments")).isFalse();
		assertThat(arriveJson.path("summary").path("kind").asText()).isEqualTo("ARRIVE_BY");
		assertThat(lastJson.has("profileSegments")).isFalse();
		assertThat(lastJson.path("summary").path("kind").asText()).isEqualTo("LAST_CONNECTION");
	}

	@Test
	void rejectsUnverifiedAccessAndMismatchedPolicyIdentity() {
		var query = query(new JourneyRaptorQuery.ArriveBy(START, START.plusSeconds(600)));
		var plan = new JourneyProfileRaptorPort.ArriveByPlan((JourneyRaptorQuery.ArriveBy) query.temporalQuery(),
			new JourneyProfileRaptorPort.ReversePlan.Found(java.util.List.of(itinerary(false))));

		assertThatThrownBy(() -> JourneyProfileResponseMapper.map(query, success(query, plan), policy(), "query"))
			.isInstanceOf(JourneyProfileResponseMapper.MappingException.class)
			.extracting(exception -> ((JourneyProfileResponseMapper.MappingException) exception).reason())
			.isEqualTo(JourneyProfileExecutionResult.Reason.RAPTOR_FAILED);
		var verifiedPlan = new JourneyProfileRaptorPort.ArriveByPlan(
			(JourneyRaptorQuery.ArriveBy) query.temporalQuery(),
			new JourneyProfileRaptorPort.ReversePlan.Found(java.util.List.of(itinerary(true))));
		assertThatThrownBy(() -> JourneyProfileResponseMapper.map(query, success(query, verifiedPlan, "b".repeat(64)), policy(), "query"))
			.isInstanceOf(JourneyProfileResponseMapper.MappingException.class);
	}

	@Test
	void preservesLastConnectionServiceDateAfterTheNextDaysCutoff() {
		Instant ready = DATE.atStartOfDay(com.easysubway.journey.application.ServiceDayResolver.ZONE)
			.toInstant().plus(Duration.ofHours(29));
		var query = query(new JourneyRaptorQuery.LastConnection(DATE));
		var original = itinerary(true);
		var legs = new java.util.ArrayList<>(original.legs());
		legs.set(1, new JourneyProfileRaptorPort.RideLeg("line", "late-trip", "destination", "board", "platform",
			ready.plusSeconds(60), ready.plusSeconds(540), null, null));
		var late = new JourneyProfileRaptorPort.Itinerary(DATE, ready, ready.plusSeconds(600),
			null, null, original.metrics(), legs);
		var plan = new JourneyProfileRaptorPort.LastConnectionPlan(
			(JourneyRaptorQuery.LastConnection) query.temporalQuery(),
			new JourneyProfileRaptorPort.ReversePlan.Found(java.util.List.of(late)), ready.plusSeconds(600));
		var template = success(query, plan);
		var execution = new JourneyProfileExecutionResult.Success(ready, ready.plusSeconds(900),
			template.sourceIdentity(), template.resourcePolicyIdentity(), plan, template.countSnapshot());

		var json = JourneyProfileResponseMapper.map(query, execution, policy(), "late-last");

		assertThat(json.path("serviceDays")).hasSize(1);
		assertThat(json.path("serviceDays").get(0).path("serviceDate").asText()).isEqualTo(DATE.toString());
		assertThat(json.path("summary").path("latestFeasibleDeparture").asText()).isEqualTo(ready.toString());
	}

	@Test
	void rejectsRealtimeTimesAndVerifiedStairsInStepFreeTimetableResponses() {
		var query = query(new JourneyRaptorQuery.ArriveBy(START, START.plusSeconds(600)));
		var valid = itinerary(true);
		var stairs = new java.util.ArrayList<>(valid.legs());
		stairs.set(0, new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.ENTRY,
			"origin", "board", 60, 10, true, true, "VERIFIED"));
		var invalidItineraries = java.util.List.of(
			new JourneyProfileRaptorPort.Itinerary(DATE, START, START.plusSeconds(600), START, START.plusSeconds(600),
				valid.metrics(), valid.legs()),
			new JourneyProfileRaptorPort.Itinerary(DATE, START, START.plusSeconds(600), null, null,
				valid.metrics(), stairs));

		for (var invalid : invalidItineraries) {
			var plan = new JourneyProfileRaptorPort.ArriveByPlan(
				(JourneyRaptorQuery.ArriveBy) query.temporalQuery(),
				new JourneyProfileRaptorPort.ReversePlan.Found(java.util.List.of(invalid)));
			assertThatThrownBy(() -> JourneyProfileResponseMapper.map(
				query, success(query, plan), policy(), "query"))
				.isInstanceOf(JourneyProfileResponseMapper.MappingException.class)
				.extracting(exception -> ((JourneyProfileResponseMapper.MappingException) exception).reason())
				.isEqualTo(JourneyProfileExecutionResult.Reason.RAPTOR_FAILED);
		}
	}

	private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");

	@Test
	void preservesVerifiedTransferChainsAndRejectsDisconnectedTransfers() {
		var query = query(new JourneyRaptorQuery.ArriveBy(START, START.plusSeconds(600)));
		var legs = new java.util.ArrayList<JourneyProfileRaptorPort.Leg>(java.util.List.of(
			new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.ENTRY,
				"origin", "board", 60, 10, false, true, "VERIFIED"),
			new JourneyProfileRaptorPort.RideLeg("line-a", "trip-a", "interchange", "board", "interchange",
				START.plusSeconds(60), START.plusSeconds(240), null, null),
			new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.TRANSFER,
				"interchange", "next-board", 60, 10, false, true, "VERIFIED"),
			new JourneyProfileRaptorPort.RideLeg("line-b", "trip-b", "destination", "next-board", "platform",
				START.plusSeconds(360), START.plusSeconds(540), null, null),
			new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.EXIT,
				"platform", "destination", 60, 10, false, true, "VERIFIED")));
		var metrics = new JourneyProfileRaptorPort.ItineraryMetrics(1, 180, 30, 0,
			new JourneyProfileRaptorPort.MinimumTransferSeconds(60));
		var itinerary = new JourneyProfileRaptorPort.Itinerary(DATE, START, START.plusSeconds(600),
			null, null, metrics, legs);
		var plan = new JourneyProfileRaptorPort.ArriveByPlan((JourneyRaptorQuery.ArriveBy) query.temporalQuery(),
			new JourneyProfileRaptorPort.ReversePlan.Found(java.util.List.of(itinerary)));
		var journey = JourneyProfileResponseMapper.map(query, success(query, plan), policy(), "transfer-query")
			.path("journeys").get(0).path("journey");
		assertThat(journey.path("transferCount").asInt()).isEqualTo(1);
		assertThat(journey.path("walkingDistanceMeters").asLong()).isEqualTo(30);
		assertThat(journey.path("legs").size()).isEqualTo(5);
		var transfer = journey.path("legs").get(2);
		assertThat(transfer.path("type").asText()).isEqualTo("TRANSFER");
		assertThat(transfer.path("fromStationId").asText())
			.isEqualTo(journey.path("legs").get(1).path("toStationId").asText()).isEqualTo("interchange");
		assertThat(transfer.path("toStationId").asText())
			.isEqualTo(journey.path("legs").get(3).path("fromStationId").asText()).isEqualTo("next-board");

		legs.set(2, new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.TRANSFER,
			"disconnected", "next-board", 60, 10, false, true, "VERIFIED"));
		var invalid = new JourneyProfileRaptorPort.Itinerary(DATE, START, START.plusSeconds(600),
			null, null, metrics, legs);
		var invalidPlan = new JourneyProfileRaptorPort.ArriveByPlan((JourneyRaptorQuery.ArriveBy) query.temporalQuery(),
			new JourneyProfileRaptorPort.ReversePlan.Found(java.util.List.of(invalid)));
		assertThatThrownBy(() -> JourneyProfileResponseMapper.map(query, success(query, invalidPlan), policy(), "invalid"))
			.isInstanceOf(JourneyProfileResponseMapper.MappingException.class)
			.extracting(exception -> ((JourneyProfileResponseMapper.MappingException) exception).reason())
			.isEqualTo(JourneyProfileExecutionResult.Reason.RAPTOR_FAILED);
	}

	private static final LocalDate DATE = LocalDate.of(2026, 9, 1);

	private static JourneyRaptorQuery query(JourneyRaptorQuery.TemporalQuery temporal) {
		return new JourneyRaptorQuery("01ARZ3NDEKTSV4RRFFQ69G5FAV", "origin", "destination", temporal,
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STEP_FREE, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE, 1, 1,
			(BooleanSupplier) () -> false);
	}

	static JourneyProfileExecutionResult.Success success(
		JourneyRaptorQuery query, JourneyProfileRaptorPort.TemporalPlan plan
	) { return success(query, plan, "a".repeat(64)); }

	private static JourneyProfileExecutionResult.Success success(
		JourneyRaptorQuery query, JourneyProfileRaptorPort.TemporalPlan plan, String digest
	) {
		var algorithm = plan instanceof JourneyProfileRaptorPort.DepartureWindowPlan
			? JourneyRaptorPruningInventoryV1.FORWARD_RANGE_RAPTOR : JourneyRaptorPruningInventoryV1.REVERSE_RANGE_RAPTOR;
		var counts = JourneyRaptorPruningInventoryV1.activeRuleIds(algorithm).stream()
			.collect(java.util.stream.Collectors.toMap(id -> id, id -> 0L));
		return new JourneyProfileExecutionResult.Success(START, START.plusSeconds(60),
			new JourneyProfileExecutionResult.SourceIdentity("bundle", "c".repeat(64), "timetable", "access", 7),
			new JourneyProfileResourcePolicy.Identity("policy", "1.0.0", digest), plan,
			new JourneyRaptorPruningInventoryV1.CountSnapshot(query.requestId(), algorithm, counts));
	}

	static JourneyProfileResourcePolicy policy() {
		return new JourneyProfileResourcePolicy(new JourneyProfileResourcePolicy.Identity("policy", "1.0.0", "a".repeat(64)),
			Duration.ofHours(1), 2, 100, 8, 16, 16, Duration.ofHours(1), Duration.ofSeconds(2),
			Duration.ofSeconds(5), Duration.ofSeconds(8), 1, 2, 3, 4, 10);
	}

	static JourneyProfileRaptorPort.Itinerary itinerary(boolean verified) {
		return new JourneyProfileRaptorPort.Itinerary(DATE, START, START.plusSeconds(600), null, null,
			new JourneyProfileRaptorPort.ItineraryMetrics(0, 120, 20, 0, new JourneyProfileRaptorPort.NoTransfer()),
			java.util.List.of(
				new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.ENTRY, "origin", "board", 60, 10, false, verified, "VERIFIED"),
				new JourneyProfileRaptorPort.RideLeg("line", "trip", "destination", "board", "platform", START.plusSeconds(60), START.plusSeconds(540), null, null),
				new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.EXIT, "platform", "destination", 60, 10, false, verified, "VERIFIED")));
	}
}
