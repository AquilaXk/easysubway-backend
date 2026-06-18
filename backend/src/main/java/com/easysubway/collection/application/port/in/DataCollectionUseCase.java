package com.easysubway.collection.application.port.in;

import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionSource;
import java.util.List;
import java.util.Optional;

public interface DataCollectionUseCase {

	DataCollectionRun runCollection(RunDataCollectionCommand command);

	Optional<DataCollectionRun> getLatestCompletedRun(DataCollectionSource source);

	List<DataCollectionRun> listRecentRuns(int limit);
}
