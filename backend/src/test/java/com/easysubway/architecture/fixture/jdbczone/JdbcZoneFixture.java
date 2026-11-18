package com.easysubway.architecture.fixture.jdbczone;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.function.Function;

final class JdbcZoneFixture {

	Timestamp toColumn(LocalDateTime value) {
		return Timestamp.valueOf(value);
	}

	LocalDateTime fromColumn(Timestamp value) {
		return value.toLocalDateTime();
	}

	Function<Timestamp, LocalDateTime> fromColumnInLambda() {
		return column -> column.toLocalDateTime();
	}

	Function<Timestamp, LocalDateTime> fromColumnByReference() {
		return Timestamp::toLocalDateTime;
	}

	Date sqlDate(LocalDate value) {
		return Date.valueOf(value);
	}

	Calendar calendar() {
		return Calendar.getInstance();
	}
}
