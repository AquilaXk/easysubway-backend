package com.easysubway.admin.metric.domain;

/** 일별 지표 스냅샷(#1739) 값이 유효하지 않을 때 던진다. */
public class InvalidAdminMetricException extends RuntimeException {

	public InvalidAdminMetricException(String message) {
		super(message);
	}
}
