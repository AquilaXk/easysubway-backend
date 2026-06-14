package com.easysubway.collection.application.port.out;

import com.easysubway.collection.domain.DataCollectionRun;

public interface SaveDataCollectionRunPort {

	DataCollectionRun saveRun(DataCollectionRun run);
}
