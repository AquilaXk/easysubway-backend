package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class JourneyRaptorQueryTest {

	private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
	private static final Instant CAPTURED = Instant.parse("2026-09-02T00:00:00Z");

	@Test
	void convertsBothPointRequestFormsToTheCapturedDepartureInstant() {
		JourneyRaptorQuery now = JourneyRaptorQuery.from(request(new JourneyRequest.Departure.Now()), CAPTURED);
		JourneyRaptorQuery scheduled = JourneyRaptorQuery.from(
			request(new JourneyRequest.Departure.Scheduled(CAPTURED.minusSeconds(60))), CAPTURED);

		assertThat(now.temporalQuery()).isEqualTo(new JourneyRaptorQuery.DepartAt(CAPTURED));
		assertThat(scheduled.temporalQuery()).isEqualTo(new JourneyRaptorQuery.DepartAt(CAPTURED));
		assertThat(now.isPointQuery()).isTrue();
		assertThat(scheduled.isPointQuery()).isTrue();
	}

	@Test
	void rejectsInvalidClosedTemporalFormsAndJourneyInvariants() {
		assertThatThrownBy(() -> new JourneyRaptorQuery.DepartAt(null))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new JourneyRaptorQuery.DepartBetween(CAPTURED, CAPTURED))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("later");
		assertThatThrownBy(() -> new JourneyRaptorQuery.DepartBetween(
			CAPTURED.plusNanos(1), CAPTURED.plusSeconds(1)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("DEPART_BETWEEN bounds must use whole seconds");
		assertThatThrownBy(() -> new JourneyRaptorQuery.ArriveBy(CAPTURED, CAPTURED.minusSeconds(1)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("later");
		assertThatThrownBy(() -> new JourneyRaptorQuery.LastConnection(null))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new JourneyRaptorQuery(
			"not-a-ulid", "station-a", "station-b", new JourneyRaptorQuery.DepartAt(CAPTURED),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("requestId");
		assertThatThrownBy(() -> new JourneyRaptorQuery(
			REQUEST_ID, "station-a", "station-b", new JourneyRaptorQuery.DepartAt(CAPTURED),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.NO_STAIRS, JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("NO_STAIRS");
	}

	@Test
	void retainsNonPointFormsForFutureExplicitRejection() {
		assertThat(query(new JourneyRaptorQuery.DepartBetween(CAPTURED, CAPTURED.plusSeconds(1))).isPointQuery())
			.isFalse();
		assertThat(query(new JourneyRaptorQuery.ArriveBy(CAPTURED, CAPTURED.plusSeconds(1))).isPointQuery())
			.isFalse();
		assertThat(query(new JourneyRaptorQuery.LastConnection(LocalDate.of(2026, 9, 2))).isPointQuery())
			.isFalse();
	}

	private static JourneyRaptorQuery query(JourneyRaptorQuery.TemporalQuery temporalQuery) {
		return new JourneyRaptorQuery(
			REQUEST_ID, "station-a", "station-b", temporalQuery,
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false);
	}

	private static JourneyRequest request(JourneyRequest.Departure departure) {
		return new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", departure,
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false);
	}
}
