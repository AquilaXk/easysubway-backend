package com.easysubway.journey.adapter.in.web;

import com.easysubway.journey.application.TestRides;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.JourneyCandidate;
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
		// #469: 프로필 응답 여정은 objectiveTags를 쓰므로 검색 전용 alternativeCategories 키가 없다.
		assertThat(json.path("journeys").get(0).path("journey").has("alternativeCategories")).isFalse();
		assertThat(json.path("journeys").get(0).has("objectiveTags")).isTrue();
	}

	@Test
	void carriesRealServicePatternAndServedStopsOfExpressRideIntoProfileJourney() {
		var query = query(new JourneyRaptorQuery.DepartBetween(START, START.plusSeconds(60)));
		var original = itinerary(true);
		var legs = new java.util.ArrayList<>(original.legs());
		legs.set(0, new JourneyProfileRaptorPort.RideLeg("line", "trip", "interchange", "origin", "interchange",
			"EXPRESS", START.plusSeconds(60), START.plusSeconds(240), null, null,
			java.util.List.of(
				new JourneyCandidate.Stop("origin", null, START.plusSeconds(60), null, null),
				new JourneyCandidate.Stop("mid-served", START.plusSeconds(120), START.plusSeconds(150), null, null),
				new JourneyCandidate.Stop("interchange", START.plusSeconds(240), null, null, null))));
		var express = new JourneyProfileRaptorPort.Itinerary(DATE, START, START.plusSeconds(600),
			null, null, original.metrics(), original.fare(), legs);
		var plan = new JourneyProfileRaptorPort.DepartureWindowPlan(
			(JourneyRaptorQuery.DepartBetween) query.temporalQuery(),
			java.util.List.of(new JourneyProfileRaptorPort.DeparturePoint(DATE, START,
				java.util.List.of(express), new JourneyRaptorPort.ScanMetrics(1, 1, 1))));

		var json = JourneyProfileResponseMapper.map(query, success(query, plan), policy(), "express-query");

		var ride = json.path("journeys").get(0).path("journey").path("legs").get(0);
		assertThat(ride.path("type").asText()).isEqualTo("RIDE");
		assertThat(ride.path("servicePattern").asText()).isEqualTo("EXPRESS");
		assertThat(ride.path("stops")).hasSize(3);
		assertThat(ride.path("stops").get(0).path("stationId").asText()).isEqualTo("origin");
		assertThat(ride.path("stops").get(0).path("plannedArrivalTime").isNull()).isTrue();
		assertThat(ride.path("stops").get(1).path("stationId").asText()).isEqualTo("mid-served");
		assertThat(ride.path("stops").get(1).path("plannedArrivalTime").asText()).isEqualTo(START.plusSeconds(120).toString());
		assertThat(ride.path("stops").get(1).path("plannedDepartureTime").asText()).isEqualTo(START.plusSeconds(150).toString());
		assertThat(ride.path("stops").get(2).path("stationId").asText()).isEqualTo("interchange");
		assertThat(ride.path("stops").get(2).path("plannedDepartureTime").isNull()).isTrue();
	}

	@Test
	void carriesTheProfileItineraryOfficialFareIntoTheJourneyAndOmitsAmountsWhenUnavailable() {
		var query = query(new JourneyRaptorQuery.DepartBetween(START, START.plusSeconds(60)));
		var official = JourneyCandidate.Fare.available(
			1950, 2050, 1220, 2050, 750, 750, java.util.List.of("seoul-metro-official-od-fares-20260712"));
		var quoted = new JourneyProfileRaptorPort.DepartureWindowPlan(
			(JourneyRaptorQuery.DepartBetween) query.temporalQuery(),
			java.util.List.of(new JourneyProfileRaptorPort.DeparturePoint(DATE, START,
				java.util.List.of(itinerary(true, official)), new JourneyRaptorPort.ScanMetrics(1, 1, 1))));
		var unquoted = new JourneyProfileRaptorPort.DepartureWindowPlan(
			(JourneyRaptorQuery.DepartBetween) query.temporalQuery(),
			java.util.List.of(new JourneyProfileRaptorPort.DeparturePoint(DATE, START,
				java.util.List.of(itinerary(true)), new JourneyRaptorPort.ScanMetrics(1, 1, 1))));

		var fare = JourneyProfileResponseMapper.map(query, success(query, quoted), policy(), "fare-query")
			.path("journeys").get(0).path("journey").path("fare");
		var unavailable = JourneyProfileResponseMapper.map(query, success(query, unquoted), policy(), "no-fare-query")
			.path("journeys").get(0).path("journey").path("fare");

		assertThat(fare.path("status").asText()).isEqualTo("AVAILABLE");
		assertThat(fare.path("adultCardWon").intValue()).isEqualTo(1950);
		assertThat(fare.path("adultCashWon").intValue()).isEqualTo(2050);
		assertThat(fare.path("youthCardWon").intValue()).isEqualTo(1220);
		assertThat(fare.path("youthCashWon").intValue()).isEqualTo(2050);
		assertThat(fare.path("childCardWon").intValue()).isEqualTo(750);
		assertThat(fare.path("childCashWon").intValue()).isEqualTo(750);
		assertThat(fare.path("sourceSnapshotIds")).hasSize(1);
		assertThat(fare.path("sourceSnapshotIds").get(0).asText()).isEqualTo("seoul-metro-official-od-fares-20260712");
		var unavailableFields = new java.util.HashSet<String>();
		unavailable.fieldNames().forEachRemaining(unavailableFields::add);
		assertThat(unavailableFields).containsExactlyInAnyOrder("status", "sourceSnapshotIds");
		assertThat(unavailable.path("status").asText()).isEqualTo("UNAVAILABLE");
		assertThat(unavailable.path("sourceSnapshotIds")).isEmpty();
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
		legs.set(0, TestRides.profileRide("line", "late-trip", "interchange", "origin", "interchange",
			ready.plusSeconds(60), ready.plusSeconds(240), null, null));
		legs.set(2, TestRides.profileRide("line-b", "late-trip-b", "destination", "interchange", "destination",
			ready.plusSeconds(360), ready.plusSeconds(540), null, null));
		var late = new JourneyProfileRaptorPort.Itinerary(DATE, ready, ready.plusSeconds(600),
			null, null, original.metrics(), original.fare(), legs);
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
		stairs.set(1, new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.TRANSFER,
			"interchange", "interchange", 60, 10, true, true, "VERIFIED"));
		var invalidItineraries = java.util.List.of(
			new JourneyProfileRaptorPort.Itinerary(DATE, START, START.plusSeconds(600), START, START.plusSeconds(600),
				valid.metrics(), valid.fare(), valid.legs()),
			new JourneyProfileRaptorPort.Itinerary(DATE, START, START.plusSeconds(600), null, null,
				valid.metrics(), valid.fare(), stairs));

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

	@Test
	void mapsReasonCodesFromTheThreeStairAccessStates() {
		// #503: 계단 없음 확정 / 계단 확정 / 계단 미확정 환승이 각각 다른 reasonCodes를 낸다.
		var temporal = new JourneyRaptorQuery.ArriveBy(START, START.plusSeconds(600));
		var query = new JourneyRaptorQuery("01ARZ3NDEKTSV4RRFFQ69G5FAV", "origin", "destination", temporal,
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 1, 1,
			(BooleanSupplier) () -> false);
		var base = itinerary(true);
		var cases = java.util.Map.of(
			"ACCESSIBILITY_VERIFIED", transfer(false, false),
			"ACCESSIBILITY_STAIRS_INCLUDED", transfer(true, false),
			"ACCESSIBILITY_UNDETERMINED", transfer(true, true));
		cases.forEach((expected, leg) -> {
			var legs = new java.util.ArrayList<>(base.legs());
			legs.set(1, leg);
			var itinerary = new JourneyProfileRaptorPort.Itinerary(DATE, START, START.plusSeconds(540), null, null,
				base.metrics(), base.fare(), legs);
			var plan = new JourneyProfileRaptorPort.ArriveByPlan((JourneyRaptorQuery.ArriveBy) query.temporalQuery(),
				new JourneyProfileRaptorPort.ReversePlan.Found(java.util.List.of(itinerary)));
			var accessibility = JourneyProfileResponseMapper.map(query, success(query, plan), policy(), "reason")
				.path("journeys").get(0).path("journey").path("accessibility");
			assertThat(accessibility.path("reasonCodes")).extracting(node -> node.asText()).containsExactly(expected);
			assertThat(accessibility.path("stairFree").asBoolean()).isEqualTo(expected.equals("ACCESSIBILITY_VERIFIED"));
		});
	}

	private static JourneyProfileRaptorPort.AccessLeg transfer(boolean stairs, boolean unconfirmed) {
		return new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.TRANSFER, "interchange",
			"interchange", 60, 10, stairs, true, "VERIFIED", null, null, null, unconfirmed);
	}

	private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");

	@Test
	void mapsPlatformBoundaryJourneysWithoutEntryOrExitLegsAndRejectsOtherBoundaries() {
		// #454: 응답 여정은 출발역 승강장의 승차로 시작해 도착역 승강장의 승차 종료로 끝난다.
		var query = query(new JourneyRaptorQuery.ArriveBy(START, START.plusSeconds(600)));
		var journey = JourneyProfileResponseMapper.map(query, success(query, new JourneyProfileRaptorPort.ArriveByPlan(
			(JourneyRaptorQuery.ArriveBy) query.temporalQuery(),
			new JourneyProfileRaptorPort.ReversePlan.Found(java.util.List.of(itinerary(true))))), policy(), "platform")
			.path("journeys").get(0).path("journey");
		assertThat(journey.path("legs")).extracting(leg -> leg.path("type").asText())
			.containsExactly("RIDE", "TRANSFER", "RIDE");

		var valid = itinerary(true);
		var wrongOrigin = new java.util.ArrayList<>(valid.legs());
		wrongOrigin.set(0, TestRides.profileRide("line", "trip", "interchange", "elsewhere", "interchange",
			START.plusSeconds(60), START.plusSeconds(240), null, null));
		var wrongDestination = new java.util.ArrayList<>(valid.legs());
		wrongDestination.set(2, TestRides.profileRide("line-b", "trip-b", "elsewhere", "interchange", "elsewhere",
			START.plusSeconds(360), START.plusSeconds(540), null, null));
		var endsWithTransfer = new java.util.ArrayList<>(valid.legs().subList(0, 2));
		var startsWithTransfer = new java.util.ArrayList<>(valid.legs().subList(1, 3));
		for (var legs : java.util.List.of(wrongOrigin, wrongDestination, endsWithTransfer, startsWithTransfer)) {
			var invalid = new JourneyProfileRaptorPort.Itinerary(DATE, START, START.plusSeconds(540), null, null,
				valid.metrics(), valid.fare(), legs);
			var plan = new JourneyProfileRaptorPort.ArriveByPlan((JourneyRaptorQuery.ArriveBy) query.temporalQuery(),
				new JourneyProfileRaptorPort.ReversePlan.Found(java.util.List.of(invalid)));
			assertThatThrownBy(() -> JourneyProfileResponseMapper.map(query, success(query, plan), policy(), "invalid"))
				.isInstanceOf(JourneyProfileResponseMapper.MappingException.class);
		}
	}

	@Test
	void preservesVerifiedTransferChainsAndRejectsDisconnectedTransfers() {
		var query = query(new JourneyRaptorQuery.ArriveBy(START, START.plusSeconds(600)));
		// #454: 출발역 승강장에서 타고 도착역 승강장에서 내린다(진입·하차 구간 없음).
		var legs = new java.util.ArrayList<JourneyProfileRaptorPort.Leg>(java.util.List.of(
			TestRides.profileRide("line-a", "trip-a", "interchange", "origin", "interchange",
				START.plusSeconds(60), START.plusSeconds(240), null, null),
			new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.TRANSFER,
				"interchange", "next-board", 60, 10, false, true, "VERIFIED"),
			TestRides.profileRide("line-b", "trip-b", "destination", "next-board", "destination",
				START.plusSeconds(360), START.plusSeconds(540), null, null)));
		var metrics = new JourneyProfileRaptorPort.ItineraryMetrics(1, 60, 10, 0,
			new JourneyProfileRaptorPort.MinimumTransferSeconds(60));
		var itinerary = new JourneyProfileRaptorPort.Itinerary(DATE, START, START.plusSeconds(600),
			null, null, metrics, JourneyCandidate.Fare.unavailable(), legs);
		var plan = new JourneyProfileRaptorPort.ArriveByPlan((JourneyRaptorQuery.ArriveBy) query.temporalQuery(),
			new JourneyProfileRaptorPort.ReversePlan.Found(java.util.List.of(itinerary)));
		var journey = JourneyProfileResponseMapper.map(query, success(query, plan), policy(), "transfer-query")
			.path("journeys").get(0).path("journey");
		assertThat(journey.path("transferCount").asInt()).isEqualTo(1);
		assertThat(journey.path("walkingDistanceMeters").asLong()).isEqualTo(10);
		assertThat(journey.path("legs").size()).isEqualTo(3);
		assertThat(journey.path("legs")).extracting(leg -> leg.path("type").asText())
			.containsExactly("RIDE", "TRANSFER", "RIDE");
		var transfer = journey.path("legs").get(1);
		assertThat(transfer.path("fromStationId").asText())
			.isEqualTo(journey.path("legs").get(0).path("toStationId").asText()).isEqualTo("interchange");
		assertThat(transfer.path("toStationId").asText())
			.isEqualTo(journey.path("legs").get(2).path("fromStationId").asText()).isEqualTo("next-board");

		legs.set(1, new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.TRANSFER,
			"disconnected", "next-board", 60, 10, false, true, "VERIFIED"));
		var invalid = new JourneyProfileRaptorPort.Itinerary(DATE, START, START.plusSeconds(600),
			null, null, metrics, JourneyCandidate.Fare.unavailable(), legs);
		var invalidPlan = new JourneyProfileRaptorPort.ArriveByPlan((JourneyRaptorQuery.ArriveBy) query.temporalQuery(),
			new JourneyProfileRaptorPort.ReversePlan.Found(java.util.List.of(invalid)));
		assertThatThrownBy(() -> JourneyProfileResponseMapper.map(query, success(query, invalidPlan), policy(), "invalid"))
			.isInstanceOf(JourneyProfileResponseMapper.MappingException.class)
			.extracting(exception -> ((JourneyProfileResponseMapper.MappingException) exception).reason())
			.isEqualTo(JourneyProfileExecutionResult.Reason.RAPTOR_FAILED);
	}

	@Test
	void carriesOutOfStationTransferFareFieldsLikePointSearch() {
		var query = query(new JourneyRaptorQuery.ArriveBy(START, START.plusSeconds(600)));
		var metrics = new JourneyProfileRaptorPort.ItineraryMetrics(1, 60, 10, 0,
			new JourneyProfileRaptorPort.MinimumTransferSeconds(60));
		var itinerary = new JourneyProfileRaptorPort.Itinerary(DATE, START, START.plusSeconds(600),
			null, null, metrics, JourneyCandidate.Fare.unavailable(), java.util.List.of(
				TestRides.profileRide("line-a", "trip-a", "interchange", "origin", "interchange",
					START.plusSeconds(60), START.plusSeconds(240), null, null),
				new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.TRANSFER,
					"interchange", "next-board", 60, 10, false, true, "VERIFIED", "OUT_OF_STATION", true, 30),
				TestRides.profileRide("line-b", "trip-b", "destination", "next-board", "destination",
					START.plusSeconds(360), START.plusSeconds(540), null, null)));
		var plan = new JourneyProfileRaptorPort.ArriveByPlan((JourneyRaptorQuery.ArriveBy) query.temporalQuery(),
			new JourneyProfileRaptorPort.ReversePlan.Found(java.util.List.of(itinerary)));

		var transfer = JourneyProfileResponseMapper.map(query, success(query, plan), policy(), "out-of-station")
			.path("journeys").get(0).path("journey").path("legs").get(1);

		assertThat(transfer.path("type").asText()).isEqualTo("TRANSFER");
		assertThat(transfer.path("transferType").asText()).isEqualTo("OUT_OF_STATION");
		assertThat(transfer.path("farePenaltyApplies").isBoolean()).isTrue();
		assertThat(transfer.path("farePenaltyApplies").asBoolean()).isTrue();
		assertThat(transfer.path("transferLimitMinutes").asInt()).isEqualTo(30);
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
		return itinerary(verified, JourneyCandidate.Fare.unavailable());
	}

	/** #454: 출발역 승강장 → 승차 → 환승({@code verified}) → 승차 → 도착역 승강장. */
	static JourneyProfileRaptorPort.Itinerary itinerary(boolean verified, JourneyCandidate.Fare fare) {
		return new JourneyProfileRaptorPort.Itinerary(DATE, START, START.plusSeconds(540), null, null,
			new JourneyProfileRaptorPort.ItineraryMetrics(1, 60, 10, 0, new JourneyProfileRaptorPort.MinimumTransferSeconds(60)),
			fare,
			java.util.List.of(
				TestRides.profileRide("line", "trip", "interchange", "origin", "interchange", START.plusSeconds(60), START.plusSeconds(240), null, null),
				new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.TRANSFER, "interchange", "interchange", 60, 10, false, verified, "VERIFIED"),
				TestRides.profileRide("line-b", "trip-b", "destination", "interchange", "destination", START.plusSeconds(360), START.plusSeconds(540), null, null)));
	}
}
