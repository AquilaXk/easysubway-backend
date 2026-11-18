package com.easysubway.transit.domain;

/**
 * 시설 운영 상태의 출처(#419). 미검증 시민 제보는 출처가 될 수 없고, 관리자가 확인한 뒤에만 {@link #ADMIN_VERIFIED}로 기록한다.
 */
public enum FacilityStatusSource {
	SEOUL_METRO_FEED,
	ADMIN_VERIFIED
}
