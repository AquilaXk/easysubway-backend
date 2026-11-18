package com.easysubway.collection.application.port.out;

import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionSource;
import java.time.LocalDateTime;

public interface SaveDataCollectionRunPort {

	DataCollectionRun saveRun(DataCollectionRun run);

	boolean failOrphanedRunningRun(
		DataCollectionSource source,
		LocalDateTime staleBefore,
		LocalDateTime failedAt,
		String failureMessage,
		String operatorAction
	);
}
