package com.easysubway.admin.batch.application.service;

import com.easysubway.admin.batch.domain.AdminBatchJob;
import com.easysubway.collection.application.port.in.DataCollectionUseCase;
import com.easysubway.collection.application.port.in.RunDataCollectionCommand;
import com.easysubway.collection.application.port.out.LoadDataCollectionRunPort;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStatus;
import com.easysubway.collection.domain.InvalidDataCollectionException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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

	public Set<DataCollectionSource> runningSources() {
		return listJobs()
			.stream()
			.filter(job -> loadDataCollectionRunPort.loadRunningRun(job.source()).isPresent())
			.map(AdminBatchJob::source)
			.collect(Collectors.toSet());
	}

	/**
	 * 잡별 최근 {@code perJob}회 실행 이력을 오래된 순으로 돌려준다. 잡 registry는 작은 고정 enum이라
	 * 잡별 반복 조회 대신 한 번의 벌크 조회(최근 window) 후 source별로 그룹핑해 N+1을 피한다.
	 */
	public List<JobExecutionHistory> jobHistories(int perJob) {
		int limitPerJob = Math.max(perJob, 1);
		List<AdminBatchJob> jobs = listJobs();
		int window = limitPerJob * Math.max(jobs.size(), 1);
		Map<DataCollectionSource, List<DataCollectionRun>> bySource = loadDataCollectionRunPort.loadRecentRuns(window)
			.stream()
			.collect(Collectors.groupingBy(DataCollectionRun::source));
		return jobs.stream()
			.map(job -> new JobExecutionHistory(
				job.id(),
				job.label(),
				recentExecutions(bySource.getOrDefault(job.source(), List.of()), limitPerJob)))
			.toList();
	}

	private static List<RunExecution> recentExecutions(List<DataCollectionRun> runs, int limit) {
		// loadRecentRuns는 최근순(desc)이라 limit로 자른 뒤 뒤집어 오래된→최근 순으로 만든다.
		List<RunExecution> executions = runs.stream()
			.limit(limit)
			.map(RunExecution::from)
			.collect(Collectors.toCollection(ArrayList::new));
		Collections.reverse(executions);
		return executions;
	}

	public record JobExecutionHistory(String jobId, String label, List<RunExecution> executions) {

		public long successCount() {
			return executions.stream().filter(RunExecution::success).count();
		}

		public long failureCount() {
			return executions.stream().filter(execution -> execution.status() == DataCollectionStatus.FAILED).count();
		}
	}

	public record RunExecution(LocalDateTime startedAt, DataCollectionStatus status, Long durationMillis) {

		static RunExecution from(DataCollectionRun run) {
			Long durationMillis = run.completedAt() == null
				? null
				: Math.max(0L, Duration.between(run.startedAt(), run.completedAt()).toMillis());
			return new RunExecution(run.startedAt(), run.status(), durationMillis);
		}

		public boolean success() {
			return status == DataCollectionStatus.COMPLETED;
		}
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

	public DataCollectionRun run(String jobId, String requestedBy) {
		AdminBatchJob job = AdminBatchJob.require(jobId);
		if (loadDataCollectionRunPort.loadRunningRun(job.source()).isPresent()) {
			throw new InvalidDataCollectionException("같은 수집 대상이 이미 실행 중입니다.");
		}
		return dataCollectionUseCase.runCollection(new RunDataCollectionCommand(job.source(), requestedBy));
	}
}
