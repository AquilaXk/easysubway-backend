package com.easysubway.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

@DisplayName("관리자 Basic auth 실패 잠금 provider")
class AdminOperatorLockoutAuthenticationProviderTest {

	@Test
	@DisplayName("잠금 기간이 지나면 올바른 비밀번호 인증을 다시 허용한다")
	void lockoutExpiresAfterDuration() {
		var clock = new MutableClock(Instant.parse("2026-06-22T00:00:00Z"));
		var provider = provider(clock, List.of("admin-user"), 2, Duration.ofMinutes(5));

		assertThatThrownBy(() -> provider.authenticate(token("admin-user", "bad-password")))
			.isInstanceOf(BadCredentialsException.class);
		assertThatThrownBy(() -> provider.authenticate(token("admin-user", "bad-password")))
			.isInstanceOf(BadCredentialsException.class);
		assertThatThrownBy(() -> provider.authenticate(token("admin-user", "admin-password")))
			.isInstanceOf(LockedException.class);

		clock.advance(Duration.ofMinutes(5).plusSeconds(1));

		assertThat(provider.authenticate(token("admin-user", "admin-password")).isAuthenticated())
			.isTrue();
	}

	@Test
	@DisplayName("보호 대상이 아닌 사용자는 실패 누적으로 잠기지 않는다")
	void nonProtectedUserDoesNotLock() {
		var provider = provider(new MutableClock(Instant.parse("2026-06-22T00:00:00Z")), List.of("admin-user"), 2,
			Duration.ofMinutes(5));

		assertThatThrownBy(() -> provider.authenticate(token("app-user", "bad-password")))
			.isInstanceOf(BadCredentialsException.class);
		assertThatThrownBy(() -> provider.authenticate(token("app-user", "bad-password")))
			.isInstanceOf(BadCredentialsException.class);

		assertThat(provider.authenticate(token("app-user", "app-password")).isAuthenticated())
			.isTrue();
	}

	private AdminOperatorLockoutAuthenticationProvider provider(
		Clock clock,
		List<String> protectedUsernames,
		int maxFailures,
		Duration lockoutDuration
	) {
		var users = new ConcurrentUserDetailsManager();
		var passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
		users.createUser(User.withUsername("admin-user")
			.password(passwordEncoder.encode("admin-password"))
			.roles("ADMIN")
			.build());
		users.createUser(User.withUsername("app-user")
			.password(passwordEncoder.encode("app-password"))
			.roles("USER")
			.build());
		return new AdminOperatorLockoutAuthenticationProvider(
			users,
			passwordEncoder,
			protectedUsernames,
			maxFailures,
			lockoutDuration,
			clock
		);
	}

	private Authentication token(String username, String password) {
		return UsernamePasswordAuthenticationToken.unauthenticated(username, password);
	}

	private static final class MutableClock extends Clock {

		private Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		@Override
		public ZoneId getZone() {
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}

		private void advance(Duration duration) {
			instant = instant.plus(duration);
		}
	}
}
