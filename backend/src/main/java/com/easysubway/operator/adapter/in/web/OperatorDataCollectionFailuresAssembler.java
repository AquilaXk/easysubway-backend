package com.easysubway.operator.adapter.in.web;

import com.easysubway.collection.application.port.in.DataCollectionUseCase;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
class OperatorDataCollectionFailuresAssembler {

	private static final int DEFAULT_RECENT_RUN_LIMIT = 20;
	private static final Duration FRESHNESS_THRESHOLD = Duration.ofHours(24);

	private final DataCollectionUseCase dataCollectionUseCase;
	private final Clock clock;

	@Autowired
	OperatorDataCollectionFailuresAssembler(
		DataCollectionUseCase dataCollectionUseCase,
		ObjectProvider<Clock> clockProvider
	) {
		this(dataCollectionUseCase, clockProvider.getIfAvailable(Clock::systemDefaultZone));
	}

	OperatorDataCollectionFailuresAssembler(DataCollectionUseCase dataCollectionUseCase, Clock clock) {
		this.dataCollectionUseCase = dataCollectionUseCase;
		this.clock = clock;
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
		Optional<LocalDateTime> latestCompletedAt = dataCollectionUseCase
			.getLatestCompletedRun(DataCollectionSource.TRANSIT_MASTER)
			.map(DataCollectionRun::completedAt);
		return new OperatorDataCollectionFailuresView(
			rows.size(),
			failedRunCount,
			retryableRunCount,
			latestCompletedAt.map(OperatorDataCollectionFailuresAssembler::timeLabel).orElse("-"),
			freshnessAlertLabel(latestCompletedAt),
			freshnessAlertDescription(latestCompletedAt),
			freshnessAlertClass(latestCompletedAt),
			rows
		);
	}

	private String freshnessAlertLabel(Optional<LocalDateTime> latestCompletedAt) {
		return isFresh(latestCompletedAt) ? "정상" : "점검 필요";
	}

	private String freshnessAlertDescription(Optional<LocalDateTime> latestCompletedAt) {
		if (latestCompletedAt.isEmpty()) {
			return "데이터 수집 완료 기록이 없습니다.";
		}
		if (isFresh(latestCompletedAt)) {
			return "최근 24시간 이내 데이터 수집 완료 기록이 있습니다.";
		}
		return "도시철도 마스터 수집 완료 기록이 24시간 이상 갱신되지 않았습니다.";
	}

	private String freshnessAlertClass(Optional<LocalDateTime> latestCompletedAt) {
		return isFresh(latestCompletedAt) ? "ok" : "stale";
	}

	private boolean isFresh(Optional<LocalDateTime> latestCompletedAt) {
		if (latestCompletedAt.isEmpty()) {
			return false;
		}
		LocalDateTime staleAt = latestCompletedAt.get().plus(FRESHNESS_THRESHOLD);
		return staleAt.isAfter(LocalDateTime.now(clock));
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
