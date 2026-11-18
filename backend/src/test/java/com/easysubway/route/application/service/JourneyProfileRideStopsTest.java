package com.easysubway.route.application.service;

import static com.easysubway.route.application.service.RouteTimetableRaptorPlannerRideStopsTest.DAILY;
import static com.easysubway.route.application.service.RouteTimetableRaptorPlannerRideStopsTest.accessData;
import static com.easysubway.route.application.service.RouteTimetableRaptorPlannerRideStopsTest.query;
import static com.easysubway.route.application.service.RouteTimetableRaptorPlannerRideStopsTest.route;
import static com.easysubway.route.application.service.RouteTimetableRaptorPlannerRideStopsTest.stop;
import static com.easysubway.route.application.service.RouteTimetableRaptorPlannerRideStopsTest.trip;
import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.JourneyCandidate;
import com.easysubway.journey.application.JourneyProfileRaptorPort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#429 프로필 경로 탑승 구간이 실제 운행 유형과 정차역을 전달한다")
class JourneyProfileRideStopsTest {

	@Test
	@DisplayName("EXPRESS 트립은 EXPRESS와 통과역을 뺀 정차역 목록으로 프로필 RideLeg에 실린다")
	void expressTripKeepsServicePatternAndServedStopsOnProfileRideLeg() {
		RouteTimetable timetable = new RouteTimetable(
			List.of(DAILY), List.of(), List.of(route("r1", "l1")), List.of(trip("t1", "r1", "EXPRESS")),
			List.of(
				stop("t1", 1, "station-a", "l1", 25200, 25200, 0, 0),
				stop("t1", 2, "station-b", "l1", 25380, 25380, 1, 1), // 통과
				stop("t1", 3, "station-c", "l1", 25560, 25620, 0, 0),
				stop("t1", 4, "station-d", "l1", 25800, 25800, 0, 0)),
			List.of(), List.of(), null,
			accessData(List.of("station-a:l1", "station-b:l1", "station-c:l1", "station-d:l1"), List.of()));

		var plan = new RouteTimetableRaptorPlanner().journeyItineraries(
			query("station-a", "station-d", Instant.parse("2026-07-05T21:50:00Z"), 0), timetable);

		var itinerary = JourneyProfileRaptorAdapter.itinerary(plan.itineraries().getFirst(), Map.of());
		var ride = itinerary.legs().stream()
			.filter(JourneyProfileRaptorPort.RideLeg.class::isInstance)
			.map(JourneyProfileRaptorPort.RideLeg.class::cast)
			.findFirst().orElseThrow();

		assertThat(ride.servicePattern()).isEqualTo("EXPRESS");
		assertThat(ride.stops()).containsExactly(
			new JourneyCandidate.Stop("station-a", null, Instant.parse("2026-07-05T22:00:00Z"), null, null),
			new JourneyCandidate.Stop("station-c", Instant.parse("2026-07-05T22:06:00Z"),
				Instant.parse("2026-07-05T22:07:00Z"), null, null),
			new JourneyCandidate.Stop("station-d", Instant.parse("2026-07-05T22:10:00Z"), null, null, null));
	}

	@Test
	@DisplayName("투영 탑승 구간도 운행 유형과 정차역을 명시적으로 받는 정규 생성자만 가진다")
	void rideProjectionExposesOnlyItsCanonicalConstructor() {
		assertThat(RouteTimetableRaptorPlanner.JourneyRideProjection.class.getDeclaredConstructors()).hasSize(1);
	}
}
