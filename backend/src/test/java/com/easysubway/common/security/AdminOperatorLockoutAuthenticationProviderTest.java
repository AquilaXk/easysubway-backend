package com.easysubway.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.admin.identity.adapter.out.persistence.InMemoryAdminIdentityRepository;
import com.easysubway.admin.identity.application.port.out.AdminIdentityRepository;
import com.easysubway.admin.identity.application.service.AdminIdentityUserDetailsService;
import com.easysubway.admin.identity.domain.AdminIdentity;
import com.easysubway.admin.identity.domain.AdminIdentityAuthMethod;
import com.easysubway.admin.identity.domain.AdminIdentityRole;
import com.easysubway.admin.identity.domain.AdminIdentityStatus;
import com.easysubway.common.web.TrustedClientIpResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@DisplayName("관리자 Basic auth 실패 잠금 provider")
class AdminOperatorLockoutAuthenticationProviderTest {

	@Test
	@DisplayName("잠금 기간이 지나면 올바른 비밀번호 인증을 다시 허용한다")
	void lockoutExpiresAfterDuration() {
		var clock = new MutableClock(Instant.parse("2026-06-22T00:00:00Z"));
		var repository = new InMemoryAdminIdentityRepository();
		var provider = provider(clock, repository, 2, Duration.ofMinutes(5));

		assertThatThrownBy(() -> provider.authenticate(token("admin-user", "bad-password")))
			.isInstanceOf(BadCredentialsException.class);
		assertThatThrownBy(() -> provider.authenticate(token("admin-user", "bad-password")))
			.isInstanceOf(BadCredentialsException.class);
		assertThatThrownBy(() -> provider.authenticate(token("admin-user", "admin-password")))
			.isInstanceOf(LockedException.class);

		clock.advance(Duration.ofMinutes(5).plusSeconds(1));

		assertThat(provider.authenticate(token("admin-user", "admin-password")).isAuthenticated())
			.isTrue();
		assertThat(repository.findByLoginId("admin-user").orElseThrow().failedLoginCount())
			.isZero();
	}

	@Test
	@DisplayName("잠금 기간이 지난 뒤 첫 실패는 이전 실패 횟수를 이어받지 않는다")
	void expiredLockoutResetsFailureCounterBeforeNextFailure() {
		var clock = new MutableClock(Instant.parse("2026-06-22T00:00:00Z"));
		var repository = new InMemoryAdminIdentityRepository();
		var provider = provider(clock, repository, 2, Duration.ofMinutes(5));

		assertThatThrownBy(() -> provider.authenticate(token("admin-user", "bad-password")))
			.isInstanceOf(BadCredentialsException.class);
		assertThatThrownBy(() -> provider.authenticate(token("admin-user", "bad-password")))
			.isInstanceOf(BadCredentialsException.class);
		clock.advance(Duration.ofMinutes(5).plusSeconds(1));

		assertThatThrownBy(() -> provider.authenticate(token("admin-user", "bad-password")))
			.isInstanceOf(BadCredentialsException.class);

		assertThat(provider.authenticate(token("admin-user", "admin-password")).isAuthenticated())
			.isTrue();
		assertThat(repository.findByLoginId("admin-user").orElseThrow().failedLoginCount())
			.isZero();
	}

	@Test
	@DisplayName("잠금 상태에서 거절된 인증도 감사에 남긴다")
	void lockedAuthenticationWritesAudit() {
		var clock = new MutableClock(Instant.parse("2026-06-22T00:00:00Z"));
		var repository = new InMemoryAdminIdentityRepository();
		var provider = provider(clock, repository, 1, Duration.ofMinutes(5));

		assertThatThrownBy(() -> provider.authenticate(token("admin-user", "bad-password")))
			.isInstanceOf(BadCredentialsException.class);
		int auditCountBeforeLockedReject = repository.audits().size();

		assertThatThrownBy(() -> provider.authenticate(token("admin-user", "admin-password")))
			.isInstanceOf(LockedException.class);

		assertThat(repository.audits()).hasSize(auditCountBeforeLockedReject + 1);
		assertThat(repository.audits().getLast().outcome()).isEqualTo("LOCKED");
	}

	@Test
	@DisplayName("잠긴 계정 인증 시 타이밍 오라클 방지를 위해 LockedException 전에 더미 BCrypt 비교를 수행한다")
	void lockedAuthenticationPerformsDummyPasswordComparisonBeforeThrowingLockedException() {
		var clock = new MutableClock(Instant.parse("2026-06-22T00:00:00Z"));
		var repository = new InMemoryAdminIdentityRepository();
		var delegateEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
		var matchesCount = new AtomicInteger();
		PasswordEncoder countingEncoder = new PasswordEncoder() {
			@Override
			public String encode(CharSequence rawPassword) {
				return delegateEncoder.encode(rawPassword);
			}

			@Override
			public boolean matches(CharSequence rawPassword, String encodedPassword) {
				matchesCount.incrementAndGet();
				return delegateEncoder.matches(rawPassword, encodedPassword);
			}
		};
		repository.save(new AdminIdentity(
			"admin-user",
			"관리자",
			null,
			delegateEncoder.encode("admin-password"),
			AdminIdentityAuthMethod.LOCAL,
			AdminIdentityRole.ADMIN,
			AdminIdentityStatus.ACTIVE,
			5,
			LocalDateTime.now(clock).plusMinutes(5),
			LocalDateTime.now(clock),
			null,
			false,
			null,
			true,
			LocalDateTime.now(clock),
			LocalDateTime.now(clock)
		));
		var users = new ConcurrentUserDetailsManager();
		users.createUser(User.withUsername("admin-user")
			.password(delegateEncoder.encode("admin-password"))
			.roles("ADMIN")
			.build());
		var provider = new AdminOperatorLockoutAuthenticationProvider(
			new AdminIdentityUserDetailsService(repository, users, clock),
			countingEncoder,
			repository,
			5,
			Duration.ofMinutes(5),
			clock
		);

		assertThatThrownBy(() -> provider.authenticate(token("admin-user", "admin-password")))
			.isInstanceOf(LockedException.class);
		assertThatThrownBy(() -> provider.authenticate(token("admin-user", null)))
			.isInstanceOf(LockedException.class);
		assertThat(matchesCount.get()).isEqualTo(2);
	}

	@Test
	@DisplayName("disabled 상태에서 거절된 인증도 감사에 남긴다")
	void disabledAuthenticationWritesAudit() {
		var clock = new MutableClock(Instant.parse("2026-06-22T00:00:00Z"));
		var repository = new InMemoryAdminIdentityRepository();
		var provider = provider(clock, repository, 2, Duration.ofMinutes(5));
		repository.save(repository.findByLoginId("admin-user").orElseThrow().disable(LocalDateTime.now(clock)));

		assertThatThrownBy(() -> provider.authenticate(token("admin-user", "admin-password")))
			.isInstanceOf(DisabledException.class);

		assertThat(repository.audits()).hasSize(1);
		assertThat(repository.audits().getFirst().outcome()).isEqualTo("DISABLED");
		assertThat(repository.audits().getFirst().reason()).isEqualTo("DisabledException");
	}

	@Test
	@DisplayName("credential rotation 플래그가 있으면 ACTIVE 상태라도 인증을 차단한다")
	void credentialRotationFlagBlocksAuthentication() {
		var clock = new MutableClock(Instant.parse("2026-06-22T00:00:00Z"));
		var repository = new InMemoryAdminIdentityRepository();
		var provider = provider(clock, repository, 2, Duration.ofMinutes(5));
		var current = repository.findByLoginId("admin-user").orElseThrow();
		repository.save(new AdminIdentity(
			current.loginId(),
			current.displayName(),
			current.email(),
			current.passwordHash(),
			current.authMethod(),
			current.role(),
			AdminIdentityStatus.ACTIVE,
			current.failedLoginCount(),
			current.lockedUntil(),
			current.passwordChangedAt(),
			current.passwordExpiresAt(),
			true,
			current.breakGlassReason(),
			current.bootstrapManaged(),
			current.createdAt(),
			LocalDateTime.now(clock)
		));

		assertThatThrownBy(() -> provider.authenticate(token("admin-user", "admin-password")))
			.isInstanceOf(DisabledException.class);
		assertThat(repository.audits()).hasSize(1);
		assertThat(repository.audits().getFirst().outcome()).isEqualTo("CREDENTIAL_ROTATION_REQUIRED");
	}

	@Test
	@DisplayName("락아웃 정책 값이 잘못되면 provider를 만들 수 없다")
	void rejectsInvalidLockoutPolicy() {
		var clock = new MutableClock(Instant.parse("2026-06-22T00:00:00Z"));
		assertThatThrownBy(() -> provider(clock, new InMemoryAdminIdentityRepository(), 0, Duration.ofMinutes(5)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> provider(clock, new InMemoryAdminIdentityRepository(), 2, Duration.ofMinutes(-1)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> provider(clock, new InMemoryAdminIdentityRepository(), 2, Duration.ZERO))
			.isInstanceOf(IllegalArgumentException.class);
		PasswordEncoder blankEncoder = new PasswordEncoder() {
			@Override
			public String encode(CharSequence rawPassword) {
				return "";
			}

			@Override
			public boolean matches(CharSequence rawPassword, String encodedPassword) {
				return false;
			}
		};
		assertThatThrownBy(() -> new AdminOperatorLockoutAuthenticationProvider(
			new AdminIdentityUserDetailsService(new InMemoryAdminIdentityRepository(), new ConcurrentUserDetailsManager(), clock),
			blankEncoder,
			new InMemoryAdminIdentityRepository(),
			2,
			Duration.ofMinutes(5),
			clock
		)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("관리자 identity가 아닌 fallback 사용자는 실패 누적으로 잠기지 않는다")
	void fallbackUserDoesNotLock() {
		var provider = provider(
			new MutableClock(Instant.parse("2026-06-22T00:00:00Z")),
			new InMemoryAdminIdentityRepository(),
			2,
			Duration.ofMinutes(5)
		);

		assertThatThrownBy(() -> provider.authenticate(token(null, "bad-password")))
			.isInstanceOf(BadCredentialsException.class);
		assertThatThrownBy(() -> provider.authenticate(token("app-user", "bad-password")))
			.isInstanceOf(BadCredentialsException.class);
		assertThatThrownBy(() -> provider.authenticate(token("app-user", "bad-password")))
			.isInstanceOf(BadCredentialsException.class);

		assertThat(provider.authenticate(token("app-user", "app-password")).isAuthenticated())
			.isTrue();
	}

	@Test
	@DisplayName("HTTP 로그인 잠금은 (loginId, clientIp) 단위로 분리되어 공격자 IP의 실패로 정상 관리자 IP가 잠기지 않는다")
	void httpLockoutIsScopedPerClientIpWithoutLockingOtherIps() {
		var clock = new MutableClock(Instant.parse("2026-06-22T00:00:00Z"));
		var repository = new InMemoryAdminIdentityRepository();
		var passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
		repository.upsertBootstrap(new AdminIdentity(
			"admin-user",
			"관리자",
			null,
			passwordEncoder.encode("admin-password"),
			AdminIdentityAuthMethod.LOCAL,
			AdminIdentityRole.ADMIN,
			AdminIdentityStatus.ACTIVE,
			0,
			null,
			LocalDateTime.now(clock),
			null,
			false,
			null,
			true,
			LocalDateTime.now(clock),
			LocalDateTime.now(clock)
		));
		var provider = new AdminOperatorLockoutAuthenticationProvider(
			new AdminIdentityUserDetailsService(repository, new ConcurrentUserDetailsManager(), clock),
			passwordEncoder,
			repository,
			2,
			Duration.ofMinutes(5),
			clock,
			TrustedClientIpResolver.fromCidrs("10.0.0.0/8")
		);

		try {
			var attackerRequest = new MockHttpServletRequest();
			attackerRequest.setRemoteAddr("10.1.2.3");
			attackerRequest.addHeader("X-Forwarded-For", "198.51.100.5");
			RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(attackerRequest));
			assertThatThrownBy(() -> provider.authenticate(token("admin-user", "bad-password")))
				.isInstanceOf(BadCredentialsException.class);
			assertThatThrownBy(() -> provider.authenticate(token("admin-user", "bad-password")))
				.isInstanceOf(BadCredentialsException.class);
			assertThatThrownBy(() -> provider.authenticate(token("admin-user", "admin-password")))
				.isInstanceOf(LockedException.class);

			var legitimateAdminRequest = new MockHttpServletRequest();
			legitimateAdminRequest.setRemoteAddr("10.1.2.3");
			legitimateAdminRequest.addHeader("X-Forwarded-For", "203.0.113.10");
			RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(legitimateAdminRequest));
			assertThat(provider.authenticate(token("admin-user", "admin-password")).isAuthenticated())
				.isTrue();

			// 정상 관리자 로그인 성공 후에도 공격자 IP는 잠금 기간 동안 계속 차단된다.
			RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(attackerRequest));
			assertThatThrownBy(() -> provider.authenticate(token("admin-user", "admin-password")))
				.isInstanceOf(LockedException.class);

			clock.advance(Duration.ofMinutes(5).plusSeconds(1));
			assertThat(provider.authenticate(token("admin-user", "admin-password")).isAuthenticated())
				.isTrue();
		} finally {
			RequestContextHolder.resetRequestAttributes();
		}
	}

	@Test
	@DisplayName("존재하지 않는 사용자 ID 대입 시 BCrypt 더미 비교를 수행하지 않고 즉시 거부한다")
	void unknownUsernameSkipsDummyBcryptEvaluation() {
		var clock = new MutableClock(Instant.parse("2026-06-22T00:00:00Z"));
		var repository = new InMemoryAdminIdentityRepository();
		var delegateEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
		var matchesCount = new AtomicInteger();
		PasswordEncoder countingEncoder = new PasswordEncoder() {
			@Override
			public String encode(CharSequence rawPassword) {
				return delegateEncoder.encode(rawPassword);
			}

			@Override
			public boolean matches(CharSequence rawPassword, String encodedPassword) {
				matchesCount.incrementAndGet();
				return delegateEncoder.matches(rawPassword, encodedPassword);
			}
		};
		var provider = new AdminOperatorLockoutAuthenticationProvider(
			new AdminIdentityUserDetailsService(repository, new ConcurrentUserDetailsManager(), clock),
			countingEncoder,
			repository,
			5,
			Duration.ofMinutes(5),
			clock
		);

		for (int i = 0; i < 10; i++) {
			String loginId = "unknown-admin-" + i;
			assertThatThrownBy(() -> provider.authenticate(token(loginId, "bad-password")))
				.isInstanceOf(BadCredentialsException.class);
		}
		assertThat(matchesCount.get()).isZero();
	}

	@Test
	@DisplayName("null IP 해석기는 directOnly로 대체되고 null 사용자명은 빈 문자열로 정규화되며 IP 잠금 맵은 상한 초과 시 축출된다")
	void nullResolverNullUsernameAndBoundedIpLockoutsAreHandled() {
		var clock = new MutableClock(Instant.parse("2026-06-22T00:00:00Z"));
		var repository = new InMemoryAdminIdentityRepository();
		PasswordEncoder fastEncoder = new PasswordEncoder() {
			@Override
			public String encode(CharSequence rawPassword) {
				return "{noop}" + rawPassword;
			}

			@Override
			public boolean matches(CharSequence rawPassword, String encodedPassword) {
				return ("{noop}" + rawPassword).equals(encodedPassword);
			}
		};
		repository.upsertBootstrap(new AdminIdentity(
			"admin-user", "관리자", null, fastEncoder.encode("admin-password"),
			AdminIdentityAuthMethod.LOCAL, AdminIdentityRole.ADMIN, AdminIdentityStatus.ACTIVE,
			0, null, LocalDateTime.now(clock), null, false, null, true,
			LocalDateTime.now(clock), LocalDateTime.now(clock)
		));
		var users = new ConcurrentUserDetailsManager();
		var userDetailsService = new AdminIdentityUserDetailsService(repository, users, clock);

		var defaultResolverProvider = new AdminOperatorLockoutAuthenticationProvider(
			userDetailsService, fastEncoder, repository, 2, Duration.ofMinutes(5), clock, null
		);
		Authentication nullNameAuth = new UsernamePasswordAuthenticationToken(null, "bad-password") {
			@Override
			public String getName() {
				return null;
			}
		};
		assertThatThrownBy(() -> defaultResolverProvider.authenticate(nullNameAuth))
			.isInstanceOf(BadCredentialsException.class);
		assertThat(defaultResolverProvider.authenticate(token("admin-user", "admin-password")).isAuthenticated())
			.isTrue();

		var proxyProvider = new AdminOperatorLockoutAuthenticationProvider(
			userDetailsService, fastEncoder, repository, 1, Duration.ofMinutes(5),
			clock, TrustedClientIpResolver.fromCidrs("10.0.0.0/8")
		);
		try {
			for (int i = 0; i <= 16_384; i++) {
				var req = new MockHttpServletRequest();
				req.setRemoteAddr("10.1.2.3");
				req.addHeader("X-Forwarded-For", "198.51." + ((i >> 8) & 0xFF) + "." + (i & 0xFF));
				RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
				assertThatThrownBy(() -> proxyProvider.authenticate(token("admin-user", "bad-password")))
					.isInstanceOf(BadCredentialsException.class);
			}
			// 가장 오래된 IP("198.51.0.0")는 축출되어 잠금 상태가 아닌 BadCredentialsException이 발생한다.
			var evictedReq = new MockHttpServletRequest();
			evictedReq.setRemoteAddr("10.1.2.3");
			evictedReq.addHeader("X-Forwarded-For", "198.51.0.0");
			RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(evictedReq));
			assertThatThrownBy(() -> proxyProvider.authenticate(token("admin-user", "bad-password")))
				.isInstanceOf(BadCredentialsException.class);
		} finally {
			RequestContextHolder.resetRequestAttributes();
		}
	}

	@Test
	@DisplayName("차단 감사 결과는 CREDENTIAL_ROTATION_REQUIRED 상태와 ACTIVE 계정의 만료·비활성 상태를 구분한다")
	void blockedOutcomeCoversCredentialRotationAndActiveAccountStatusVariants() {
		var clock = new MutableClock(Instant.parse("2026-06-22T00:00:00Z"));
		var repository = new InMemoryAdminIdentityRepository();
		var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
		var now = LocalDateTime.now(clock);
		String hash = encoder.encode("admin-password");

		repository.upsertBootstrap(new AdminIdentity(
			"rotation-user", "교체필요", null, hash,
			AdminIdentityAuthMethod.LOCAL, AdminIdentityRole.ADMIN, AdminIdentityStatus.CREDENTIAL_ROTATION_REQUIRED,
			0, null, now, null, false, null, true, now, now
		));
		repository.upsertBootstrap(new AdminIdentity(
			"expired-active-user", "만료활성", null, hash,
			AdminIdentityAuthMethod.LOCAL, AdminIdentityRole.ADMIN, AdminIdentityStatus.ACTIVE,
			0, null, now, now.minusMinutes(1), false, null, true, now, now
		));
		repository.upsertBootstrap(new AdminIdentity(
			"disabled-details-user", "비활성위임", null, hash,
			AdminIdentityAuthMethod.LOCAL, AdminIdentityRole.ADMIN, AdminIdentityStatus.ACTIVE,
			0, null, now, null, false, null, true, now, now
		));

		AdminIdentityUserDetailsService delegateService = new AdminIdentityUserDetailsService(
			repository, new ConcurrentUserDetailsManager(), clock
		);
		UserDetailsService customService = username -> {
			if ("disabled-details-user".equals(username)) {
				return User.withUsername("disabled-details-user")
					.password(hash)
					.roles("ADMIN")
					.disabled(true)
					.build();
			}
			return delegateService.loadUserByUsername(username);
		};

		var provider = new AdminOperatorLockoutAuthenticationProvider(
			customService, encoder, repository, 2, Duration.ofMinutes(5), clock
		);

		assertThatThrownBy(() -> provider.authenticate(token("rotation-user", "admin-password")))
			.isInstanceOf(DisabledException.class);
		assertThatThrownBy(() -> provider.authenticate(token("expired-active-user", "admin-password")))
			.isInstanceOf(org.springframework.security.authentication.CredentialsExpiredException.class);
		assertThatThrownBy(() -> provider.authenticate(token("disabled-details-user", "admin-password")))
			.isInstanceOf(DisabledException.class);

		assertThat(repository.audits())
			.extracting(audit -> audit.loginId() + ":" + audit.outcome())
			.containsExactly(
				"rotation-user:CREDENTIAL_ROTATION_REQUIRED",
				"expired-active-user:PASSWORD_EXPIRED",
				"disabled-details-user:DISABLED"
			);
	}

	private AdminOperatorLockoutAuthenticationProvider provider(
		Clock clock,
		AdminIdentityRepository adminIdentityRepository,
		int maxFailures,
		Duration lockoutDuration
	) {
		var passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
		adminIdentityRepository.upsertBootstrap(new AdminIdentity(
			"admin-user",
			"관리자",
			null,
			passwordEncoder.encode("admin-password"),
			AdminIdentityAuthMethod.LOCAL,
			AdminIdentityRole.ADMIN,
			AdminIdentityStatus.ACTIVE,
			0,
			null,
			LocalDateTime.now(clock),
			null,
			false,
			null,
			true,
			LocalDateTime.now(clock),
			LocalDateTime.now(clock)
		));
		var users = new ConcurrentUserDetailsManager();
		users.createUser(User.withUsername("admin-user")
			.password(passwordEncoder.encode("admin-password"))
			.roles("ADMIN")
			.build());
		users.createUser(User.withUsername("app-user")
			.password(passwordEncoder.encode("app-password"))
			.roles("USER")
			.build());
		var userDetailsService = new AdminIdentityUserDetailsService(adminIdentityRepository, users, clock);
		return new AdminOperatorLockoutAuthenticationProvider(
			userDetailsService,
			passwordEncoder,
			adminIdentityRepository,
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
