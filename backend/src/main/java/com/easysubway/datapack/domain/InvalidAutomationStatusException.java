package com.easysubway.datapack.domain;

/** 자동화 상태 snapshot이 계약(v1)과 맞지 않을 때. 메시지는 어긋난 위치만 담고 입력 값을 되풀이하지 않는다. */
public class InvalidAutomationStatusException extends RuntimeException {

	public InvalidAutomationStatusException(String message) {
		super(message);
	}
}
