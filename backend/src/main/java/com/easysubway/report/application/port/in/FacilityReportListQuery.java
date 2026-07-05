package com.easysubway.report.application.port.in;

import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.InvalidFacilityReportException;
import java.time.LocalDate;
import java.util.Locale;

/**
 * 관리자 신고 대기열 표준 테이블(#1737)의 서버 파라미터 질의.
 *
 * <p>상태 필터에 더해 키워드 검색(신고 내용)·접수 기간·정렬을 담는다. 모든 값은 URL 쿼리에서
 * 오며 no-JS(폼 제출)와 htmx(부분 갱신)가 같은 파라미터를 공유한다. 잘못된 정렬 토큰은 500이
 * 아니라 기본값(최신순)으로 관대하게 처리한다.
 */
public record FacilityReportListQuery(
	FacilityReportStatus status,
	String keyword,
	LocalDate createdFrom,
	LocalDate createdTo,
	SortField sortField,
	SortDirection sortDirection,
	int page,
	int size
) {

	public enum SortField {
		CREATED_AT("created_at"),
		STATUS("status");

		private final String column;

		SortField(String column) {
			this.column = column;
		}

		public String column() {
			return column;
		}

		public String token() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	public enum SortDirection {
		ASC,
		DESC;

		public String sql() {
			return this == ASC ? "ASC" : "DESC";
		}

		public String token() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	public FacilityReportListQuery {
		keyword = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
		sortField = sortField == null ? SortField.CREATED_AT : sortField;
		sortDirection = sortDirection == null ? SortDirection.DESC : sortDirection;
		if (page < 0 || size <= 0) {
			throw new InvalidFacilityReportException("페이지 요청 값을 확인해야 합니다.");
		}
		size = Math.min(size, FacilityReportPageRequest.MAX_SIZE);
		if (page > Integer.MAX_VALUE / size) {
			throw new InvalidFacilityReportException("페이지 요청 값을 확인해야 합니다.");
		}
		if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
			throw new InvalidFacilityReportException("접수 기간 시작이 종료보다 늦을 수 없습니다.");
		}
	}

	public static FacilityReportListQuery of(
		FacilityReportStatus status,
		String keyword,
		LocalDate createdFrom,
		LocalDate createdTo,
		String sort,
		Integer page,
		Integer size
	) {
		SortField field = SortField.CREATED_AT;
		SortDirection direction = SortDirection.DESC;
		if (sort != null && !sort.isBlank()) {
			String[] parts = sort.split(",", 2);
			field = parseSortField(parts[0]);
			if (parts.length > 1) {
				direction = parseSortDirection(parts[1]);
			}
		}
		int normalizedPage = page == null ? FacilityReportPageRequest.DEFAULT_PAGE : page;
		int requestedSize = size == null ? FacilityReportPageRequest.DEFAULT_SIZE : size;
		return new FacilityReportListQuery(
			status,
			keyword,
			createdFrom,
			createdTo,
			field,
			direction,
			normalizedPage,
			requestedSize
		);
	}

	private static SortField parseSortField(String raw) {
		String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
		for (SortField field : SortField.values()) {
			if (field.name().equals(value)) {
				return field;
			}
		}
		return SortField.CREATED_AT;
	}

	private static SortDirection parseSortDirection(String raw) {
		String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
		return "ASC".equals(value) ? SortDirection.ASC : SortDirection.DESC;
	}

	/** 정렬 상태를 URL 토큰(`created_at,desc`)으로 되돌린다. 템플릿의 정렬 링크·저장된 뷰에 쓴다. */
	public String sortToken() {
		return sortField.token() + "," + sortDirection.token();
	}

	/** 지정 컬럼을 클릭했을 때의 다음 정렬 토큰: 같은 컬럼이면 방향 토글, 아니면 오름차순 시작. */
	public String nextSortToken(SortField column) {
		if (sortField == column && sortDirection == SortDirection.ASC) {
			return column.token() + ",desc";
		}
		if (sortField == column && sortDirection == SortDirection.DESC) {
			return column.token() + ",asc";
		}
		return column.token() + ",asc";
	}

	/** `<th aria-sort>` 값: 현재 정렬 컬럼이면 방향, 아니면 none. */
	public String ariaSort(SortField column) {
		if (sortField != column) {
			return "none";
		}
		return sortDirection == SortDirection.ASC ? "ascending" : "descending";
	}

	/** 템플릿용: 컬럼 토큰(created_at·status)으로 aria-sort 값을 준다. */
	public String ariaSortFor(String columnToken) {
		return ariaSort(columnOf(columnToken));
	}

	/** 템플릿용: 컬럼 토큰으로 다음 정렬 토큰을 준다(같은 컬럼이면 방향 토글). */
	public String nextSortFor(String columnToken) {
		return nextSortToken(columnOf(columnToken));
	}

	private static SortField columnOf(String columnToken) {
		return "status".equalsIgnoreCase(columnToken) ? SortField.STATUS : SortField.CREATED_AT;
	}

	public FacilityReportPageRequest toPageRequest() {
		return new FacilityReportPageRequest(page, size);
	}

	public int limitForHasNext() {
		return size + 1;
	}

	public int offset() {
		return page * size;
	}

	public boolean hasKeyword() {
		return keyword != null;
	}
}
