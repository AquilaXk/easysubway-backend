package com.easysubway.user.domain;

import java.util.Objects;

public class InvalidUserDataDeletionException extends RuntimeException {

	public InvalidUserDataDeletionException(String message) {
		super(Objects.requireNonNull(message, "예외 메시지가 필요합니다."));
	}
}
