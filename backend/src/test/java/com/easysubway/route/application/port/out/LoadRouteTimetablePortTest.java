package com.easysubway.route.application.port.out;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
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
}
