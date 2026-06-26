package com.easysubway.admin.audit.domain;

import java.time.LocalDateTime;

public record AdminAuditEvent(
	Long id,
	AdminAuditEventType eventType,
	String actor,
	String rolePermission,
	String requestId,
	String clientIp,
	String userAgent,
	String targetType,
	String targetId,
	String action,
	AdminAuditOutcome outcome,
	String reason,
	LocalDateTime occurredAt
) {

	public AdminAuditEvent {
		if (eventType == null) {
			throw new IllegalArgumentException("감사 이벤트 유형이 필요합니다.");
		}
		if (actor == null || actor.isBlank()) {
			throw new IllegalArgumentException("감사 actor가 필요합니다.");
		}
		if (targetType == null || targetType.isBlank()) {
			throw new IllegalArgumentException("감사 target type이 필요합니다.");
		}
		if (action == null || action.isBlank()) {
			throw new IllegalArgumentException("감사 action이 필요합니다.");
		}
		if (outcome == null) {
			throw new IllegalArgumentException("감사 outcome이 필요합니다.");
		}
		if (occurredAt == null) {
			throw new IllegalArgumentException("감사 발생 시간이 필요합니다.");
		}
		actor = clean(actor);
		rolePermission = cleanNullable(rolePermission);
		requestId = cleanNullable(requestId);
		clientIp = cleanNullable(clientIp);
		userAgent = cleanNullable(userAgent);
		targetType = clean(targetType);
		targetId = cleanNullable(targetId);
		action = clean(action);
		reason = cleanNullable(reason);
	}

	private static String clean(String value) {
		String cleaned = value.trim();
		rejectSensitive(cleaned);
		return cleaned;
	}

	private static String cleanNullable(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return clean(value);
	}

	private static void rejectSensitive(String value) {
		String lower = value.toLowerCase(java.util.Locale.ROOT);
		if (lower.contains("receipt")
			|| lower.contains("private note")
			|| lower.contains("privatenote")
			|| lower.contains("secret")
			|| lower.contains("provider key")
			|| lower.contains("providerkey")
			|| lower.contains("uploadurl")
			|| lower.contains("upload url")
			|| (lower.contains("upload") && (lower.contains("http://") || lower.contains("https://")))) {
			throw new IllegalArgumentException("감사 이벤트에는 민감정보를 저장할 수 없습니다.");
		}
	}
}
