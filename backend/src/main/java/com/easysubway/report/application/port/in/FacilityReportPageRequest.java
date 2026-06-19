package com.easysubway.report.application.port.in;

import com.easysubway.report.domain.InvalidFacilityReportException;

public record FacilityReportPageRequest(
	int page,
	int size
) {

	public static final int DEFAULT_PAGE = 0;
	public static final int DEFAULT_SIZE = 20;
	public static final int MAX_SIZE = 50;

	public FacilityReportPageRequest {
		if (page < 0 || size <= 0) {
			throw new InvalidFacilityReportException("페이지 요청 값을 확인해야 합니다.");
		}
		size = Math.min(size, MAX_SIZE);
		if (page > Integer.MAX_VALUE / size) {
			throw new InvalidFacilityReportException("페이지 요청 값을 확인해야 합니다.");
		}
	}

	public static FacilityReportPageRequest of(Integer page, Integer size) {
		int normalizedPage = page == null ? DEFAULT_PAGE : page;
		int requestedSize = size == null ? DEFAULT_SIZE : size;
		return new FacilityReportPageRequest(normalizedPage, requestedSize);
	}

	public int limitForHasNext() {
		return size + 1;
	}

	public int offset() {
		return page * size;
	}
}
