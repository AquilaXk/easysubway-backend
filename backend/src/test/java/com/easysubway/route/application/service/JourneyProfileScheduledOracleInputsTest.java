package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendarDate;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitFrequency;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class JourneyProfileScheduledOracleInputsTest {
	private static final LocalDate ACTIVE_DATE = LocalDate.of(2024, 1, 1);

	@Test
	void includesOnlyTripsActiveOnTheExplicitServiceDate() {
		var source = source(List.of(new ServiceCalendarDate("daily", ACTIVE_DATE.plusDays(1), 2)), List.of());
		assertThat(JourneyProfileScheduledOracleInputs.rides(source, ACTIVE_DATE, 3)).hasSize(3);
		assertThat(JourneyProfileScheduledOracleInputs.rides(source, ACTIVE_DATE.plusDays(1), 3)).isEmpty();
	}

	@Test
	void preservesAbsoluteTimesBeyondTwentyFourHours() {
		var rides = JourneyProfileScheduledOracleInputs.rides(source(List.of(), List.of()), ACTIVE_DATE, 3);
		assertThat(rides.getFirst().departureAt()).isEqualTo(Instant.parse("2024-01-01T16:00:00Z"));
		assertThat(rides.getFirst().arrivalAt()).isEqualTo(Instant.parse("2024-01-01T16:01:00Z"));
	}

	@Test
	void retainsPickupAndDropOffFactsForIntermediatePairs() {
		var rides = JourneyProfileScheduledOracleInputs.rides(source(List.of(), List.of()), ACTIVE_DATE, 3);
		assertThat(rides).extracting(JourneyProfileExactOracle.Ride::identity).doesNotHaveDuplicates();
		assertThat(rides.get(0).pickupAllowed()).isTrue();
		assertThat(rides.get(0).dropOffAllowed()).isFalse();
		assertThat(rides.get(1).pickupAllowed()).isTrue();
		assertThat(rides.get(1).dropOffAllowed()).isTrue();
		assertThat(rides.get(2).pickupAllowed()).isFalse();
		assertThat(rides.get(2).dropOffAllowed()).isTrue();
	}

	@Test
	void rejectsFrequencyBasedInputs() {
		var frequency = new TransitFrequency("trip", 0, 60, 30, true);
		assertThatThrownBy(() -> JourneyProfileScheduledOracleInputs.rides(source(List.of(), List.of(frequency)), ACTIVE_DATE, 3))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("frequency");
	}

	@Test
	void rejectsACompleteInputThatExceedsItsExplicitRideBound() {
		assertThatThrownBy(() -> JourneyProfileScheduledOracleInputs.rides(source(List.of(), List.of()), ACTIVE_DATE, 2))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("budget");
	}

	private static RouteTimetable source(List<ServiceCalendarDate> dates, List<TransitFrequency> frequencies) {
		return new RouteTimetable(
			List.of(new ServiceCalendar("daily", true, true, true, true, true, true, true,
				ACTIVE_DATE, ACTIVE_DATE.plusDays(2), "Asia/Seoul")),
			dates,
			List.of(new TransitRoute("route", "line", "line", "line", "down", "Asia/Seoul")),
			List.of(new TransitTrip("trip", "route", "daily", "destination", "down", "LOCAL", 0)),
			List.of(new TransitStopTime("trip", 1, "a", "line", 25 * 3600, 25 * 3600, 0, 0),
				new TransitStopTime("trip", 2, "b", "line", 25 * 3600 + 60, 25 * 3600 + 60, 1, 1),
				new TransitStopTime("trip", 3, "c", "line", 25 * 3600 + 120, 25 * 3600 + 120, 1, 0)),
			frequencies);
	}
}
