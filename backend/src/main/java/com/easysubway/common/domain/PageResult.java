package com.easysubway.common.domain;

import java.util.List;

public record PageResult<T>(
	List<T> items,
	int page,
	int size,
	boolean hasNext
) {

	public PageResult {
		items = List.copyOf(items);
	}
}
