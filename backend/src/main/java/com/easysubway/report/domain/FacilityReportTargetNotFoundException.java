package com.easysubway.report.domain;

import com.easysubway.common.error.ResourceNotFoundException;

public class FacilityReportTargetNotFoundException extends ResourceNotFoundException {

	public FacilityReportTargetNotFoundException() {
		super("시설 정보를 찾을 수 없습니다.");
	}
}
