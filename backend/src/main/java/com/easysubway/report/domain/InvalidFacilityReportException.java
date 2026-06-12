package com.easysubway.report.domain;

import com.easysubway.common.error.InvalidRequestException;

public class InvalidFacilityReportException extends InvalidRequestException {

	public InvalidFacilityReportException(String message) {
		super(message);
	}
}
