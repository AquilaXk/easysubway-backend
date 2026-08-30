package com.easysubway.journey.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.JourneyCandidate;
import com.easysubway.journey.application.JourneyExecutionResult;
import com.easysubway.journey.application.JourneyRequest;
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
		var first = new JourneyCandidate(
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
				new JourneyCandidate.Ride(
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
		);
		var second = new JourneyCandidate(
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
			List.of(new JourneyCandidate.Ride(
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
		);
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
			      "legs":[
			        {"type":"ENTRY","fromStationId":"station-origin","durationSeconds":30},
			        {
			          "type":"RIDE",
			          "lineId":"line-1",
			          "tripId":"trip-1",
			          "directionStationId":"station-direction",
			          "fromStationId":"station-origin",
			          "toStationId":"station-transfer-a",
			          "plannedDepartureTime":"2026-08-12T00:01:00Z",
			          "plannedArrivalTime":"2026-08-12T00:04:30Z",
			          "realtimeDepartureTime":null,
			          "realtimeArrivalTime":null
			        },
			        {"type":"TRANSFER","fromStationId":"station-transfer-a","toStationId":"station-transfer-b","durationSeconds":45},
			        {"type":"EXIT","fromStationId":"station-destination","durationSeconds":20}
			      ]
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
			      "legs":[{
			        "type":"RIDE",
			        "lineId":"line-2",
			        "tripId":"trip-2",
			        "directionStationId":"station-direction-2",
			        "fromStationId":"station-origin",
			        "toStationId":"station-destination",
			        "plannedDepartureTime":"2026-08-12T00:02:00Z",
			        "plannedArrivalTime":"2026-08-12T00:07:00Z",
			        "realtimeDepartureTime":null,
			        "realtimeArrivalTime":null
			      }]
			    }
			  ]
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
		var journey = new JourneyCandidate(
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
			List.of(new JourneyCandidate.Ride(
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
		var journeys = List.of(new JourneyCandidate(
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
			new JourneyExecutionResult.BoundaryObservation(
				timePolicy == JourneyRequest.TimePolicy.REALTIME_REQUIRED
					? JourneyExecutionResult.BoundaryObservation.Status.UNOBSERVABLE
					: JourneyExecutionResult.BoundaryObservation.Status.OBSERVED,
				0L, 0L, 0L, 0L)
		);
	}
}
