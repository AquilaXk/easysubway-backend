package com.easysubway.auth.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("익명 사용자 발급 속도 제한")
class AnonymousAuthRateLimiterTest {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-06-13T00:00:00Z"));

	@Test
	@DisplayName("허용 횟수 안에서는 같은 클라이언트 발급을 허용한다")
	void checkAllowsRequestsWithinLimit() {
		var limiter = new AnonymousAuthRateLimiter(clock, 2, Duration.ofMinutes(10), 100);

		assertThatCode(() -> {
			limiter.check("client-1");
			limiter.check("client-1");
		}).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("허용 횟수를 넘으면 같은 클라이언트 발급을 거부한다")
	void checkRejectsRequestsOverLimit() {
		var limiter = new AnonymousAuthRateLimiter(clock, 1, Duration.ofMinutes(10), 100);

		limiter.check("client-1");

		assertThatThrownBy(() -> limiter.check("client-1"))
			.isInstanceOf(AnonymousAuthRateLimitExceededException.class)
			.hasMessage("잠시 후 다시 시도해 주세요.");
	}

	@Test
	@DisplayName("제한 시간이 지나면 같은 클라이언트 발급을 다시 허용한다")
	void checkAllowsRequestAfterWindowExpires() {
		var limiter = new AnonymousAuthRateLimiter(clock, 1, Duration.ofMinutes(10), 100);
		limiter.check("client-1");
		clock.advance(Duration.ofMinutes(10));

		assertThatCode(() -> limiter.check("client-1")).doesNotThrowAnyException();
	}

	private static final class MutableClock extends Clock {

		private Instant now;

		private MutableClock(Instant now) {
			this.now = now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneId.of("Asia/Seoul");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}

		private void advance(Duration duration) {
			now = now.plus(duration);
		}
	}
}
