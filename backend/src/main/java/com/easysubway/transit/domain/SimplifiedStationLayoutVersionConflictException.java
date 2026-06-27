package com.easysubway.transit.domain;

import com.easysubway.common.error.ConflictException;

public class SimplifiedStationLayoutVersionConflictException extends ConflictException {

	public SimplifiedStationLayoutVersionConflictException() {
		super("역 구조도 정보가 이미 변경되었습니다.");
	}
}
