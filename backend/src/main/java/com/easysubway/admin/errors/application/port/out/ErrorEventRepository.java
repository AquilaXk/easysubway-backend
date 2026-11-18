package com.easysubway.admin.errors.application.port.out;

import com.easysubway.admin.errors.application.ErrorEventQuery;
import com.easysubway.admin.errors.domain.ErrorEvent;
import com.easysubway.common.domain.PageResult;
import java.time.Instant;

public interface ErrorEventRepository {

	void upsertOccurrence(ErrorEvent event);

	PageResult<ErrorEvent> search(ErrorEventQuery query);

	long count(ErrorEventQuery query);

	int deleteOlderThan(Instant cutoffExclusive);
}
