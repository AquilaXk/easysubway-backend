package com.easysubway.common.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.Assert;

public class AdminOperatorLockoutAuthenticationProvider implements AuthenticationProvider {

	private final DaoAuthenticationProvider delegate = new DaoAuthenticationProvider();
	private final Set<String> protectedUsernames;
	private final int maxFailures;
	private final Duration lockoutDuration;
	private final Clock clock;
	private final ConcurrentMap<String, AttemptState> attemptsByUsername = new ConcurrentHashMap<>();

	public AdminOperatorLockoutAuthenticationProvider(
		UserDetailsService userDetailsService,
		PasswordEncoder passwordEncoder,
		Collection<String> protectedUsernames,
		int maxFailures,
		Duration lockoutDuration,
		Clock clock
	) {
		Assert.isTrue(maxFailures > 0, "maxFailures must be positive");
		Assert.isTrue(!lockoutDuration.isNegative() && !lockoutDuration.isZero(), "lockoutDuration must be positive");
		this.delegate.setUserDetailsService(userDetailsService);
		this.delegate.setPasswordEncoder(passwordEncoder);
		this.protectedUsernames = protectedUsernames.stream()
			.filter(username -> username != null && !username.isBlank())
			.map(AdminOperatorLockoutAuthenticationProvider::normalize)
			.collect(Collectors.toUnmodifiableSet());
		this.maxFailures = maxFailures;
		this.lockoutDuration = lockoutDuration;
		this.clock = clock;
	}

	@Override
	public Authentication authenticate(Authentication authentication) throws AuthenticationException {
		String username = normalize(authentication.getName());
		if (!protectedUsernames.contains(username)) {
			return delegate.authenticate(authentication);
		}

		rejectIfLocked(username);
		try {
			Authentication result = delegate.authenticate(authentication);
			attemptsByUsername.remove(username);
			return result;
		} catch (BadCredentialsException exception) {
			recordFailure(username);
			throw exception;
		}
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return delegate.supports(authentication);
	}

	private void rejectIfLocked(String username) {
		AttemptState state = attemptsByUsername.get(username);
		if (state == null || state.lockedUntil == null) {
			return;
		}
		if (state.lockedUntil.isAfter(clock.instant())) {
			throw new LockedException("관리자 인증 실패 횟수가 초과되었습니다.");
		}
		attemptsByUsername.remove(username, state);
	}

	private void recordFailure(String username) {
		Instant now = clock.instant();
		attemptsByUsername.compute(username, (key, current) -> {
			int failures = current == null ? 1 : current.failures + 1;
			Instant lockedUntil = failures >= maxFailures ? now.plus(lockoutDuration) : null;
			return new AttemptState(failures, lockedUntil);
		});
	}

	private static String normalize(String username) {
		if (username == null) {
			return "";
		}
		return username.toLowerCase(Locale.ROOT);
	}

	private record AttemptState(int failures, Instant lockedUntil) {
	}
}
