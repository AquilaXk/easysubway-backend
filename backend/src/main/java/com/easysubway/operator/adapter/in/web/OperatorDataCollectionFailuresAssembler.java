package com.easysubway.operator.adapter.in.web;

import com.easysubway.collection.application.port.in.DataCollectionUseCase;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class OperatorDataCollectionFailuresAssembler {

	private static final int DEFAULT_RECENT_RUN_LIMIT = 20;

	private final DataCollectionUseCase dataCollectionUseCase;

	OperatorDataCollectionFailuresAssembler(DataCollectionUseCase dataCollectionUseCase) {
		this.dataCollectionUseCase = dataCollectionUseCase;
	}

	OperatorDataCollectionFailuresView assemble() {
		List<DataCollectionRun> runs = dataCollectionUseCase.listRecentRuns(DEFAULT_RECENT_RUN_LIMIT);
		long failedRunCount = runs.stream()
			.filter(run -> run.status() == DataCollectionStatus.FAILED)
			.count();
		long retryableRunCount = runs.stream()
			.filter(DataCollectionRun::retryable)
			.count();
		List<OperatorDataCollectionFailuresView.DataCollectionRunRow> rows = runs.stream()
			.map(OperatorDataCollectionFailuresAssembler::row)
			.toList();
		return new OperatorDataCollectionFailuresView(
			rows.size(),
			failedRunCount,
			retryableRunCount,
			rows
		);
	}

	private static OperatorDataCollectionFailuresView.DataCollectionRunRow row(DataCollectionRun run) {
		return new OperatorDataCollectionFailuresView.DataCollectionRunRow(
			sourceLabel(run.source()),
			statusLabel(run.status()),
			timeLabel(run.startedAt()),
			timeLabel(run.completedAt()),
			run.collectedCount(),
			failureLabel(run.failureMessage()),
			run.retryable(),
			run.operatorAction()
		);
	}

	private static String sourceLabel(DataCollectionSource source) {
		return switch (source) {
			case TRANSIT_MASTER -> "도시철도 마스터";
		};
	}

	private static String statusLabel(DataCollectionStatus status) {
		return switch (status) {
			case RUNNING -> "실행 중";
			case COMPLETED -> "완료";
			case FAILED -> "실패";
		};
	}

	private static String timeLabel(LocalDateTime value) {
		return value == null ? "-" : value.toString();
	}

	private static String failureLabel(String value) {
		return value == null || value.isBlank() ? "-" : value;
	}
}
