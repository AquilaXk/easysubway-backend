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
}
