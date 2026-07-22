package com.easysubway.admin.errors.application;

import com.easysubway.common.web.pagination.AdminPageRequest;
import java.time.Instant;

public record ErrorEventQuery(
	Instant fromInclusive,
	Instant toExclusive,
	String code,
	String category,
	AdminPageRequest pageRequest
) {

	public static ErrorEventQuery of(
		Instant fromInclusive,
		Instant toExclusive,
		String code,
		String category,
		Integer page,
		Integer size
	) {
		return new ErrorEventQuery(
			fromInclusive,
			toExclusive,
			blankToNull(code),
			blankToNull(category),
			AdminPageRequest.of(page, size)
		);
	}

	public ErrorEventQuery withPage(int page) {
		return new ErrorEventQuery(
			fromInclusive,
			toExclusive,
			code,
			category,
			AdminPageRequest.of(page, pageRequest.size())
		);
	}

	private static String blankToNull(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}
}
