package com.easysubway.common.security;

import com.easysubway.admin.identity.application.port.out.AdminIdentityRepository;
import com.easysubway.admin.identity.domain.AdminIdentity;
import com.easysubway.admin.identity.domain.AdminIdentityAuthMethod;
import com.easysubway.admin.identity.domain.AdminLoginAudit;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.security.authentication.AccountStatusException;
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
	private final AdminIdentityRepository adminIdentityRepository;
	private final int maxFailures;
	private final Duration lockoutDuration;
	private final Clock clock;

	public AdminOperatorLockoutAuthenticationProvider(
		UserDetailsService userDetailsService,
		PasswordEncoder passwordEncoder,
		AdminIdentityRepository adminIdentityRepository,
		int maxFailures,
		Duration lockoutDuration,
		Clock clock
	) {
		Assert.isTrue(maxFailures > 0, "maxFailures must be positive");
		Assert.isTrue(!lockoutDuration.isNegative() && !lockoutDuration.isZero(), "lockoutDuration must be positive");
		this.delegate.setUserDetailsService(userDetailsService);
		this.delegate.setPasswordEncoder(passwordEncoder);
		this.adminIdentityRepository = adminIdentityRepository;
		this.maxFailures = maxFailures;
		this.lockoutDuration = lockoutDuration;
		this.clock = clock;
	}

	@Override
	public Authentication authenticate(Authentication authentication) throws AuthenticationException {
		String username = normalize(authentication.getName());
		var identity = adminIdentityRepository.findByLoginId(username);
		if (identity.isEmpty()) {
			return delegate.authenticate(authentication);
		}

		try {
			rejectIfLocked(identity.get());
			Authentication result = delegate.authenticate(authentication);
			recordSuccess(identity.get());
			return result;
		} catch (AccountStatusException exception) {
			recordBlocked(identity.get(), exception);
			throw exception;
		} catch (BadCredentialsException exception) {
			recordFailure(identity.get());
			throw exception;
		}
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return delegate.supports(authentication);
	}

	private void rejectIfLocked(AdminIdentity identity) {
		if (identity.lockedAt(LocalDateTime.now(clock))) {
			throw new LockedException("관리자 인증 실패 횟수가 초과되었습니다.");
		}
	}

	private void recordFailure(AdminIdentity identity) {
		LocalDateTime now = LocalDateTime.now(clock);
		AdminIdentity saved = adminIdentityRepository.recordLoginFailure(
			identity.loginId(),
			now,
			maxFailures,
			lockoutDuration
		);
		adminIdentityRepository.recordLoginAudit(new AdminLoginAudit(
			saved.loginId(),
			saved.authMethod(),
			saved.lockedAt(now) ? "LOCKED" : "FAILED",
			null,
			now
		));
	}

	private void recordSuccess(AdminIdentity identity) {
		LocalDateTime now = LocalDateTime.now(clock);
		AdminIdentity saved = adminIdentityRepository.recordLoginSuccess(identity.loginId(), now);
		adminIdentityRepository.recordLoginAudit(new AdminLoginAudit(
			saved.loginId(),
			saved.authMethod(),
			"SUCCESS",
			saved.authMethod() == AdminIdentityAuthMethod.BREAK_GLASS ? saved.breakGlassReason() : null,
			now
		));
	}

	private void recordBlocked(AdminIdentity identity, AccountStatusException exception) {
		LocalDateTime now = LocalDateTime.now(clock);
		adminIdentityRepository.recordLoginAudit(new AdminLoginAudit(
			identity.loginId(),
			identity.authMethod(),
			blockedOutcome(identity, now),
			exception.getClass().getSimpleName(),
			now
		));
	}

	private String blockedOutcome(AdminIdentity identity, LocalDateTime now) {
		if (identity.lockedAt(now)) {
			return "LOCKED";
		}
		if (identity.credentialRotationRequired()) {
			return "CREDENTIAL_ROTATION_REQUIRED";
		}
		return switch (identity.status()) {
			case DISABLED -> "DISABLED";
			case PASSWORD_EXPIRED -> "PASSWORD_EXPIRED";
			case CREDENTIAL_ROTATION_REQUIRED -> "CREDENTIAL_ROTATION_REQUIRED";
			case LOCKED -> "LOCKED";
			case ACTIVE -> identity.credentialsExpiredAt(now) ? "PASSWORD_EXPIRED" : "DISABLED";
		};
	}

	private static String normalize(String username) {
		if (username == null) {
			return "";
		}
		return username.toLowerCase(Locale.ROOT);
	}
}
