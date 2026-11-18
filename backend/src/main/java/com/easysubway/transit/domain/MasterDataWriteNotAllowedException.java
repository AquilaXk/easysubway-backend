package com.easysubway.transit.domain;

import com.easysubway.common.error.ConflictException;

public class MasterDataWriteNotAllowedException extends ConflictException {

	public MasterDataWriteNotAllowedException() {
		super("운영 마스터 데이터가 읽기 전용입니다.");
	}
}
