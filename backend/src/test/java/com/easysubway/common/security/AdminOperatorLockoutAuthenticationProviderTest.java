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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
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
		assertThatThrownBy(() -> provider(clock, new InMemoryAdminIdentityRepository(), 2, Duration.ZERO))
			.isInstanceOf(IllegalArgumentException.class);
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

		assertThatThrownBy(() -> provider.authenticate(token("app-user", "bad-password")))
			.isInstanceOf(BadCredentialsException.class);
		assertThatThrownBy(() -> provider.authenticate(token("app-user", "bad-password")))
			.isInstanceOf(BadCredentialsException.class);

		assertThat(provider.authenticate(token("app-user", "app-password")).isAuthenticated())
			.isTrue();
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
