package com.easysubway.auth.domain;

public record AuthenticatedUser(
	String userId,
	String authType,
	boolean anonymous
) {

	public AuthenticatedUser {
		if (userId == null || userId.isBlank()) {
			throw new InvalidAnonymousAuthException("사용자 식별자가 필요합니다.");
		}
		if (authType == null || authType.isBlank()) {
			throw new InvalidAnonymousAuthException("인증 방식이 필요합니다.");
		}

		userId = userId.trim();
		authType = authType.trim();
	}
}
