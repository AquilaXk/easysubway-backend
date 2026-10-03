package com.easysubway.journey.adapter.in.web;

import com.easysubway.journey.application.TestJourneyCandidates;
import com.easysubway.journey.application.TestRides;
import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.JourneyAlternatives;
import com.easysubway.journey.application.JourneyCandidate;
import com.easysubway.journey.application.JourneyExecutionResult;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.GapGrade;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.HeightDiffGrade;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PlatformGap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class JourneySearchResponseMapperTest {

	private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
	private static final String REQUEST_ID = "01K1Y000000000000000000000";
	private static final Instant CALCULATED_AT = Instant.parse("2026-08-12T00:00:00Z");
	private static final Instant VALID_UNTIL = Instant.parse("2026-08-12T00:05:00Z");
	private static final Instant EFFECTIVE_DEPARTURE = Instant.parse("2026-08-12T00:01:00Z");
	private static final Instant PLANNED_DEPARTURE = Instant.parse("2026-08-12T00:01:00Z");
	private static final Instant PLANNED_ARRIVAL = Instant.parse("2026-08-12T00:06:00Z");

	@Test
	void mapsOrderedTimetableSuccessAndAllFourLegsToExactWireShape() throws Exception {
		var first = TestJourneyCandidates.unavailableFare(
			"journey-first",
			PLANNED_DEPARTURE,
			PLANNED_ARRIVAL,
			null,
			null,
			300,
			1,
			75,
			JourneyCandidate.TimeSource.TIMETABLE,
			new JourneyCandidate.Accessibility(true, List.of("STEP_FREE_PATH")),
			List.of(
				new JourneyCandidate.Entry("station-origin", 30),
				TestRides.candidateRide(
					"line-1",
					"trip-1",
					"station-direction",
					"station-origin",
					"station-transfer-a",
					PLANNED_DEPARTURE,
					PLANNED_ARRIVAL.minusSeconds(90),
					null,
					null
				),
				new JourneyCandidate.Transfer("station-transfer-a", "station-transfer-b", 45),
				new JourneyCandidate.Exit("station-destination", 20)
			)
		).withAlternativeCategories(List.of(JourneyAlternatives.Category.FASTEST, JourneyAlternatives.Category.STAIR_FREE));
		var second = TestJourneyCandidates.unavailableFare(
			"journey-second",
			PLANNED_DEPARTURE.plusSeconds(60),
			PLANNED_ARRIVAL.plusSeconds(60),
			null,
			null,
			300,
			0,
			20,
			JourneyCandidate.TimeSource.TIMETABLE,
			new JourneyCandidate.Accessibility(false, List.of("STAIRS_PRESENT")),
			List.of(TestRides.candidateRide(
				"line-2",
				"trip-2",
				"station-direction-2",
				"station-origin",
				"station-destination",
				PLANNED_DEPARTURE.plusSeconds(60),
				PLANNED_ARRIVAL.plusSeconds(60),
				null,
				null
			))
		).withAlternativeCategories(List.of(JourneyAlternatives.Category.FEWEST_TRANSFERS));
		var success = success(
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			null,
			List.of(first, second)
		);

		JsonNode actual = JSON.valueToTree(JourneySearchResponseMapper.map(success));
		JsonNode expected = JSON.readTree("""
			{
			  "contractVersion":"JOURNEY_SEARCH_V3",
			  "requestId":"01K1Y000000000000000000000",
			  "queryId":"query-1",
			  "calculatedAt":"2026-08-12T00:00:00Z",
			  "validUntil":"2026-08-12T00:05:00Z",
			  "effectiveDepartureTime":"2026-08-12T00:01:00Z",
			  "serviceDate":"2026-08-12",
			  "serviceTimezone":"Asia/Seoul",
			  "serviceDayCutoff":"03:00",
			  "sourceIdentity":{
			    "routeBundleId":"bundle-1",
			    "routeBundleSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			    "timetableSnapshotId":"timetable-1",
			    "accessibilitySnapshotId":"accessibility-1",
			    "realtimeSnapshotId":null
			  },
			  "requestPolicy":{
			    "timePolicy":"TIMETABLE_REQUIRED",
			    "walkingPace":"STANDARD",
			    "mobilityProfile":"STEP_FREE",
			    "constraintMode":"REQUIRE_STEP_FREE",
			    "maxTransfers":3,
			    "alternativeCount":2
			  },
			  "journeys":[
			    {
			      "journeyId":"journey-first",
			      "status":"FOUND",
			      "planSource":"SERVER_TIMETABLE_RAPTOR",
			      "plannedDepartureTime":"2026-08-12T00:01:00Z",
			      "plannedArrivalTime":"2026-08-12T00:06:00Z",
			      "realtimeDepartureTime":null,
			      "realtimeArrivalTime":null,
			      "durationSeconds":300,
			      "transferCount":1,
			      "walkingDistanceMeters":75,
			      "timeSource":"TIMETABLE",
			      "accessibility":{"result":"VERIFIED","stairFree":true,"reasonCodes":["STEP_FREE_PATH"]},
			      "fare":{"status":"UNAVAILABLE","sourceSnapshotIds":[]},
			      "legs":[
			        {"type":"ENTRY","fromStationId":"station-origin","durationSeconds":30},
			        {
			          "type":"RIDE",
			          "lineId":"line-1",
			          "tripId":"trip-1",
			          "directionStationId":"station-direction",
			          "fromStationId":"station-origin",
			          "toStationId":"station-transfer-a",
			          "servicePattern":"LOCAL",
			          "plannedDepartureTime":"2026-08-12T00:01:00Z",
			          "plannedArrivalTime":"2026-08-12T00:04:30Z",
			          "realtimeDepartureTime":null,
			          "realtimeArrivalTime":null,
			          "stops":[
			            {"stationId":"station-origin","plannedArrivalTime":null,"plannedDepartureTime":"2026-08-12T00:01:00Z","realtimeArrivalTime":null,"realtimeDepartureTime":null},
			            {"stationId":"station-transfer-a","plannedArrivalTime":"2026-08-12T00:04:30Z","plannedDepartureTime":null,"realtimeArrivalTime":null,"realtimeDepartureTime":null}
			          ],
			          "alightingCarDoors":[],
			          "boardingPlatformGaps":[],
			          "alightingPlatformGaps":[]
			        },
			        {"type":"TRANSFER","fromStationId":"station-transfer-a","toStationId":"station-transfer-b","durationSeconds":45},
			        {"type":"EXIT","fromStationId":"station-destination","durationSeconds":20}
			      ],
			      "alternativeCategories":["FASTEST","STAIR_FREE"]
			    },
			    {
			      "journeyId":"journey-second",
			      "status":"FOUND",
			      "planSource":"SERVER_TIMETABLE_RAPTOR",
			      "plannedDepartureTime":"2026-08-12T00:02:00Z",
			      "plannedArrivalTime":"2026-08-12T00:07:00Z",
			      "realtimeDepartureTime":null,
			      "realtimeArrivalTime":null,
			      "durationSeconds":300,
			      "transferCount":0,
			      "walkingDistanceMeters":20,
			      "timeSource":"TIMETABLE",
			      "accessibility":{"result":"VERIFIED","stairFree":false,"reasonCodes":["STAIRS_PRESENT"]},
			      "fare":{"status":"UNAVAILABLE","sourceSnapshotIds":[]},
			      "legs":[{
			        "type":"RIDE",
			        "lineId":"line-2",
			        "tripId":"trip-2",
			        "directionStationId":"station-direction-2",
			        "fromStationId":"station-origin",
			        "toStationId":"station-destination",
			        "servicePattern":"LOCAL",
			        "plannedDepartureTime":"2026-08-12T00:02:00Z",
			        "plannedArrivalTime":"2026-08-12T00:07:00Z",
			        "realtimeDepartureTime":null,
			        "realtimeArrivalTime":null,
			        "stops":[
			        {"stationId":"station-origin","plannedArrivalTime":null,"plannedDepartureTime":"2026-08-12T00:02:00Z","realtimeArrivalTime":null,"realtimeDepartureTime":null},
			        {"stationId":"station-destination","plannedArrivalTime":"2026-08-12T00:07:00Z","plannedDepartureTime":null,"realtimeArrivalTime":null,"realtimeDepartureTime":null}
			        ],
			        "alightingCarDoors":[],
			        "boardingPlatformGaps":[],
			        "alightingPlatformGaps":[]
			      }],
			      "alternativeCategories":["FEWEST_TRANSFERS"]
			    }
			  ],
			  "stairFreeAlternative":{"status":"INCLUDED","facilityStatus":"UNOBSERVED"}
			}
			""");

		assertThat(actual.toString()).isEqualTo(expected.toString());
		assertThat(actual.path("journeys").findValuesAsText("journeyId"))
			.containsExactly("journey-first", "journey-second");
	}

	@Test
	void mapsRealtimeIdentityAndTimesWithoutTimetableSubstitution() throws Exception {
		Instant realtimeDeparture = PLANNED_DEPARTURE.plusSeconds(20);
		Instant realtimeArrival = PLANNED_ARRIVAL.plusSeconds(40);
		var journey = TestJourneyCandidates.unavailableFare(
			"journey-realtime",
			PLANNED_DEPARTURE,
			PLANNED_ARRIVAL,
			realtimeDeparture,
			realtimeArrival,
			320,
			0,
			10,
			JourneyCandidate.TimeSource.REALTIME,
			new JourneyCandidate.Accessibility(true, List.of()),
			List.of(TestRides.candidateRide(
				"line-1",
				"trip-1",
				"station-direction",
				"station-origin",
				"station-destination",
				PLANNED_DEPARTURE,
				PLANNED_ARRIVAL,
				realtimeDeparture,
				realtimeArrival
			))
		);

		JsonNode actual = JSON.valueToTree(JourneySearchResponseMapper.map(success(
			JourneyRequest.TimePolicy.REALTIME_REQUIRED,
			"realtime-1",
			List.of(journey)
		)));

		assertThat(actual.path("sourceIdentity").path("realtimeSnapshotId").asText())
			.isEqualTo("realtime-1");
		assertThat(actual.path("journeys").path(0).path("timeSource").asText())
			.isEqualTo("REALTIME");
		assertThat(actual.path("journeys").path(0).path("realtimeDepartureTime").asText())
			.isEqualTo("2026-08-12T00:01:20Z");
		assertThat(actual.path("journeys").path(0).path("legs").path(0).path("realtimeArrivalTime").asText())
			.isEqualTo("2026-08-12T00:06:40Z");
	}

	@Test
	void mapsSlowAndFastWalkingPacesToTheirWireValues() {
		var journeys = List.of(TestJourneyCandidates.unavailableFare(
			"journey-pace",
			PLANNED_DEPARTURE,
			PLANNED_ARRIVAL,
			null,
			null,
			300,
			0,
			0,
			JourneyCandidate.TimeSource.TIMETABLE,
			new JourneyCandidate.Accessibility(true, List.of()),
			List.of(new JourneyCandidate.Entry("station-origin", 30))
		));
		assertThat(JSON.valueToTree(JourneySearchResponseMapper.map(success(
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			null,
			journeys
		))).path("requestPolicy").path("walkingPace").asText()).isEqualTo("SLOW");
		assertThat(JSON.valueToTree(JourneySearchResponseMapper.map(success(
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.FAST,
			null,
			journeys
		))).path("requestPolicy").path("walkingPace").asText()).isEqualTo("FAST");
	}

	@Test
	void mapsAlightingCarDoorsToExactWireShape() {
		var journey = TestJourneyCandidates.unavailableFare(
			"journey-doors",
			PLANNED_DEPARTURE,
			PLANNED_ARRIVAL,
			null,
			null,
			300,
			0,
			10,
			JourneyCandidate.TimeSource.TIMETABLE,
			new JourneyCandidate.Accessibility(true, List.of()),
			List.of(new JourneyCandidate.Ride(
				"line-1",
				"trip-1",
				"station-direction",
				"station-origin",
				"station-destination",
				"LOCAL",
				PLANNED_DEPARTURE,
				PLANNED_ARRIVAL,
				null,
				null,
				List.of(
					new JourneyCandidate.Stop("station-origin", null, PLANNED_DEPARTURE, null, null),
					new JourneyCandidate.Stop("station-destination", PLANNED_ARRIVAL, null, null, null)
				),
				List.of(new JourneyCandidate.AlightingCarDoor(3, 2, "TRANSFER")),
				List.of(),
				List.of()
			))
		);
		JsonNode actual = JSON.valueToTree(JourneySearchResponseMapper.map(success(
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			null,
			List.of(journey)
		)));
		JsonNode doors = actual.path("journeys").path(0).path("legs").path(0).path("alightingCarDoors");
		assertThat(doors.isArray()).isTrue();
		assertThat(doors.size()).isEqualTo(1);
		assertThat(doors.get(0).path("carNumber").asInt()).isEqualTo(3);
		assertThat(doors.get(0).path("doorNumber").asInt()).isEqualTo(2);
		assertThat(doors.get(0).path("targetFacilityType").asText()).isEqualTo("TRANSFER");

		var resDoor = new JourneySearchResponseMapper.AlightingCarDoorResponse(3, 2, "TRANSFER");
		assertThat(resDoor.carNumber()).isEqualTo(3);
		assertThat(resDoor.doorNumber()).isEqualTo(2);
		assertThat(resDoor.targetFacilityType()).isEqualTo("TRANSFER");
		assertThat(resDoor).isEqualTo(new JourneySearchResponseMapper.AlightingCarDoorResponse(3, 2, "TRANSFER"));
		assertThat(resDoor.hashCode()).isNotZero();
		assertThat(resDoor.toString()).contains("carNumber=3");
	}

	@Test
	void mapsPlatformGapsToOfficialGradeWireShape() throws Exception {
		var boarding = List.of(
			new PlatformGap("본선 오이도 방면 1-3", 1, 3, GapGrade.WIDE, HeightDiffGrade.HIGH, true),
			new PlatformGap("본선 대야미 방면", null, null, GapGrade.NARROW, HeightDiffGrade.LOW, false));
		var alighting = List.of(
			new PlatformGap("2-1", 2, 1, GapGrade.NORMAL, HeightDiffGrade.NORMAL, false));
		var journey = TestJourneyCandidates.unavailableFare(
			"journey-gap",
			PLANNED_DEPARTURE,
			PLANNED_ARRIVAL,
			null,
			null,
			300,
			1,
			75,
			JourneyCandidate.TimeSource.TIMETABLE,
			new JourneyCandidate.Accessibility(true, List.of("STEP_FREE_PATH")),
			List.of(
				new JourneyCandidate.Entry("station-origin", 30),
				new JourneyCandidate.Ride(
					"line-1", "trip-1", "station-direction", "station-origin", "station-destination", "LOCAL",
					PLANNED_DEPARTURE, PLANNED_ARRIVAL, null, null,
					List.of(
						new JourneyCandidate.Stop("station-origin", null, PLANNED_DEPARTURE, null, null),
						new JourneyCandidate.Stop("station-destination", PLANNED_ARRIVAL, null, null, null)),
					List.of(), boarding, alighting),
				new JourneyCandidate.Exit("station-destination", 20)
			)
		);

		JsonNode root = JSON.readTree(JSON.writeValueAsString(JourneySearchResponseMapper.map(
			success(JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, null, List.of(journey))
		)));

		JsonNode rideLeg = root.path("journeys").get(0).path("legs").get(1);
		assertThat(rideLeg.path("boardingPlatformGaps")).isEqualTo(JSON.readTree("""
			[
			  {"platformPosition":"본선 오이도 방면 1-3","carNumber":1,"doorNumber":3,
			   "gapGrade":"WIDE","heightDiffGrade":"HIGH","curved":true},
			  {"platformPosition":"본선 대야미 방면","gapGrade":"NARROW","heightDiffGrade":"LOW","curved":false}
			]
			"""));
		assertThat(rideLeg.path("alightingPlatformGaps")).isEqualTo(JSON.readTree("""
			[{"platformPosition":"2-1","carNumber":2,"doorNumber":1,
			  "gapGrade":"NORMAL","heightDiffGrade":"NORMAL","curved":false}]
			"""));
	}

	@Test
	void mapsAvailableFareWithAllSixAmountsAndSnapshotIds() {
		var journey = new JourneyCandidate(
			"journey-fare",
			PLANNED_DEPARTURE,
			PLANNED_ARRIVAL,
			null,
			null,
			300,
			0,
			10,
			JourneyCandidate.TimeSource.TIMETABLE,
			new JourneyCandidate.Accessibility(true, List.of()),
			JourneyCandidate.Fare.available(1400, 1500, 800, 900, 500, 600, List.of("snap-1", "snap-2")),
			List.of(new JourneyCandidate.Entry("station-origin", 30)),
			List.of(com.easysubway.journey.application.JourneyAlternatives.Category.FASTEST,
				com.easysubway.journey.application.JourneyAlternatives.Category.FEWEST_TRANSFERS,
				com.easysubway.journey.application.JourneyAlternatives.Category.STAIR_FREE)
		);
		JsonNode actual = JSON.valueToTree(JourneySearchResponseMapper.map(success(
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			null,
			List.of(journey)
		))).path("journeys").path(0).path("fare");

		assertThat(actual.path("status").asText()).isEqualTo("AVAILABLE");
		assertThat(actual.path("adultCardWon").asInt()).isEqualTo(1400);
		assertThat(actual.path("adultCashWon").asInt()).isEqualTo(1500);
		assertThat(actual.path("youthCardWon").asInt()).isEqualTo(800);
		assertThat(actual.path("youthCashWon").asInt()).isEqualTo(900);
		assertThat(actual.path("childCardWon").asInt()).isEqualTo(500);
		assertThat(actual.path("childCashWon").asInt()).isEqualTo(600);
		assertThat(actual.path("sourceSnapshotIds")).extracting(JsonNode::asText)
			.containsExactly("snap-1", "snap-2");
	}

	private static JourneyExecutionResult.Success success(
		JourneyRequest.TimePolicy timePolicy,
		String realtimeSnapshotId,
		List<JourneyCandidate> journeys
	) {
		return success(timePolicy, JourneyRequest.WalkingPace.STANDARD, realtimeSnapshotId, journeys);
	}

	private static JourneyExecutionResult.Success success(
		JourneyRequest.TimePolicy timePolicy,
		JourneyRequest.WalkingPace walkingPace,
		String realtimeSnapshotId,
		List<JourneyCandidate> journeys
	) {
		return new JourneyExecutionResult.Success(
			REQUEST_ID,
			"query-1",
			CALCULATED_AT,
			VALID_UNTIL,
			EFFECTIVE_DEPARTURE,
			LocalDate.parse("2026-08-12"),
			1,
			new com.easysubway.journey.application.JourneyRaptorPort.ScanMetrics(1, 2, 3),
			new JourneyExecutionResult.SourceIdentity(
				"bundle-1",
				"a".repeat(64),
				"timetable-1",
				"accessibility-1",
				realtimeSnapshotId
			),
			new JourneyExecutionResult.RequestPolicy(
				timePolicy,
				walkingPace,
				JourneyRequest.MobilityProfile.STEP_FREE,
				JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE,
				3,
				journeys.size()
			),
			journeys,
			timePolicy == JourneyRequest.TimePolicy.REALTIME_REQUIRED
				? JourneyExecutionResult.SafetyBoundary.unobservable()
				: JourneyExecutionResult.SafetyBoundary.observed(), TestJourneyCandidates.stairFreeAlternative(journeys)
		);
	}
}
