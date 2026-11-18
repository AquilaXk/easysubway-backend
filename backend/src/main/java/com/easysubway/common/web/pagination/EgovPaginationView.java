package com.easysubway.common.web.pagination;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.egovframe.rte.ptl.mvc.tags.ui.pagination.PaginationInfo;
import org.springframework.web.util.UriComponentsBuilder;

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

	public static EgovPaginationView fromSlice(int page, int size, int fetchedCount) {
		int safePage = Math.max(page, 0);
		int safeSize = Math.max(size, 1);
		int safeFetchedCount = Math.max(fetchedCount, 0);
		long minimumTotal = (long) safePage * safeSize + safeFetchedCount;
		if (safePage > 0 && safeFetchedCount == 0) {
			minimumTotal++;
		}
		return from(page, size, minimumTotal);
	}

	public <T> List<T> visibleItems(List<T> fetchedItems) {
		return fetchedItems.stream().limit(size).toList();
	}

	public <T> List<T> pageItems(List<T> allItems) {
		return allItems.stream()
			.skip((long) page * size)
			.limit(size)
			.toList();
	}

	public record PageLink(int page, String label, boolean current) {
	}

	public PaginationLinks links(String path, Map<String, ?> filters) {
		return new PaginationLinks(
			href(path, filters, previousPage),
			href(path, filters, nextPage),
			pageLinks.stream()
				.map(link -> new PageHref(link.page(), link.label(), link.current(), href(path, filters, link.page())))
				.toList()
		);
	}

	private String href(String path, Map<String, ?> filters, int targetPage) {
		Map<String, Object> query = new LinkedHashMap<>();
		filters.forEach((key, value) -> {
			if (value != null && !value.toString().isBlank()) {
				query.put(key, value);
			}
		});
		query.put("page", targetPage);
		query.put("size", size);
		UriComponentsBuilder builder = UriComponentsBuilder.fromPath(path);
		query.forEach(builder::queryParam);
		return builder.build().encode().toUriString();
	}

	public record PaginationLinks(String previousHref, String nextHref, List<PageHref> pages) {
		public PaginationLinks {
			pages = List.copyOf(pages);
		}
	}

	public record PageHref(int page, String label, boolean current, String href) {
	}
}
