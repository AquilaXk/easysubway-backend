package com.easysubway.transit.domain;

/**
 * 시설의 운영 상태 이진값(#403·#419). 확인된 고장만 {@link #OUT_OF_SERVICE}로 두고 추정하지 않는다.
 */
public enum FacilityOperationalState {
	OPERATING,
	OUT_OF_SERVICE
}
