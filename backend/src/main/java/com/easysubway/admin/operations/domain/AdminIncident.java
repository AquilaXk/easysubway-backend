package com.easysubway.admin.operations.domain;

import java.time.LocalDateTime;

public record AdminIncident(
	String incidentId,
	String severity,
	String status,
	String source,
	String summary,
	String owner,
	LocalDateTime openedAt,
	LocalDateTime resolvedAt,
	String resolution
) {

	public AdminIncident {
		incidentId = clean(incidentId, "incident id가 필요합니다.");
		severity = clean(severity, "incident severity가 필요합니다.");
		status = clean(status, "incident status가 필요합니다.");
		source = clean(source, "incident source가 필요합니다.");
		summary = clean(summary, "incident summary가 필요합니다.");
		owner = clean(owner, "incident owner가 필요합니다.");
		resolution = cleanNullable(resolution);
		if (openedAt == null) {
			throw new IllegalArgumentException("incident openedAt이 필요합니다.");
		}
		if ("RESOLVED".equals(status) && (resolvedAt == null || resolution == null)) {
			throw new IllegalArgumentException("해결된 incident는 resolvedAt과 resolution이 필요합니다.");
		}
		if (!"RESOLVED".equals(status) && (resolvedAt != null || resolution != null)) {
			throw new IllegalArgumentException("열린 incident는 resolvedAt과 resolution을 가질 수 없습니다.");
		}
	}

	public AdminIncident resolve(String resolution, LocalDateTime resolvedAt) {
		return new AdminIncident(incidentId, severity, "RESOLVED", source, summary, owner, openedAt, resolvedAt, resolution);
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
