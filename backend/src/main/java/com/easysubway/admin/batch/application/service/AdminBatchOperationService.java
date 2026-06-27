package com.easysubway.admin.batch.application.service;

import com.easysubway.admin.batch.domain.AdminBatchJob;
import com.easysubway.collection.application.port.in.DataCollectionUseCase;
import com.easysubway.collection.application.port.in.RunDataCollectionCommand;
import com.easysubway.collection.application.port.out.LoadDataCollectionRunPort;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionStatus;
import com.easysubway.collection.domain.InvalidDataCollectionException;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AdminBatchOperationService {

	private final LoadDataCollectionRunPort loadDataCollectionRunPort;
	private final DataCollectionUseCase dataCollectionUseCase;

	public AdminBatchOperationService(
		LoadDataCollectionRunPort loadDataCollectionRunPort,
		DataCollectionUseCase dataCollectionUseCase
	) {
		this.loadDataCollectionRunPort = loadDataCollectionRunPort;
		this.dataCollectionUseCase = dataCollectionUseCase;
	}

	public List<AdminBatchJob> listJobs() {
		return AdminBatchJob.all();
	}

	public List<DataCollectionRun> listExecutions(int limit) {
		return loadDataCollectionRunPort.loadRecentRuns(limit);
	}

	public List<DataCollectionRun> listExecutions(int limit, int offset) {
		return loadDataCollectionRunPort.loadRecentRuns(limit, offset);
	}

	public DataCollectionRun retry(String jobId, String runId, String requestedBy) {
		AdminBatchJob job = AdminBatchJob.require(jobId);
		DataCollectionRun failedRun = loadDataCollectionRunPort.loadRun(runId)
			.orElseThrow(() -> new InvalidDataCollectionException("재처리할 배치 실행을 찾을 수 없습니다."));
		if (failedRun.source() != job.source()) {
			throw new InvalidDataCollectionException("배치 실행과 작업 registry가 일치하지 않습니다.");
		}
		if (!job.retryEnabled()
			|| failedRun.status() != DataCollectionStatus.FAILED
			|| !failedRun.retryable()) {
			throw new InvalidDataCollectionException("재처리할 수 없는 배치 실행입니다.");
		}
		return dataCollectionUseCase.runCollection(new RunDataCollectionCommand(job.source(), requestedBy));
	}
}
