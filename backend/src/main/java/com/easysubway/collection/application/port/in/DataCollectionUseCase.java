package com.easysubway.collection.application.port.in;

import com.easysubway.collection.domain.DataCollectionRun;
import java.util.List;

public interface DataCollectionUseCase {

	DataCollectionRun runCollection(RunDataCollectionCommand command);

	List<DataCollectionRun> listRecentRuns(int limit);
}
