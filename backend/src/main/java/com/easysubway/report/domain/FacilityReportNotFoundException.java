package com.easysubway.report.domain;

import com.easysubway.common.error.ResourceNotFoundException;

public class FacilityReportNotFoundException extends ResourceNotFoundException {

	public FacilityReportNotFoundException() {
		super("신고 정보를 찾을 수 없습니다.");
	}
}
