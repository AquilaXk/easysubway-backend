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

	private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
	private static final LocalDate DATE = LocalDate.of(2026, 9, 1);

	private static JourneyRaptorQuery query(JourneyRaptorQuery.TemporalQuery temporal) {
		return new JourneyRaptorQuery("01ARZ3NDEKTSV4RRFFQ69G5FAV", "origin", "destination", temporal,
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STEP_FREE, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE, 1, 1,
			(BooleanSupplier) () -> false);
	}

	private static JourneyProfileExecutionResult.Success success(
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

	private static JourneyProfileResourcePolicy policy() {
		return new JourneyProfileResourcePolicy(new JourneyProfileResourcePolicy.Identity("policy", "1.0.0", "a".repeat(64)),
			Duration.ofHours(1), 2, 100, 8, 16, 16, Duration.ofHours(1), Duration.ofSeconds(2),
			Duration.ofSeconds(5), Duration.ofSeconds(8), 1, 2, 3, 4, 10);
	}

	private static JourneyProfileRaptorPort.Itinerary itinerary(boolean verified) {
		return new JourneyProfileRaptorPort.Itinerary(DATE, START, START.plusSeconds(600), null, null,
			new JourneyProfileRaptorPort.ItineraryMetrics(0, 120, 20, 0, new JourneyProfileRaptorPort.NoTransfer()),
			java.util.List.of(
				new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.ENTRY, "origin", "board", 60, 10, false, verified, "VERIFIED"),
				new JourneyProfileRaptorPort.RideLeg("line", "trip", "destination", "board", "platform", START.plusSeconds(60), START.plusSeconds(540), null, null),
				new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.EXIT, "platform", "destination", 60, 10, false, verified, "VERIFIED")));
	}
}
