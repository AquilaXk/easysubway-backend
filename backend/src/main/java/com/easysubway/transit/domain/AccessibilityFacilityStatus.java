package com.easysubway.transit.domain;

public enum AccessibilityFacilityStatus {
	NORMAL,
	BROKEN,
	UNDER_CONSTRUCTION,
	CLOSED,
	UNKNOWN,
	USER_REPORTED,
	ADMIN_VERIFIED;

	/**
	 * 운영자가 확인해야 하는 상태인지(#1741 역 목록 "확인 필요 시설" 뱃지·시설 상태판 정렬의 단일 판정 기준).
	 * 고장·공사·폐쇄·확인 필요·사용자 제보는 확인 대상, 정상·관리자 확인은 아니다.
	 */
	public boolean needsAttention() {
		return switch (this) {
			case BROKEN, UNDER_CONSTRUCTION, CLOSED, UNKNOWN, USER_REPORTED -> true;
			case NORMAL, ADMIN_VERIFIED -> false;
		};
	}
}
