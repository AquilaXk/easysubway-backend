package com.easysubway.common.security;

import com.easysubway.admin.identity.application.port.out.AdminIdentityRepository;
import com.easysubway.admin.identity.domain.AdminIdentity;
import com.easysubway.admin.identity.domain.AdminIdentityAuthMethod;
import com.easysubway.admin.identity.domain.AdminIdentityStatus;
import com.easysubway.admin.identity.domain.AdminLoginAudit;
import com.easysubway.common.web.TrustedClientIpResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.Assert;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class AdminOperatorLockoutAuthenticationProvider implements AuthenticationProvider {

	private static final String DIRECT_CLIENT_KEY = "direct";
	private static final int MAX_LOCKOUT_KEYS = 16_384;

	private final DaoAuthenticationProvider delegate = new DaoAuthenticationProvider();
	private final UserDetailsService userDetailsService;
	private final PasswordEncoder passwordEncoder;
	private final String dummyEncodedPassword;
	private final AdminIdentityRepository adminIdentityRepository;
	private final int maxFailures;
	private final Duration lockoutDuration;
	private final Clock clock;
	private final TrustedClientIpResolver clientIpResolver;
	private final Map<String, IpLockoutState> ipLockouts = Collections.synchronizedMap(
		new LinkedHashMap<>(256, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, IpLockoutState> eldest) {
				return size() > MAX_LOCKOUT_KEYS;
			}
		}
	);

	public AdminOperatorLockoutAuthenticationProvider(
		UserDetailsService userDetailsService,
		PasswordEncoder passwordEncoder,
		AdminIdentityRepository adminIdentityRepository,
		int maxFailures,
		Duration lockoutDuration,
		Clock clock
	) {
		this(
			userDetailsService,
			passwordEncoder,
			adminIdentityRepository,
			maxFailures,
			lockoutDuration,
			clock,
			TrustedClientIpResolver.directOnly()
		);
	}

	public AdminOperatorLockoutAuthenticationProvider(
		UserDetailsService userDetailsService,
		PasswordEncoder passwordEncoder,
		AdminIdentityRepository adminIdentityRepository,
		int maxFailures,
		Duration lockoutDuration,
		Clock clock,
		TrustedClientIpResolver clientIpResolver
	) {
		Assert.isTrue(maxFailures > 0, "maxFailures must be positive");
		Assert.isTrue(!lockoutDuration.isNegative() && !lockoutDuration.isZero(), "lockoutDuration must be positive");
		this.userDetailsService = userDetailsService;
		this.passwordEncoder = passwordEncoder;
		this.dummyEncodedPassword = initializeDummyEncodedPassword(passwordEncoder);
		this.delegate.setUserDetailsService(userDetailsService);
		this.delegate.setPasswordEncoder(passwordEncoder);
		this.adminIdentityRepository = adminIdentityRepository;
		this.maxFailures = maxFailures;
		this.lockoutDuration = lockoutDuration;
		this.clock = clock;
		this.clientIpResolver = clientIpResolver == null ? TrustedClientIpResolver.directOnly() : clientIpResolver;
	}

	@Override
	public Authentication authenticate(Authentication authentication) throws AuthenticationException {
		String username = normalize(authentication.getName());
		String clientIp = resolveClientIp();
		var identity = adminIdentityRepository.findByLoginId(username);
		if (identity.isEmpty()) {
			// 미존재 ID는 DaoAuthenticationProvider의 더미 BCrypt 타이밍 보정으로 100ms+ CPU가 소모되지 않도록
			// 존재 여부를 먼저 조회해 즉시 거부한다.
			try {
				userDetailsService.loadUserByUsername(username);
			} catch (UsernameNotFoundException notFound) {
				throw new BadCredentialsException("관리자 인증에 실패했습니다.");
			}
			return delegate.authenticate(authentication);
		}

		try {
			rejectIfLocked(identity.get(), clientIp, authentication);
			Authentication result = delegate.authenticate(authentication);
			clearIpFailure(identity.get().loginId(), clientIp);
			recordSuccess(identity.get());
			return result;
		} catch (AccountStatusException exception) {
			recordBlocked(identity.get(), exception);
			throw exception;
		} catch (BadCredentialsException exception) {
			recordIpFailure(identity.get().loginId(), clientIp);
			recordFailure(identity.get());
			throw exception;
		}
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return delegate.supports(authentication);
	}

	private void rejectIfLocked(AdminIdentity identity, String clientIp, Authentication authentication) {
		LocalDateTime now = LocalDateTime.now(clock);
		boolean permanentLock = identity.status() == AdminIdentityStatus.LOCKED;
		boolean directContext = DIRECT_CLIENT_KEY.equals(clientIp);
		boolean locked = permanentLock
			|| (directContext ? identity.lockedAt(now) : isIpLocked(identity.loginId(), clientIp, now));
		if (locked) {
			Object credentials = authentication.getCredentials();
			String presentedPassword = credentials == null ? "" : credentials.toString();
			passwordEncoder.matches(presentedPassword, dummyEncodedPassword);
			throw new LockedException("관리자 인증 실패 횟수가 초과되었습니다.");
		}
	}

	private String resolveClientIp() {
		if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
			return clientIpResolver.resolve(attributes.getRequest());
		}
		return DIRECT_CLIENT_KEY;
	}

	private boolean isIpLocked(String loginId, String clientIp, LocalDateTime now) {
		String key = ipKey(loginId, clientIp);
		synchronized (ipLockouts) {
			IpLockoutState state = ipLockouts.get(key);
			return state != null && state.lockedUntil != null && state.lockedUntil.isAfter(now);
		}
	}

	private void recordIpFailure(String loginId, String clientIp) {
		LocalDateTime now = LocalDateTime.now(clock);
		String key = ipKey(loginId, clientIp);
		synchronized (ipLockouts) {
			IpLockoutState state = ipLockouts.get(key);
			boolean expired = state == null || (state.lockedUntil != null && !state.lockedUntil.isAfter(now));
			int baselineFailures = expired ? 0 : state.failures;
			LocalDateTime baselineLockedUntil = expired ? null : state.lockedUntil;
			int nextFailures = baselineFailures + 1;
			LocalDateTime nextLockedUntil = nextFailures >= maxFailures
				? now.plus(lockoutDuration)
				: baselineLockedUntil;
			ipLockouts.put(key, new IpLockoutState(nextFailures, nextLockedUntil));
		}
	}

	private void clearIpFailure(String loginId, String clientIp) {
		synchronized (ipLockouts) {
			ipLockouts.remove(ipKey(loginId, clientIp));
		}
	}

	private static String ipKey(String loginId, String clientIp) {
		return loginId + "\n" + clientIp;
	}

	private record IpLockoutState(int failures, LocalDateTime lockedUntil) {
	}

	private static String initializeDummyEncodedPassword(PasswordEncoder passwordEncoder) {
		Assert.notNull(passwordEncoder, "passwordEncoder must not be null");
		String encoded = passwordEncoder.encode("easysubway-lockout-timing-guard");
		Assert.hasText(encoded, "passwordEncoder must produce non-blank dummy hash");
		return encoded;
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
