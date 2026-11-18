package com.easysubway.transit.domain;

import com.easysubway.common.error.ResourceNotFoundException;

public class AccessibilityFacilityNotFoundException extends ResourceNotFoundException {

	public AccessibilityFacilityNotFoundException() {
		super("시설 정보를 찾을 수 없습니다.");
	}
}
