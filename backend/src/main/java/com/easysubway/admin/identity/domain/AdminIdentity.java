package com.easysubway.admin.identity.domain;

import java.time.Duration;
import java.time.LocalDateTime;

public record AdminIdentity(
	String loginId,
	String displayName,
	String email,
	String passwordHash,
	AdminIdentityAuthMethod authMethod,
	AdminIdentityRole role,
	AdminIdentityStatus status,
	int failedLoginCount,
	LocalDateTime lockedUntil,
	LocalDateTime passwordChangedAt,
	LocalDateTime passwordExpiresAt,
	boolean credentialRotationRequired,
	String breakGlassReason,
	boolean bootstrapManaged,
	LocalDateTime createdAt,
	LocalDateTime updatedAt
) {

	public AdminIdentity {
		if (loginId == null || loginId.isBlank()) {
			throw new IllegalArgumentException("관리자 로그인 ID가 필요합니다.");
		}
		if (displayName == null || displayName.isBlank()) {
			throw new IllegalArgumentException("관리자 표시명이 필요합니다.");
		}
		if (passwordHash == null || passwordHash.isBlank()) {
			throw new IllegalArgumentException("관리자 비밀번호 해시가 필요합니다.");
		}
		if (authMethod == null) {
			throw new IllegalArgumentException("관리자 인증 방식이 필요합니다.");
		}
		if (role == null) {
			throw new IllegalArgumentException("관리자 역할이 필요합니다.");
		}
		if (status == null) {
			throw new IllegalArgumentException("관리자 계정 상태가 필요합니다.");
		}
		if (failedLoginCount < 0) {
			throw new IllegalArgumentException("관리자 로그인 실패 횟수는 0 이상이어야 합니다.");
		}
		if (createdAt == null || updatedAt == null) {
			throw new IllegalArgumentException("관리자 계정 감사 시간이 필요합니다.");
		}
		loginId = loginId.trim();
		displayName = displayName.trim();
		email = trimToNull(email);
		passwordHash = passwordHash.trim();
		breakGlassReason = trimToNull(breakGlassReason);
		if (authMethod == AdminIdentityAuthMethod.BREAK_GLASS && breakGlassReason == null) {
			throw new IllegalArgumentException("break-glass 계정은 사용 사유가 필요합니다.");
		}
	}

	public boolean lockedAt(LocalDateTime now) {
		return status == AdminIdentityStatus.LOCKED || (lockedUntil != null && lockedUntil.isAfter(now));
	}

	public boolean disabled() {
		return credentialRotationRequired
			|| status == AdminIdentityStatus.DISABLED
			|| status == AdminIdentityStatus.CREDENTIAL_ROTATION_REQUIRED;
	}

	public boolean credentialsExpiredAt(LocalDateTime now) {
		return status == AdminIdentityStatus.PASSWORD_EXPIRED
			|| (passwordExpiresAt != null && !passwordExpiresAt.isAfter(now));
	}

	public AdminIdentity recordFailure(LocalDateTime now, int maxFailures, Duration lockoutDuration) {
		if (maxFailures < 1) {
			throw new IllegalArgumentException("최대 로그인 실패 횟수는 1 이상이어야 합니다.");
		}
		if (lockoutDuration == null || lockoutDuration.isZero() || lockoutDuration.isNegative()) {
			throw new IllegalArgumentException("락아웃 기간은 양수여야 합니다.");
		}
		boolean lockoutExpired = lockedUntil != null && !lockedUntil.isAfter(now);
		int baselineFailures = lockoutExpired ? 0 : failedLoginCount;
		LocalDateTime baselineLockedUntil = lockoutExpired ? null : lockedUntil;
		int failures = baselineFailures + 1;
		LocalDateTime nextLockedUntil = failures >= maxFailures ? now.plus(lockoutDuration) : baselineLockedUntil;
		return withLoginPolicy(failures, nextLockedUntil, status, credentialRotationRequired, now);
	}

	public AdminIdentity recordLocalSuccess(LocalDateTime now) {
		return withLoginPolicy(0, null, status, credentialRotationRequired, now);
	}

	public AdminIdentity recordBreakGlassSuccess(LocalDateTime now) {
		return withLoginPolicy(0, null, AdminIdentityStatus.CREDENTIAL_ROTATION_REQUIRED, true, now);
	}

	public AdminIdentity refreshBootstrap(AdminIdentity bootstrap, LocalDateTime now) {
		return new AdminIdentity(
			loginId,
			bootstrap.displayName(),
			bootstrap.email(),
			bootstrap.passwordHash(),
			bootstrap.authMethod(),
			bootstrap.role(),
			bootstrap.status(),
			0,
			null,
			now,
			bootstrap.passwordExpiresAt(),
			bootstrap.credentialRotationRequired(),
			bootstrap.breakGlassReason(),
			bootstrap.bootstrapManaged(),
			createdAt,
			now
		);
	}

	public AdminIdentity disable(LocalDateTime now) {
		return new AdminIdentity(
			loginId,
			displayName,
			email,
			passwordHash,
			authMethod,
			role,
			AdminIdentityStatus.DISABLED,
			0,
			null,
			passwordChangedAt,
			passwordExpiresAt,
			credentialRotationRequired,
			breakGlassReason,
			bootstrapManaged,
			createdAt,
			now
		);
	}

	private AdminIdentity withLoginPolicy(
		int failures,
		LocalDateTime nextLockedUntil,
		AdminIdentityStatus nextStatus,
		boolean nextCredentialRotationRequired,
		LocalDateTime now
	) {
		return new AdminIdentity(
			loginId,
			displayName,
			email,
			passwordHash,
			authMethod,
			role,
			nextStatus,
			failures,
			nextLockedUntil,
			passwordChangedAt,
			passwordExpiresAt,
			nextCredentialRotationRequired,
			breakGlassReason,
			bootstrapManaged,
			createdAt,
			now
		);
	}

	private static String trimToNull(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}
}
