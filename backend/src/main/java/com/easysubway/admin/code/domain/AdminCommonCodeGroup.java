package com.easysubway.admin.code.domain;

import java.time.LocalDateTime;

public record AdminCommonCodeGroup(
	String groupCode,
	String displayName,
	String description,
	int sortOrder,
	boolean enabled,
	LocalDateTime createdAt,
	LocalDateTime updatedAt
) {

	public AdminCommonCodeGroup {
		groupCode = clean(groupCode, "공통코드 group code가 필요합니다.");
		displayName = clean(displayName, "공통코드 group 이름이 필요합니다.");
		description = cleanNullable(description);
		if (sortOrder < 0) {
			throw new IllegalArgumentException("공통코드 group 정렬 순서는 0 이상이어야 합니다.");
		}
		if (createdAt == null || updatedAt == null) {
			throw new IllegalArgumentException("공통코드 group audit 시간이 필요합니다.");
		}
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
