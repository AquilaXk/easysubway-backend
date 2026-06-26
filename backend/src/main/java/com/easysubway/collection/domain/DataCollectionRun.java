package com.easysubway.collection.domain;

import java.time.LocalDateTime;
import java.util.List;

public record DataCollectionRun(
	String runId,
	DataCollectionSource source,
	DataCollectionStatus status,
	String requestedBy,
	LocalDateTime startedAt,
	LocalDateTime completedAt,
	int collectedCount,
	String failureMessage,
	boolean retryable,
	String operatorAction,
	List<DataCollectionRunStep> steps
) {

	public DataCollectionRun(
		String runId,
		DataCollectionSource source,
		DataCollectionStatus status,
		String requestedBy,
		LocalDateTime startedAt,
		LocalDateTime completedAt,
		int collectedCount,
		String failureMessage,
		boolean retryable,
		String operatorAction
	) {
		this(
			runId,
			source,
			status,
			requestedBy,
			startedAt,
			completedAt,
			collectedCount,
			failureMessage,
			retryable,
			operatorAction,
			List.of()
		);
	}

	public DataCollectionRun {
		if (runId == null || runId.isBlank()) {
			throw new InvalidDataCollectionException("실행 식별자가 필요합니다.");
		}
		if (source == null) {
			throw new InvalidDataCollectionException("수집 대상을 선택해야 합니다.");
		}
		if (status == null) {
			throw new InvalidDataCollectionException("실행 상태가 필요합니다.");
		}
		if (requestedBy == null || requestedBy.isBlank()) {
			throw new InvalidDataCollectionException("요청자 식별자가 필요합니다.");
		}
		if (startedAt == null) {
			throw new InvalidDataCollectionException("시작 시간이 필요합니다.");
		}
		if (collectedCount < 0) {
			throw new InvalidDataCollectionException("수집 건수는 0 이상이어야 합니다.");
		}
		if (operatorAction == null || operatorAction.isBlank()) {
			throw new InvalidDataCollectionException("운영자 다음 행동 안내가 필요합니다.");
		}
		if (steps == null) {
			throw new InvalidDataCollectionException("수집 단계 이력이 필요합니다.");
		}
		if (status == DataCollectionStatus.RUNNING && completedAt != null) {
			throw new InvalidDataCollectionException("실행 중인 실행은 완료 시간을 포함할 수 없습니다.");
		}
		if (status == DataCollectionStatus.COMPLETED && completedAt == null) {
			throw new InvalidDataCollectionException("완료된 실행은 완료 시간이 필요합니다.");
		}
		runId = runId.trim();
		requestedBy = requestedBy.trim();
		if (failureMessage != null) {
			failureMessage = failureMessage.trim();
		}
		operatorAction = operatorAction.trim();
		steps = List.copyOf(steps);
		if (status == DataCollectionStatus.COMPLETED && failureMessage != null && !failureMessage.isBlank()) {
			throw new InvalidDataCollectionException("완료된 실행은 실패 사유를 포함할 수 없습니다.");
		}
		if (status == DataCollectionStatus.FAILED && (failureMessage == null || failureMessage.isBlank())) {
			throw new InvalidDataCollectionException("실패한 실행은 실패 사유가 필요합니다.");
		}
		if (status != DataCollectionStatus.FAILED && retryable) {
			throw new InvalidDataCollectionException("실패하지 않은 실행은 재시도 대상으로 표시할 수 없습니다.");
		}
	}
}
