package com.easysubway.collection.application.port.out;

import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionSource;
import java.util.List;
import java.util.Optional;

public interface LoadDataCollectionRunPort {

	Optional<DataCollectionRun> loadRun(String runId);

	Optional<DataCollectionRun> loadLatestCompletedRun(DataCollectionSource source);

	List<DataCollectionRun> loadRecentRuns(int limit);
}
