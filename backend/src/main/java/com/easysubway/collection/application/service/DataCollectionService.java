package com.easysubway.collection.application.service;

import com.easysubway.collection.application.port.in.DataCollectionUseCase;
import com.easysubway.collection.application.port.in.RunDataCollectionCommand;
import com.easysubway.collection.application.port.out.LoadDataCollectionRunPort;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.InvalidDataCollectionException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class DataCollectionService implements DataCollectionUseCase {

	private final LoadDataCollectionRunPort loadDataCollectionRunPort;
	private final JobLauncher jobLauncher;
	private final Job transitMasterCollectionJob;

	public DataCollectionService(
		LoadDataCollectionRunPort loadDataCollectionRunPort,
		JobLauncher jobLauncher,
		@Qualifier("transitMasterCollectionJob") Job transitMasterCollectionJob
	) {
		this.loadDataCollectionRunPort = loadDataCollectionRunPort;
		this.jobLauncher = jobLauncher;
		this.transitMasterCollectionJob = transitMasterCollectionJob;
	}

	@Override
	public DataCollectionRun runCollection(RunDataCollectionCommand command) {
		return switch (command.source()) {
			case TRANSIT_MASTER -> launchTransitMasterCollection(command.requestedBy());
		};
	}

	@Override
	public List<DataCollectionRun> listRecentRuns(int limit) {
		return loadDataCollectionRunPort.loadRecentRuns(limit);
	}

	@Override
	public Optional<DataCollectionRun> getLatestCompletedRun(DataCollectionSource source) {
		return loadDataCollectionRunPort.loadLatestCompletedRun(source);
	}

	private DataCollectionRun launchTransitMasterCollection(String requestedBy) {
		String runId = "collection-" + UUID.randomUUID();
		var parameters = new JobParametersBuilder()
			.addString("runId", runId)
			.addString("requestedBy", requestedBy)
			.addString("source", DataCollectionSource.TRANSIT_MASTER.name())
			.addLong("run.id", System.nanoTime())
			.toJobParameters();
		try {
			jobLauncher.run(transitMasterCollectionJob, parameters);
		} catch (JobExecutionException exception) {
			throw new InvalidDataCollectionException("데이터 수집 배치를 실행하지 못했습니다.", exception);
		}
		return loadDataCollectionRunPort.loadRun(runId)
			.orElseThrow(() -> new InvalidDataCollectionException("데이터 수집 실행 기록을 찾을 수 없습니다."));
	}
}
