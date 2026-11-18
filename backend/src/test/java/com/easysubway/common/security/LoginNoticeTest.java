package com.easysubway.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("공개 로그인 notice")
class LoginNoticeTest {

	@Test
	@DisplayName("공개 상태는 NONE과 RETRY_WARNING만 제공한다")
	void exposesOnlyNoneAndRetryWarning() {
		assertThat(LoginNotice.values())
			.containsExactly(LoginNotice.NONE, LoginNotice.RETRY_WARNING);
	}
}
