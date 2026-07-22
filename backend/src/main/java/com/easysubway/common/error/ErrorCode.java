package com.easysubway.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
	INVALID_REQUEST("INVALID_REQUEST", ErrorCategory.USER, HttpStatus.BAD_REQUEST, "error.invalid-request"),
	RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", ErrorCategory.USER, HttpStatus.NOT_FOUND, "error.resource-not-found"),
	CONFLICT("CONFLICT", ErrorCategory.USER, HttpStatus.CONFLICT, "error.conflict"),
	UNREADABLE_BODY("UNREADABLE_BODY", ErrorCategory.USER, HttpStatus.BAD_REQUEST, "error.unreadable-body"),
	INTERNAL_ERROR("INTERNAL_ERROR", ErrorCategory.SYSTEM, HttpStatus.INTERNAL_SERVER_ERROR, "error.internal-error"),
	ROUTE_SESSION_ATTESTATION_REJECTED(
		"ROUTE_SESSION_ATTESTATION_REJECTED",
		ErrorCategory.USER,
		HttpStatus.FORBIDDEN,
		"error.route-session-attestation-rejected"
	),
	ROUTE_SESSION_ATTESTATION_UNAVAILABLE(
		"ROUTE_SESSION_ATTESTATION_UNAVAILABLE",
		ErrorCategory.DEPENDENCY,
		HttpStatus.SERVICE_UNAVAILABLE,
		"error.route-session-attestation-unavailable"
	),
	ITX_TIMETABLE_UNAVAILABLE(
		"ITX_TIMETABLE_UNAVAILABLE",
		ErrorCategory.DEPENDENCY,
		HttpStatus.SERVICE_UNAVAILABLE,
		"error.itx-timetable-unavailable"
	),
	ROUTE_SCOPE_INVALID("ROUTE_SCOPE_INVALID", ErrorCategory.USER, HttpStatus.UNPROCESSABLE_ENTITY, "error.route-scope-invalid"),
	ROUTE_SESSION_REQUIRED("ROUTE_SESSION_REQUIRED", ErrorCategory.USER, HttpStatus.UNAUTHORIZED, "error.route-session-required"),
	ROUTE_RATE_LIMITED("ROUTE_RATE_LIMITED", ErrorCategory.USER, HttpStatus.TOO_MANY_REQUESTS, "error.route-rate-limited"),
	ROUTE_ORIGIN_FORBIDDEN("ROUTE_ORIGIN_FORBIDDEN", ErrorCategory.USER, HttpStatus.FORBIDDEN, "error.route-origin-forbidden");

	private final String code;
	private final ErrorCategory category;
	private final HttpStatus httpStatus;
	private final String messageKey;

	ErrorCode(String code, ErrorCategory category, HttpStatus httpStatus, String messageKey) {
		this.code = code;
		this.category = category;
		this.httpStatus = httpStatus;
		this.messageKey = messageKey;
	}

	public String code() {
		return code;
	}

	public ErrorCategory category() {
		return category;
	}

	public HttpStatus httpStatus() {
		return httpStatus;
	}

	public String messageKey() {
		return messageKey;
	}
}
