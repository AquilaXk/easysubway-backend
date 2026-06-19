package com.easysubway.auth.domain;

import java.time.LocalDateTime;

public record AnonymousAuthTokenSession(
	String userId,
	String accessToken,
	String refreshToken,
	LocalDateTime createdAt
) {

	public AnonymousAuthTokenSession {
		if (userId == null || userId.isBlank()) {
			throw new InvalidAnonymousAuthException("사용자 식별자가 필요합니다.");
		}
		if (accessToken == null || accessToken.isBlank()) {
			throw new InvalidAnonymousAuthException("access token이 필요합니다.");
		}
		if (refreshToken == null || refreshToken.isBlank()) {
			throw new InvalidAnonymousAuthException("refresh token이 필요합니다.");
		}
		if (createdAt == null) {
			throw new InvalidAnonymousAuthException("생성 시간이 필요합니다.");
		}

		userId = userId.trim();
		accessToken = accessToken.trim();
		refreshToken = refreshToken.trim();
	}
}
