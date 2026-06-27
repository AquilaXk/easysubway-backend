package com.easysubway.common.web.pagination;

public record AdminPageRequest(int page, int size) {

	public static final int DEFAULT_PAGE = 0;
	public static final int DEFAULT_SIZE = 20;
	public static final int MAX_SIZE = 50;

	public AdminPageRequest {
		size = Math.min(Math.max(size, 1), MAX_SIZE);
		page = Math.max(page, 0);
		page = Math.min(page, Integer.MAX_VALUE / size);
	}

	public static AdminPageRequest of(Integer page, Integer size) {
		return new AdminPageRequest(
			page == null ? DEFAULT_PAGE : page,
			size == null ? DEFAULT_SIZE : size
		);
	}

	public int offset() {
		return page * size;
	}

	public int limitForHasNext() {
		return size + 1;
	}
}
