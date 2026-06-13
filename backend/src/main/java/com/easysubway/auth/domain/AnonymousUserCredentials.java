package com.easysubway.auth.domain;

import java.time.LocalDateTime;

public record AnonymousUserCredentials(
	String userId,
	String password,
	LocalDateTime createdAt
) {

	public AnonymousUserCredentials {
		if (userId == null || userId.isBlank()) {
			throw new InvalidAnonymousAuthException("사용자 식별자가 필요합니다.");
		}
		if (password == null || password.isBlank()) {
			throw new InvalidAnonymousAuthException("인증 비밀번호가 필요합니다.");
		}
		if (createdAt == null) {
			throw new InvalidAnonymousAuthException("생성 시간이 필요합니다.");
		}

		userId = userId.trim();
		password = password.trim();
	}
}
