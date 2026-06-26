package com.easysubway.collection.adapter.out.persistence;

import com.easysubway.collection.application.port.out.LoadDataCollectionRunPort;
import com.easysubway.collection.application.port.out.SaveDataCollectionRunPort;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!prod")
public class InMemoryDataCollectionRunRepository implements
	LoadDataCollectionRunPort,
	SaveDataCollectionRunPort {

	private final List<DataCollectionRun> runs = new CopyOnWriteArrayList<>();

	@Override
	public DataCollectionRun saveRun(DataCollectionRun run) {
		runs.removeIf(savedRun -> savedRun.runId().equals(run.runId()));
		runs.add(run);
		return run;
	}

	@Override
	public Optional<DataCollectionRun> loadRun(String runId) {
		return runs.stream()
			.filter(run -> run.runId().equals(runId))
			.findFirst();
	}

	@Override
	public Optional<DataCollectionRun> loadLatestCompletedRun(DataCollectionSource source) {
		return runs.stream()
			.filter(run -> run.source() == source)
			.filter(run -> run.status() == DataCollectionStatus.COMPLETED)
			.filter(run -> run.completedAt() != null)
			.max(Comparator
				.comparing(DataCollectionRun::completedAt)
				.thenComparing(DataCollectionRun::runId));
	}

	@Override
	public List<DataCollectionRun> loadRecentRuns(int limit) {
		var recentRuns = new ArrayList<>(runs);
		Collections.reverse(recentRuns);
		return recentRuns.stream()
			.limit(Math.max(limit, 0))
			.toList();
	}
}
