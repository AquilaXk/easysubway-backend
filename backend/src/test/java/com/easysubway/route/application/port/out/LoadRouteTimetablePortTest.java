package com.easysubway.route.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LoadRouteTimetablePortTest {

	@Test
	@DisplayName("시간표 port는 LocalDate와 schema 초 범위를 강제한다")
	void routeTimetableRecordsValidateDatesAndSeconds() {
		new LoadRouteTimetablePort.ServiceCalendar(
			"weekday",
			true,
			true,
			true,
			true,
			true,
			false,
			false,
			LocalDate.parse("2026-07-01"),
			LocalDate.parse("2026-12-31"),
			"Asia/Seoul"
		);

		assertThatThrownBy(() -> new LoadRouteTimetablePort.ServiceCalendar(
			"weekday",
			true,
			true,
			true,
			true,
			true,
			false,
			false,
			LocalDate.parse("2026-12-31"),
			LocalDate.parse("2026-07-01"),
			"Asia/Seoul"
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LoadRouteTimetablePort.TransitStopTime(
			"trip-1",
			1,
			"station-a",
			"seoul-4",
			-1,
			60,
			0,
			0
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LoadRouteTimetablePort.TransitFrequency(
			"trip-1",
			60,
			30,
			0,
			false
		)).isInstanceOf(IllegalArgumentException.class);
	}
	@Test
	@DisplayName("접근성 snapshot row는 immutable copy로 보관한다")
	void routeAccessDataCopiesRows() {
		var nodes = new ArrayList<>(List.of(
			new LoadRouteTimetablePort.PathwayNode("node-1", "station-a", "line-a", "PLATFORM")
		));
		var accessData = new LoadRouteTimetablePort.RouteAccessData(nodes, List.of(), List.of(), List.of());
		nodes.clear();
		assertThat(accessData.pathwayNodes()).hasSize(1);
		assertThatThrownBy(() -> accessData.pathwayNodes().clear())
			.isInstanceOf(UnsupportedOperationException.class);
		assertThat(LoadRouteTimetablePort.RouteTimetable.empty().routeAccessData())
			.isEqualTo(LoadRouteTimetablePort.RouteAccessData.empty());
	}

	@Test
	@DisplayName("default station snapshot과 activation availability는 route timetable presence를 그대로 사용한다")
	void defaultStationSnapshotAndActivatableAvailabilityFollowRouteTimetable() {
		LoadRouteTimetablePort empty = LoadRouteTimetablePort.RouteTimetable::empty;
		assertThat(empty.loadStationTimetableSnapshot()).satisfies(snapshot -> {
			assertThat(snapshot.cacheKey()).isEqualTo("STATIC");
			assertThat(snapshot.timetableArtifactId()).isNull();
			assertThat(snapshot.freshUntil()).isNull();
			assertThat(snapshot.timetable()).isEqualTo(LoadRouteTimetablePort.RouteTimetable.empty());
		});
		assertThat(empty.hasActivatableRouteTimetable()).isFalse();

		var timetable = new LoadRouteTimetablePort.RouteTimetable(
			List.of(), List.of(), List.of(),
			List.of(new LoadRouteTimetablePort.TransitTrip("trip", "route", "weekday", "headsign", "0", "LOCAL", 0)),
			List.of(new LoadRouteTimetablePort.TransitStopTime("trip", 1, "station", "line", 0, 0, 0, 0)),
			List.of()
		);
		LoadRouteTimetablePort populated = () -> timetable;
		assertThat(populated.hasActivatableRouteTimetable()).isTrue();
	}

	@Test
	@DisplayName("#476: 경로 번들 시간표 행의 불변식은 seed 적재 없이도 레코드 단위로 강제된다")
	void bundleTimetableRowInvariantsHoldWithoutTheRemovedSeedLoader() {
		assertThatThrownBy(() -> new LoadRouteTimetablePort.TransitTrip("t", "r", "s", "h", "0", "BUS", "LOCAL", null, 0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LoadRouteTimetablePort.TransitTrip("t", "r", "s", "h", "0", "SUBWAY", "RAPID", null, 0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LoadRouteTimetablePort.TransitTrip("t", "r", "s", "h", "0", "ITX_CHEONGCHUN", "LOCAL", null, 0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(new LoadRouteTimetablePort.TransitTrip("t", "r", "s", "h", "0", "ITX_CHEONGCHUN", "EXPRESS", " ", 0).trainNo())
			.isNull();
		assertThat(new LoadRouteTimetablePort.TransitTrip("t", "r", "s", "h", "0", "SUBWAY", "LOCAL", "1001", 0).trainNo())
			.isEqualTo("1001");
		assertThatThrownBy(() -> new LoadRouteTimetablePort.ServiceCalendarDate("s", LocalDate.parse("2026-10-09"), 3))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LoadRouteTimetablePort.ServiceCalendarDate("s", null, 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LoadRouteTimetablePort.OfficialFare("t", "a", "b", 0, "KRW", "src", "snap"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LoadRouteTimetablePort.OfficialFare("t", "a", "b", 1400, "USD", "src", "snap"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LoadRouteTimetablePort.TransitStopTime("t", 0, "s", "l", 0, 0, 0, 0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LoadRouteTimetablePort.TransitStopTime("t", 1, "s", "l", 60, 30, 0, 0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LoadRouteTimetablePort.TransitFrequency("t", 0, 60, 0, true))
			.isInstanceOf(IllegalArgumentException.class);

		var trips = List.of(new LoadRouteTimetablePort.TransitTrip("trip", "route", "weekday", "headsign", "0", "LOCAL", 0));
		var withoutStops = new LoadRouteTimetablePort.RouteTimetable(
			List.of(), List.of(), List.of(), trips, List.of(), List.of(), LocalDate.parse("2026-12-31"));
		assertThat(withoutStops.feedEndDate()).isEqualTo(LocalDate.parse("2026-12-31"));
		assertThat(withoutStops.officialFares()).isEmpty();
		assertThat(withoutStops.routeAccessData()).isEqualTo(LoadRouteTimetablePort.RouteAccessData.empty());
		LoadRouteTimetablePort tripsOnly = () -> withoutStops;
		assertThat(tripsOnly.hasRouteTimetable()).isFalse();

		var identity = new com.easysubway.route.application.model.PlannerIdentity(
			"a".repeat(64), "b".repeat(64), "c".repeat(64), "sha256:" + "d".repeat(64), "d".repeat(64), "e".repeat(64), "f".repeat(64));
		var snapshot = new LoadRouteTimetablePort.RouteTimetableSnapshot("cache", "artifact", identity, withoutStops);
		assertThat(snapshot.plannerIdentity().timetableSnapshotSha256()).isEqualTo("a".repeat(64));
		assertThat(snapshot.freshUntil()).isNull();
	}

	@Test
	@DisplayName("CarDoorHint는 유효한 car/door 번호와 non-null 필드를 검증한다")
	void carDoorHintValidatesInputs() {
		assertThatThrownBy(() -> new LoadRouteTimetablePort.CarDoorHint("s", "l", "UP", "TRANSFER", 0, 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LoadRouteTimetablePort.CarDoorHint("s", "l", "UP", "TRANSFER", 11, 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LoadRouteTimetablePort.CarDoorHint("s", "l", "UP", "TRANSFER", 1, 0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LoadRouteTimetablePort.CarDoorHint("s", "l", "UP", "TRANSFER", 1, 5))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LoadRouteTimetablePort.CarDoorHint(null, "l", "UP", "TRANSFER", 1, 1))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new LoadRouteTimetablePort.CarDoorHint("s", null, "UP", "TRANSFER", 1, 1))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new LoadRouteTimetablePort.CarDoorHint("s", "l", null, "TRANSFER", 1, 1))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new LoadRouteTimetablePort.CarDoorHint("s", "l", "UP", null, 1, 1))
			.isInstanceOf(NullPointerException.class);

		var hint = new LoadRouteTimetablePort.CarDoorHint("s", "l", "UP", "TRANSFER", 1, 1);
		assertThat(hint.stationId()).isEqualTo("s");
		assertThat(hint.lineId()).isEqualTo("l");
		assertThat(hint.direction()).isEqualTo("UP");
		assertThat(hint.targetFacilityType()).isEqualTo("TRANSFER");
		assertThat(hint.carNumber()).isEqualTo(1);
		assertThat(hint.doorNumber()).isEqualTo(1);
		assertThat(hint).isEqualTo(new LoadRouteTimetablePort.CarDoorHint("s", "l", "UP", "TRANSFER", 1, 1));
		assertThat(hint.hashCode()).isNotZero();
		assertThat(hint.toString()).contains("TRANSFER");

		var accessData = new LoadRouteTimetablePort.RouteAccessData(List.of(), List.of(), List.of(), List.of(), null);
		assertThat(accessData.carDoorHints()).isEmpty();
	}
}
