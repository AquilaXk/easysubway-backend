package com.easysubway.report.domain;

import com.easysubway.common.error.ConflictException;

public class FacilityReportReviewConflictException extends ConflictException {

	public FacilityReportReviewConflictException() {
		super("이미 확인 처리된 신고입니다.");
	}
}
