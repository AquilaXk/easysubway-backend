package com.easysubway.collection.application.port.in;

import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.InvalidDataCollectionException;

public record RunDataCollectionCommand(
	DataCollectionSource source,
	String requestedBy
) {

	public RunDataCollectionCommand {
		if (source == null) {
			throw new InvalidDataCollectionException("수집 대상을 선택해야 합니다.");
		}
		if (requestedBy == null || requestedBy.isBlank()) {
			throw new InvalidDataCollectionException("요청자 식별자가 필요합니다.");
		}
		requestedBy = requestedBy.trim();
	}
}
