package com.easysubway.collection.application.service;

import com.easysubway.collection.application.port.out.SaveDataCollectionRunPort;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStatus;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DataCollectionRunRecorder {

	private final LoadTransitMasterPort loadTransitMasterPort;
	private final SaveDataCollectionRunPort saveDataCollectionRunPort;
	private final Clock clock;
	private static final String COMPLETED_OPERATOR_ACTION = "수집이 완료되었습니다. 최근 데이터 품질 화면에서 반영 결과를 확인하세요.";
	private static final String FAILED_OPERATOR_ACTION = "일시 오류일 수 있습니다. 실패 사유를 확인한 뒤 같은 수집 대상을 다시 실행하세요.";

	@Autowired
	public DataCollectionRunRecorder(
		LoadTransitMasterPort loadTransitMasterPort,
		SaveDataCollectionRunPort saveDataCollectionRunPort
	) {
		this(loadTransitMasterPort, saveDataCollectionRunPort, Clock.systemDefaultZone());
	}

	public DataCollectionRunRecorder(
		LoadTransitMasterPort loadTransitMasterPort,
		SaveDataCollectionRunPort saveDataCollectionRunPort,
		Clock clock
	) {
		this.loadTransitMasterPort = loadTransitMasterPort;
		this.saveDataCollectionRunPort = saveDataCollectionRunPort;
		this.clock = clock;
	}

	public DataCollectionRun recordTransitMasterRun(String runId, String requestedBy) {
		LocalDateTime startedAt = LocalDateTime.now(clock);
		try {
			int collectedCount = countTransitMasterRecords();
			LocalDateTime completedAt = LocalDateTime.now(clock);
			var run = new DataCollectionRun(
				runId,
				DataCollectionSource.TRANSIT_MASTER,
				DataCollectionStatus.COMPLETED,
				requestedBy,
				startedAt,
				completedAt,
				collectedCount,
				null,
				false,
				COMPLETED_OPERATOR_ACTION
			);
			return saveDataCollectionRunPort.saveRun(run);
		} catch (RuntimeException exception) {
			try {
				saveFailedRun(runId, requestedBy, startedAt, exception);
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
		RuntimeException exception
	) {
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
			FAILED_OPERATOR_ACTION
		));
	}

	private String failureMessageOf(RuntimeException exception) {
		if (exception.getMessage() == null || exception.getMessage().isBlank()) {
			return exception.getClass().getSimpleName();
		}
		return exception.getMessage();
	}

	private int countTransitMasterRecords() {
		// 외부 수집기를 붙이기 전에는 현재 마스터 데이터 로딩 경로가 읽히는지 실행 기록으로 검증한다.
		return loadTransitMasterPort.loadOperators().size()
			+ loadTransitMasterPort.loadLines().size()
			+ loadTransitMasterPort.loadStations().size()
			+ loadTransitMasterPort.loadStationLines().size()
			+ loadTransitMasterPort.loadStationExits().size()
			+ loadTransitMasterPort.loadAccessibilityFacilities().size();
	}
}
