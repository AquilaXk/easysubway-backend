package com.easysubway.admin.metric.adapter.in.web;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 지표 화면 테스트가 "오늘"을 JVM 기본 시간대가 아니라 주입 시계로 정하게 하는 시계(UTC, 순간 교체 가능).
 *
 * <p>기본 순간은 한국 2026-10-23 02:20이다. UTC 날짜로는 아직 2026-10-22라서, 시스템 시간대로 "오늘"을
 * 세는 기대값은 이 순간에 한국 날짜를 쓰는 서버와 하루 어긋난다(backend PR #539 CI 실패 시각).
 */
final class AdminMetricTestClock extends Clock {

	static final Instant KOREAN_EARLY_MORNING = Instant.parse("2026-10-22T17:20:00Z");

	/** {@link #KOREAN_EARLY_MORNING}의 한국 날짜. 시계로 계산하지 않고 요구사항 값을 그대로 적는다. */
	static final LocalDate KOREAN_BUSINESS_DAY = LocalDate.of(2026, 10, 23);

	private volatile Instant instant = KOREAN_EARLY_MORNING;

	void setInstant(Instant instant) {
		this.instant = instant;
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return Clock.fixed(instant, zone);
	}

	@Override
	public Instant instant() {
		return instant;
	}

	@TestConfiguration
	static class Configuration {

		@Bean
		@Primary
		AdminMetricTestClock adminMetricTestClock() {
			return new AdminMetricTestClock();
		}
	}
}
