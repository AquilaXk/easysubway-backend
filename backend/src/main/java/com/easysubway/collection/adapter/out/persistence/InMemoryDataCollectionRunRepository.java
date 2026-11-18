package com.easysubway.collection.adapter.out.persistence;

import com.easysubway.collection.application.port.out.LoadDataCollectionRunPort;
import com.easysubway.collection.application.port.out.SaveDataCollectionRunPort;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStatus;
import com.easysubway.collection.domain.InvalidDataCollectionException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!prod & !staging & !release & !prod-like")
public class InMemoryDataCollectionRunRepository implements
	LoadDataCollectionRunPort,
	SaveDataCollectionRunPort {

	private final List<DataCollectionRun> runs = new CopyOnWriteArrayList<>();

	@Override
	public synchronized DataCollectionRun saveRun(DataCollectionRun run) {
		if (run.status() == DataCollectionStatus.RUNNING && runs.stream()
			.anyMatch(savedRun -> savedRun.status() == DataCollectionStatus.RUNNING
				&& savedRun.source() == run.source()
				&& !savedRun.runId().equals(run.runId()))) {
			throw new InvalidDataCollectionException("같은 수집 대상이 이미 실행 중입니다.");
		}
		runs.removeIf(savedRun -> savedRun.runId().equals(run.runId()));
		runs.add(run);
		return run;
	}

	@Override
	public synchronized boolean failOrphanedRunningRun(
		DataCollectionSource source,
		LocalDateTime staleBefore,
		LocalDateTime failedAt,
		String failureMessage,
		String operatorAction
	) {
		Optional<DataCollectionRun> orphaned = runs.stream()
			.filter(run -> run.source() == source)
			.filter(run -> run.status() == DataCollectionStatus.RUNNING)
			.filter(run -> !run.startedAt().isAfter(staleBefore))
			.findFirst();
		if (orphaned.isEmpty()) {
			return false;
		}
		DataCollectionRun run = orphaned.orElseThrow();
		saveRun(new DataCollectionRun(
			run.runId(),
			run.source(),
			DataCollectionStatus.FAILED,
			run.requestedBy(),
			run.startedAt(),
			failedAt,
			run.collectedCount(),
			failureMessage,
			true,
			operatorAction,
			run.steps()
		));
		return true;
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
	public Optional<DataCollectionRun> loadRunningRun(DataCollectionSource source) {
		return runs.stream()
			.filter(run -> run.source() == source)
			.filter(run -> run.status() == DataCollectionStatus.RUNNING)
			.findFirst();
	}

	@Override
	public List<DataCollectionRun> loadRecentRuns(int limit) {
		return loadRecentRuns(limit, 0);
	}

	@Override
	public List<DataCollectionRun> loadRecentRuns(int limit, int offset) {
		var recentRuns = new ArrayList<>(runs);
		Collections.reverse(recentRuns);
		return recentRuns.stream()
			.skip(Math.max(offset, 0))
			.limit(Math.max(limit, 0))
			.toList();
	}
}
