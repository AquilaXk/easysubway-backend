package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class JourneyRequestTest {

	private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
	private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

	@Test
	void constructsValidRequestWithoutViaStationId() {
		var req11 = new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", new JourneyRequest.Departure.Now(),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 2, () -> false);
		assertThat(req11.viaStationId()).isNull();
		assertThat(req11.isCancelled()).isFalse();

		var req12 = new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", null, new JourneyRequest.Departure.Now(),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 2, () -> false);
		assertThat(req12.viaStationId()).isNull();
	}

	@Test
	void constructsValidRequestWithViaStationId() {
		var req = new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", "station-via", new JourneyRequest.Departure.Now(),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 2, () -> false);
		assertThat(req.viaStationId()).isEqualTo("station-via");
	}

	@Test
	void rejectsInvalidViaStationId() {
		assertThatThrownBy(() -> new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", "   ", new JourneyRequest.Departure.Now(),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 2, () -> false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("viaStationId must not be blank");

		assertThatThrownBy(() -> new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", "station-a", new JourneyRequest.Departure.Now(),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 2, () -> false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("viaStationId cannot be originStationId or destinationStationId");

		assertThatThrownBy(() -> new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", "station-b", new JourneyRequest.Departure.Now(),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 2, () -> false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("viaStationId cannot be originStationId or destinationStationId");
	}

	@Test
	void rejectsInvalidCoreFields() {
		assertThatThrownBy(() -> new JourneyRequest(
			"invalid-ulid", "station-a", "station-b", new JourneyRequest.Departure.Now(),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 2, () -> false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("requestId");

		assertThatThrownBy(() -> new JourneyRequest(
			REQUEST_ID, "  ", "station-b", new JourneyRequest.Departure.Now(),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 2, () -> false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("originStationId");

		assertThatThrownBy(() -> new JourneyRequest(
			REQUEST_ID, "station-a", "  ", new JourneyRequest.Departure.Now(),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 2, () -> false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("destinationStationId");

		assertThatThrownBy(() -> new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", new JourneyRequest.Departure.Now(),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, -1, 2, () -> false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("maxTransfers");

		assertThatThrownBy(() -> new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", new JourneyRequest.Departure.Now(),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 4, 2, () -> false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("maxTransfers");

		assertThatThrownBy(() -> new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", new JourneyRequest.Departure.Now(),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 0, () -> false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("alternativeCount");

		assertThatThrownBy(() -> new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", new JourneyRequest.Departure.Now(),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 4, () -> false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("alternativeCount");

		assertThatThrownBy(() -> new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", new JourneyRequest.Departure.Now(),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.NO_STAIRS, JourneyRequest.ConstraintMode.NONE, 2, 2, () -> false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("NO_STAIRS requires REQUIRE_STEP_FREE");
	}

	@Test
	void delegatesCancellationSignal() {
		var cancelled = new AtomicBoolean(false);
		var req = new JourneyRequest(
			REQUEST_ID, "station-a", "station-b", new JourneyRequest.Departure.Now(),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 2, 2, cancelled::get);
		assertThat(req.isCancelled()).isFalse();
		cancelled.set(true);
		assertThat(req.isCancelled()).isTrue();
	}

	@Test
	void verifiesEnumsAndDepartureSubtypes() {
		assertThat(JourneyRequest.WalkingPace.SLOW.speedMetersPerHour()).isEqualTo(3_500);
		assertThat(JourneyRequest.WalkingPace.STANDARD.speedMetersPerHour()).isEqualTo(4_500);
		assertThat(JourneyRequest.WalkingPace.FAST.speedMetersPerHour()).isEqualTo(6_000);

		var scheduled = new JourneyRequest.Departure.Scheduled(NOW);
		assertThat(scheduled.requestedAt()).isEqualTo(NOW);
		assertThatThrownBy(() -> new JourneyRequest.Departure.Scheduled(null))
			.isInstanceOf(NullPointerException.class);
	}
}
