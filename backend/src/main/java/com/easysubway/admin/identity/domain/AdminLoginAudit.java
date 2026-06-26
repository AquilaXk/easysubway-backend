package com.easysubway.admin.identity.domain;

import java.time.LocalDateTime;

public record AdminLoginAudit(
	String loginId,
	AdminIdentityAuthMethod authMethod,
	String outcome,
	String reason,
	LocalDateTime occurredAt
) {

	public AdminLoginAudit {
		if (loginId == null || loginId.isBlank()) {
			throw new IllegalArgumentException("관리자 감사 로그인 ID가 필요합니다.");
		}
		if (authMethod == null) {
			throw new IllegalArgumentException("관리자 감사 인증 방식이 필요합니다.");
		}
		if (!"FAILED".equals(outcome)
			&& !"LOCKED".equals(outcome)
			&& !"DISABLED".equals(outcome)
			&& !"PASSWORD_EXPIRED".equals(outcome)
			&& !"CREDENTIAL_ROTATION_REQUIRED".equals(outcome)
			&& !"SUCCESS".equals(outcome)) {
			throw new IllegalArgumentException("관리자 감사 결과가 올바르지 않습니다.");
		}
		if (occurredAt == null) {
			throw new IllegalArgumentException("관리자 감사 발생 시간이 필요합니다.");
		}
		loginId = loginId.trim();
		reason = reason == null || reason.isBlank() ? null : reason.trim();
	}
}
