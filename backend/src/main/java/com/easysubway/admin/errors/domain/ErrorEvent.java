package com.easysubway.admin.errors.domain;

import java.time.Instant;

/**
 * 5xx SYSTEM/DEPENDENCY 오류 집계 행. 요청 바디·쿼리·헤더·사용자 식별자·예외 message는 갖지 않는다.
 */
public record ErrorEvent(
	Long id,
	Instant firstOccurredAt,
	Instant lastOccurredAt,
	String code,
	String category,
	int httpStatus,
	String method,
	String pathPattern,
	String exceptionClass,
	String stackHash,
	String sampleCorrelationId,
	long occurrenceCount
) {
}
