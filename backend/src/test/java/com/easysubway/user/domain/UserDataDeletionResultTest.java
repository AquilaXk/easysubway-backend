package com.easysubway.user.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("사용자 데이터 삭제 결과")
class UserDataDeletionResultTest {

	@Test
	@DisplayName("사용자 식별자는 필수다")
	void userIdIsRequired() {
		assertThatThrownBy(() -> result(" "))
			.isInstanceOf(InvalidUserDataDeletionException.class)
			.hasMessage("사용자 식별자가 필요합니다.");
	}

	@Test
	@DisplayName("삭제 결과 건수는 음수일 수 없다")
	void deletionCountsCannotBeNegative() {
		assertThatThrownBy(() -> new UserDataDeletionResult(
			"user-1",
			0,
			0,
			0,
			-1,
			false,
			0,
			0,
			false,
			0,
			false
		))
			.isInstanceOf(InvalidUserDataDeletionException.class)
			.hasMessage("삭제 결과 건수는 음수일 수 없습니다.");
	}

	private UserDataDeletionResult result(String userId) {
		return new UserDataDeletionResult(
			userId,
			0,
			0,
			0,
			0,
			false,
			0,
			0,
			false,
			0,
			false
		);
	}
}
