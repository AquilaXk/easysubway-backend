package com.easysubway.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("업무 시간대 달력 날짜")
class BusinessTimeTest {

	@Test
	@DisplayName("UTC로는 전날인 한국 00~09시도 한국 날짜를 돌려준다")
	void usesKoreanCalendarDateBetweenMidnightAndNineInKorea() {
		Clock utcClock = Clock.fixed(Instant.parse("2026-10-17T16:00:00Z"), ZoneOffset.UTC);

		assertThat(BusinessTime.today(utcClock)).isEqualTo(LocalDate.of(2026, 10, 18));
	}

	@Test
	@DisplayName("한국 자정 직전 순간은 아직 전날이다")
	void staysOnThePreviousDayJustBeforeKoreanMidnight() {
		Clock utcClock = Clock.fixed(Instant.parse("2026-10-17T14:59:59Z"), ZoneOffset.UTC);

		assertThat(BusinessTime.today(utcClock)).isEqualTo(LocalDate.of(2026, 10, 17));
	}

	@Test
	@DisplayName("주입된 시계의 시간대와 무관하게 같은 순간은 같은 날짜다")
	void ignoresTheTimeZoneOfTheInjectedClock() {
		Instant instant = Instant.parse("2026-10-17T16:00:00Z");

		assertThat(BusinessTime.today(Clock.fixed(instant, ZoneId.of("America/Los_Angeles"))))
			.isEqualTo(BusinessTime.today(Clock.fixed(instant, ZoneOffset.UTC)))
			.isEqualTo(LocalDate.of(2026, 10, 18));
	}

	@Test
	@DisplayName("한국 날짜의 시작은 전날 UTC 15시 벽시계 시각이다")
	void startOfKoreanDayIsFifteenHundredUtcOfThePreviousDay() {
		assertThat(BusinessTime.startOfDayUtcLocal(LocalDate.of(2026, 10, 18)))
			.isEqualTo(LocalDateTime.of(2026, 10, 17, 15, 0));
	}

	@Test
	@DisplayName("한국 날짜의 다음 날 시작과 Instant 시작은 같은 경계를 가리킨다")
	void nextDayStartAndInstantStartPointAtTheSameBoundaries() {
		LocalDate day = LocalDate.of(2026, 10, 18);

		assertThat(BusinessTime.startOfNextDayUtcLocal(day)).isEqualTo(LocalDateTime.of(2026, 10, 18, 15, 0));
		assertThat(BusinessTime.startOfDayInstant(day)).isEqualTo(Instant.parse("2026-10-17T15:00:00Z"));
	}

	@Test
	@DisplayName("UTC 벽시계 시각은 한국 달력 날짜로 묶인다")
	void groupsUtcWallClockByKoreanDate() {
		assertThat(BusinessTime.dateOfUtc(LocalDateTime.of(2026, 10, 17, 14, 59, 59)))
			.isEqualTo(LocalDate.of(2026, 10, 17));
		assertThat(BusinessTime.dateOfUtc(LocalDateTime.of(2026, 10, 17, 15, 0)))
			.isEqualTo(LocalDate.of(2026, 10, 18));
	}

	@Test
	@DisplayName("SQL 날짜 묶기에 쓰는 UTC 오프셋 시간은 겨울·여름 모두 업무 시간대의 오프셋과 같다")
	void sqlOffsetHoursMatchTheBusinessZone() {
		for (String instant : new String[] {"2026-01-01T00:00:00Z", "2026-07-01T00:00:00Z"}) {
			assertThat(BusinessTime.ZONE.getRules().getOffset(Instant.parse(instant)).getTotalSeconds())
				.isEqualTo(BusinessTime.UTC_OFFSET_HOURS * 3600);
		}
	}

	@Test
	@DisplayName("한국 벽시계 시각은 9시간 앞선 UTC 벽시계 시각으로 옮겨진다")
	void koreanWallClockMapsToUtcNineHoursEarlier() {
		assertThat(BusinessTime.toUtcLocal(LocalDateTime.of(2026, 10, 24, 9, 0)))
			.isEqualTo(LocalDateTime.of(2026, 10, 24, 0, 0));
		assertThat(BusinessTime.toUtcLocal(LocalDateTime.of(2026, 10, 24, 0, 30)))
			.isEqualTo(LocalDateTime.of(2026, 10, 23, 15, 30));
	}

	@Test
	@DisplayName("UTC 벽시계 시각은 9시간 뒤의 한국 벽시계 시각으로 옮겨지고 왕복해도 같다")
	void utcWallClockMapsToKoreanNineHoursLaterAndRoundTrips() {
		LocalDateTime utc = LocalDateTime.of(2026, 10, 23, 15, 30);

		assertThat(BusinessTime.fromUtcLocal(utc)).isEqualTo(LocalDateTime.of(2026, 10, 24, 0, 30));
		assertThat(BusinessTime.toUtcLocal(BusinessTime.fromUtcLocal(utc))).isEqualTo(utc);
	}
}
