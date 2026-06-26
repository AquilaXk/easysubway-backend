package com.easysubway.collection.application.service;

import com.easysubway.collection.application.port.out.FetchTransitMasterCollectionSourcePort;
import com.easysubway.collection.application.port.out.SaveDataCollectionRunPort;
import com.easysubway.collection.application.port.out.TransitMasterCollectionSnapshot;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionRunStep;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStepStatus;
import com.easysubway.collection.domain.DataCollectionStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DataCollectionRunRecorder {

	private final FetchTransitMasterCollectionSourcePort fetchTransitMasterCollectionSourcePort;
	private final SaveDataCollectionRunPort saveDataCollectionRunPort;
	private final Clock clock;
	private static final String COMPLETED_OPERATOR_ACTION = "수집이 완료되었습니다. 최근 데이터 품질 화면에서 반영 결과를 확인하세요.";
	private static final String FAILED_OPERATOR_ACTION = "일시 오류일 수 있습니다. 실패 사유를 확인한 뒤 같은 수집 대상을 다시 실행하세요.";

	@Autowired
	public DataCollectionRunRecorder(
		FetchTransitMasterCollectionSourcePort fetchTransitMasterCollectionSourcePort,
		SaveDataCollectionRunPort saveDataCollectionRunPort
	) {
		this(fetchTransitMasterCollectionSourcePort, saveDataCollectionRunPort, Clock.systemDefaultZone());
	}

	public DataCollectionRunRecorder(
		FetchTransitMasterCollectionSourcePort fetchTransitMasterCollectionSourcePort,
		SaveDataCollectionRunPort saveDataCollectionRunPort,
		Clock clock
	) {
		this.fetchTransitMasterCollectionSourcePort = fetchTransitMasterCollectionSourcePort;
		this.saveDataCollectionRunPort = saveDataCollectionRunPort;
		this.clock = clock;
	}

	public DataCollectionRun recordTransitMasterRun(String runId, String requestedBy) {
		LocalDateTime startedAt = LocalDateTime.now(clock);
		var steps = new ArrayList<DataCollectionRunStep>();
		try {
			TransitMasterCollectionSnapshot snapshot = fetchTransitMasterCollectionSourcePort.fetch();
			steps.add(completedStep("FETCH", snapshot.inputSource(), snapshot.artifactReference(), snapshot.checksum(), snapshot.recordCount()));
			steps.add(skippedStep("ARCHIVE", snapshot.artifactReference(), snapshot.checksum()));
			validate(snapshot, steps);
			steps.add(skippedStep("PARSE", snapshot.artifactReference(), snapshot.checksum()));
			steps.add(skippedStep("DIFF", snapshot.artifactReference(), snapshot.checksum()));
			steps.add(skippedStep("STAGE", snapshot.artifactReference(), snapshot.checksum()));
			steps.add(manualStep("PUBLISH", snapshot.artifactReference(), snapshot.checksum()));
			steps.add(manualStep("ACTIVATE", snapshot.artifactReference(), snapshot.checksum()));
			LocalDateTime completedAt = LocalDateTime.now(clock);
			var run = new DataCollectionRun(
				runId,
				DataCollectionSource.TRANSIT_MASTER,
				DataCollectionStatus.COMPLETED,
				requestedBy,
				startedAt,
				completedAt,
				snapshot.recordCount(),
				null,
				false,
				COMPLETED_OPERATOR_ACTION,
				steps
			);
			return saveDataCollectionRunPort.saveRun(run);
		} catch (RuntimeException exception) {
			try {
				saveFailedRun(runId, requestedBy, startedAt, exception, steps);
			} catch (RuntimeException saveException) {
				// 실패 기록 저장까지 실패하더라도 실제 수집 실패 원인을 호출자에게 보존한다.
				exception.addSuppressed(saveException);
			}
			throw exception;
		}
	}

	private void saveFailedRun(
		String runId,
		String requestedBy,
		LocalDateTime startedAt,
		RuntimeException exception,
		List<DataCollectionRunStep> steps
	) {
		if (steps.isEmpty()) {
			steps.add(new DataCollectionRunStep("FETCH", DataCollectionStepStatus.FAILED, null, null, null, 0, failureMessageOf(exception)));
		}
		// Batch가 FAILED로 끝나도 관리자 실행 이력에서 원인을 확인할 수 있게 실패 기록을 먼저 남긴다.
		saveDataCollectionRunPort.saveRun(new DataCollectionRun(
			runId,
			DataCollectionSource.TRANSIT_MASTER,
			DataCollectionStatus.FAILED,
			requestedBy,
			startedAt,
			LocalDateTime.now(clock),
			0,
			failureMessageOf(exception),
			true,
			FAILED_OPERATOR_ACTION,
			steps
		));
	}

	private String failureMessageOf(RuntimeException exception) {
		if (exception.getMessage() == null || exception.getMessage().isBlank()) {
			return exception.getClass().getSimpleName();
		}
		return exception.getMessage();
	}

	private static void validate(TransitMasterCollectionSnapshot snapshot, List<DataCollectionRunStep> steps) {
		if (snapshot.recordCount() <= 0) {
			var failureMessage = "공식 출처 수집 결과가 비어 있습니다.";
			steps.add(new DataCollectionRunStep(
				"VALIDATE",
				DataCollectionStepStatus.FAILED,
				snapshot.artifactReference(),
				null,
				snapshot.checksum(),
				0,
				failureMessage
			));
			throw new IllegalStateException(failureMessage);
		}
		steps.add(completedStep("VALIDATE", snapshot.artifactReference(), "validation://transit-master", snapshot.checksum(), snapshot.recordCount()));
	}

	private static DataCollectionRunStep completedStep(
		String name,
		String inputSource,
		String artifactReference,
		String checksum,
		int recordCount
	) {
		return new DataCollectionRunStep(
			name,
			DataCollectionStepStatus.COMPLETED,
			inputSource,
			artifactReference,
			checksum,
			recordCount,
			null
		);
	}

	private static DataCollectionRunStep manualStep(String name, String inputSource, String checksum) {
		return new DataCollectionRunStep(
			name,
			DataCollectionStepStatus.MANUAL_REQUIRED,
			inputSource,
			"manual-required://%s".formatted(name.toLowerCase()),
			checksum,
			0,
			null
		);
	}

	private static DataCollectionRunStep skippedStep(String name, String inputSource, String checksum) {
		return new DataCollectionRunStep(
			name,
			DataCollectionStepStatus.SKIPPED,
			inputSource,
			null,
			checksum,
			0,
			null
		);
	}
}
