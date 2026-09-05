package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdate;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdates;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#308 compiled departure-event index")
class RouteTimetableRaptorPlannerDepartureEventIndexTest {

	private static final LocalDate WEDNESDAY = LocalDate.of(2026, 7, 1);
	private final RouteTimetableRaptorPlanner planner = new RouteTimetableRaptorPlanner();

	@Test
	@DisplayName("active service day origin events use pinned realtime and preserve canonical identity")
	void indexesBoardableEventsLatestFirstWithinInclusiveBounds() {
		var compiled = planner.compile(timetable());
		var activeDay = compiled.activeServiceDay(WEDNESDAY);
		var overlay = planner.compileRealtimeOverlay(compiled, new TimetableRealtimeUpdates(
			"realtime-v1", true, List.of(
				new TimetableRealtimeUpdate("trip-scheduled", 30, 30, false, "snapshot", Instant.EPOCH),
				new TimetableRealtimeUpdate("trip-cancelled", 0, 0, true, "snapshot", Instant.EPOCH)
			), null));

		var events = planner.departureEvents(activeDay, "station-a", 32400, 87000, overlay);

		assertThat(events)
			.extracting(RouteTimetableRaptorPlanner.DepartureEvent::effectiveDepartureSeconds)
			.containsExactly(87000, 86400, 85800, 33030, 33000, 33000, 32400);
		assertThat(events)
			.extracting(event -> event.scheduledTrip().trip().id())
			.containsExactly(
				"trip-overnight-frequency", "trip-overnight-frequency", "trip-overnight-frequency", "trip-scheduled",
				"trip-frequency", "trip-tie", "trip-frequency");
		assertThat(events)
			.extracting(RouteTimetableRaptorPlanner.DepartureEvent::stopIndex)
			.containsOnly(0);
		assertThat(events)
			.allSatisfy(event -> assertThat(event.scheduledTrip())
				.isSameAs(compiled.scheduledTrip(event.scheduledTrip().index())));
		assertThat(events.stream()
			.map(RouteTimetableRaptorPlanner.DepartureEvent::scheduledTrip)
			.map(trip -> trip.trip().id()))
			.doesNotContain("trip-pickup-forbidden", "trip-cancelled", "trip-weekend-only");
	}

	@Test
	@DisplayName("departure-event bounds are inclusive and invalid bounds are rejected")
	void appliesInclusiveBoundsWithoutCrossingTheCapturedServiceDay() {
		var compiled = planner.compile(timetable());
		var events = planner.departureEvents(
			compiled.activeServiceDay(WEDNESDAY), "station-a", 86400, 86400,
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty());

		assertThat(events).singleElement().satisfies(event -> {
			assertThat(event.effectiveDepartureSeconds()).isEqualTo(86400);
			assertThat(event.scheduledTrip().trip().id()).isEqualTo("trip-overnight-frequency");
		});
		assertThat(planner.departureEvents(
			compiled.activeServiceDay(WEDNESDAY), "station-a", 87001, 88000,
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty())).isEmpty();
		org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() ->
			planner.departureEvents(
				compiled.activeServiceDay(WEDNESDAY), "station-a", 10, 9,
				RouteTimetableRaptorPlanner.RealtimeOverlay.empty()));
	}

	private static RouteTimetable timetable() {
		return new RouteTimetable(
			List.of(calendar("weekday", true, true, true, true, true, false, false),
				calendar("weekend", false, false, false, false, false, true, true)),
			List.of(),
			List.of(new LoadRouteTimetablePort.TransitRoute(
				"route", "line", "L", "Test", "Terminal", "Asia/Seoul")),
			List.of(
				trip("trip-scheduled", "weekday"),
				trip("trip-frequency", "weekday"),
				trip("trip-overnight-frequency", "weekday"),
				trip("trip-tie", "weekday"),
				trip("trip-pickup-forbidden", "weekday"),
				trip("trip-cancelled", "weekday"),
				trip("trip-weekend-only", "weekend")
			),
			List.of(
				stop("trip-scheduled", 1, "station-a", 33000, 0), stop("trip-scheduled", 2, "station-b", 33600, 0),
				stop("trip-frequency", 1, "station-a", 32400, 0), stop("trip-frequency", 2, "station-b", 33000, 0),
				stop("trip-overnight-frequency", 1, "station-a", 85800, 0),
				stop("trip-overnight-frequency", 2, "station-b", 86400, 0),
				stop("trip-tie", 1, "station-a", 33000, 0), stop("trip-tie", 2, "station-b", 33600, 0),
				stop("trip-pickup-forbidden", 1, "station-a", 34000, 1), stop("trip-pickup-forbidden", 2, "station-b", 34600, 0),
				stop("trip-cancelled", 1, "station-a", 35000, 0), stop("trip-cancelled", 2, "station-b", 35600, 0),
				stop("trip-weekend-only", 1, "station-a", 36000, 0), stop("trip-weekend-only", 2, "station-b", 36600, 0)
			),
			List.of(
				new LoadRouteTimetablePort.TransitFrequency("trip-frequency", 32400, 33600, 600, false),
				new LoadRouteTimetablePort.TransitFrequency("trip-overnight-frequency", 85800, 87600, 600, false)
			)
		);
	}

	private static LoadRouteTimetablePort.ServiceCalendar calendar(
		String serviceId, boolean monday, boolean tuesday, boolean wednesday, boolean thursday,
		boolean friday, boolean saturday, boolean sunday
	) {
		return new LoadRouteTimetablePort.ServiceCalendar(
			serviceId, monday, tuesday, wednesday, thursday, friday, saturday, sunday,
			WEDNESDAY.minusDays(7), WEDNESDAY.plusDays(7), "Asia/Seoul");
	}

	private static LoadRouteTimetablePort.TransitTrip trip(String id, String serviceId) {
		return new LoadRouteTimetablePort.TransitTrip(id, "route", serviceId, "Terminal", "0", "LOCAL", 0);
	}

	private static LoadRouteTimetablePort.TransitStopTime stop(
		String tripId, int sequence, String stationId, int seconds, int pickupType
	) {
		return new LoadRouteTimetablePort.TransitStopTime(
			tripId, sequence, stationId, "line", seconds, seconds, pickupType, 0);
	}
}
