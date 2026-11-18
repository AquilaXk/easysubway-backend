package com.easysubway.admin.operations.domain;

import java.time.LocalDateTime;

/**
 * 장애 상태 전이 1건의 기록. 타임라인 표시와 감사 정합의 근거가 된다.
 * 최초 접수는 {@code fromStatus}가 {@code null}인 행으로 남긴다.
 */
public record AdminIncidentTransition(
	String incidentId,
	AdminIncidentStatus fromStatus,
	AdminIncidentStatus toStatus,
	LocalDateTime changedAt,
	String changedBy,
	String note
) {

	public AdminIncidentTransition {
		incidentId = clean(incidentId, "transition incident id가 필요합니다.");
		changedBy = clean(changedBy, "transition changedBy가 필요합니다.");
		note = cleanNullable(note);
		if (toStatus == null) {
			throw new IllegalArgumentException("transition toStatus가 필요합니다.");
		}
		if (changedAt == null) {
			throw new IllegalArgumentException("transition changedAt이 필요합니다.");
		}
	}

	public boolean isInitial() {
		return fromStatus == null;
	}

	private static String clean(String value, String message) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(message);
		}
		return value.trim();
	}

	private static String cleanNullable(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
