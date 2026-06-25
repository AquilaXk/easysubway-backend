package com.easysubway.common.web.pagination;

import java.util.List;
import org.egovframe.rte.ptl.mvc.tags.ui.pagination.PaginationInfo;

public record EgovPaginationView(
	int page,
	int size,
	int totalRecordCount,
	boolean hasPrevious,
	boolean hasNext,
	int previousPage,
	int nextPage,
	boolean hasPages,
	List<PageLink> pageLinks
) {

	private static final int PAGE_BLOCK_SIZE = 5;

	public EgovPaginationView {
		pageLinks = List.copyOf(pageLinks);
	}

	public static EgovPaginationView from(int requestedPage, int size, long totalRecordCount) {
		int safeSize = Math.max(size, 1);
		int safeTotalRecordCount = (int) Math.min(Math.max(totalRecordCount, 0), Integer.MAX_VALUE);
		int totalPageCount = safeTotalRecordCount == 0
			? 0
			: (int) Math.ceil((double) safeTotalRecordCount / safeSize);
		int normalizedPage = totalPageCount == 0
			? 0
			: Math.min(Math.max(requestedPage, 0), totalPageCount - 1);

		PaginationInfo paginationInfo = new PaginationInfo();
		paginationInfo.setCurrentPageNo(normalizedPage + 1);
		paginationInfo.setRecordCountPerPage(safeSize);
		paginationInfo.setPageSize(PAGE_BLOCK_SIZE);
		paginationInfo.setTotalRecordCount(safeTotalRecordCount);

		List<PageLink> pageLinks = totalPageCount == 0
			? List.of()
			: java.util.stream.IntStream.rangeClosed(
					paginationInfo.getFirstPageNoOnPageList(),
					paginationInfo.getLastPageNoOnPageList()
				)
				.mapToObj(pageNo -> new PageLink(pageNo - 1, Integer.toString(pageNo), pageNo == normalizedPage + 1))
				.toList();

		return new EgovPaginationView(
			normalizedPage,
			safeSize,
			safeTotalRecordCount,
			normalizedPage > 0,
			totalPageCount > 0 && normalizedPage < totalPageCount - 1,
			Math.max(normalizedPage - 1, 0),
			totalPageCount == 0 ? 0 : Math.min(normalizedPage + 1, totalPageCount - 1),
			!pageLinks.isEmpty(),
			pageLinks
		);
	}

	public record PageLink(int page, String label, boolean current) {
	}
}
