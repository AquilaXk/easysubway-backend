package com.easysubway.collection.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.easysubway.collection.adapter.out.persistence.InMemoryDataCollectionRunRepository;
import com.easysubway.collection.application.port.in.RunDataCollectionCommand;
import com.easysubway.collection.application.port.out.SaveDataCollectionRunPort;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStatus;
import com.easysubway.collection.domain.InvalidDataCollectionException;
import com.easysubway.transit.adapter.out.persistence.InMemoryTransitMasterRepository;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("데이터 수집 실행 기록 작성기")
class DataCollectionRunRecorderTest {

	private static final Clock CLOCK = Clock.fixed(
		Instant.parse("2026-06-14T02:00:00Z"),
		ZoneId.of("Asia/Seoul")
	);

	private final InMemoryDataCollectionRunRepository repository = new InMemoryDataCollectionRunRepository();
	private final DataCollectionRunRecorder recorder = new DataCollectionRunRecorder(
		new InMemoryTransitMasterRepository(),
		repository,
		CLOCK
	);

	@Test
	@DisplayName("도시철도 마스터 데이터 수집 실행 기록을 완료 상태로 남긴다")
	void recordTransitMasterRunStoresCompletedRun() {
		var run = recorder.recordTransitMasterRun("collection-test", "admin-user");

		assertThat(run.runId()).isEqualTo("collection-test");
		assertThat(run.source()).isEqualTo(DataCollectionSource.TRANSIT_MASTER);
		assertThat(run.status()).isEqualTo(DataCollectionStatus.COMPLETED);
		assertThat(run.requestedBy()).isEqualTo("admin-user");
		assertThat(run.startedAt()).isEqualTo(LocalDateTime.of(2026, 6, 14, 11, 0));
		assertThat(run.completedAt()).isEqualTo(LocalDateTime.of(2026, 6, 14, 11, 0));
		assertThat(run.collectedCount()).isEqualTo(14);
		assertThat(run.retryable()).isFalse();
		assertThat(run.operatorAction()).isEqualTo("수집이 완료되었습니다. 최근 데이터 품질 화면에서 반영 결과를 확인하세요.");
		assertThat(repository.loadRun("collection-test")).contains(run);
	}

	@Test
	@DisplayName("최근 실행 기록은 최신 실행부터 제한 개수만 조회한다")
	void listRecentRunsReturnsLatestRunsFirst() {
		recorder.recordTransitMasterRun("collection-a", "admin-a");
		recorder.recordTransitMasterRun("collection-b", "admin-b");

		var recentRuns = repository.loadRecentRuns(1);

		assertThat(recentRuns)
			.extracting("requestedBy")
			.containsExactly("admin-b");
	}

	@Test
	@DisplayName("도시철도 마스터 데이터 로딩 실패도 실행 기록에 실패 상태로 남긴다")
	void recordTransitMasterRunStoresFailedRunWhenLoadingFails() {
		LoadTransitMasterPort failingTransitMasterPort = mock(LoadTransitMasterPort.class);
		when(failingTransitMasterPort.loadOperators()).thenThrow(new IllegalStateException("loader down"));
		var failingRepository = new InMemoryDataCollectionRunRepository();
		var failingRecorder = new DataCollectionRunRecorder(failingTransitMasterPort, failingRepository, CLOCK);

		assertThatThrownBy(() -> failingRecorder.recordTransitMasterRun("collection-failed-run", "admin-user"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("loader down");

		var run = failingRepository.loadRun("collection-failed-run").orElseThrow();
		assertThat(run.status()).isEqualTo(DataCollectionStatus.FAILED);
		assertThat(run.requestedBy()).isEqualTo("admin-user");
		assertThat(run.completedAt()).isEqualTo(LocalDateTime.of(2026, 6, 14, 11, 0));
		assertThat(run.failureMessage()).isEqualTo("loader down");
		assertThat(run.retryable()).isTrue();
		assertThat(run.operatorAction()).isEqualTo("일시 오류일 수 있습니다. 실패 사유를 확인한 뒤 같은 수집 대상을 다시 실행하세요.");
	}

	@Test
	@DisplayName("실패 기록 저장이 실패해도 원래 수집 실패 예외를 보존한다")
	void recordTransitMasterRunPreservesOriginalFailureWhenFailedRunSaveFails() {
		LoadTransitMasterPort failingTransitMasterPort = mock(LoadTransitMasterPort.class);
		when(failingTransitMasterPort.loadOperators()).thenThrow(new IllegalStateException("loader down"));
		SaveDataCollectionRunPort failingSavePort = mock(SaveDataCollectionRunPort.class);
		when(failingSavePort.saveRun(any(DataCollectionRun.class))).thenThrow(new IllegalStateException("save down"));
		var failingRecorder = new DataCollectionRunRecorder(failingTransitMasterPort, failingSavePort, CLOCK);

		assertThatThrownBy(() -> failingRecorder.recordTransitMasterRun("collection-failed-run", "admin-user"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("loader down")
			.satisfies(exception -> assertThat(exception.getSuppressed())
				.extracting(Throwable::getMessage)
				.containsExactly("save down"));
	}

	@Test
	@DisplayName("배치 실행 명령은 수집 대상과 요청자를 요구한다")
	void runCommandRequiresSourceAndRequester() {
		assertThatThrownBy(() -> new RunDataCollectionCommand(null, "admin-user"))
			.isInstanceOf(InvalidDataCollectionException.class)
			.hasMessage("수집 대상을 선택해야 합니다.");

		assertThatThrownBy(() -> new RunDataCollectionCommand(DataCollectionSource.TRANSIT_MASTER, ""))
			.isInstanceOf(InvalidDataCollectionException.class)
			.hasMessage("요청자 식별자가 필요합니다.");
	}

	@Test
	@DisplayName("실행 중인 실행 기록은 완료 시간을 포함할 수 없다")
	void runningRunRejectsCompletedAt() {
		assertThatThrownBy(() -> new DataCollectionRun(
			"collection-running",
			DataCollectionSource.TRANSIT_MASTER,
			DataCollectionStatus.RUNNING,
			"admin-user",
			LocalDateTime.of(2026, 6, 14, 11, 0),
			LocalDateTime.of(2026, 6, 14, 11, 1),
			0,
			null,
			false,
			"실행 중"
		))
			.isInstanceOf(InvalidDataCollectionException.class)
			.hasMessage("실행 중인 실행은 완료 시간을 포함할 수 없습니다.");
	}

	@Test
	@DisplayName("완료된 실행 기록은 완료 시간을 요구한다")
	void completedRunRequiresCompletedAt() {
		assertThatThrownBy(() -> new DataCollectionRun(
			"collection-completed",
			DataCollectionSource.TRANSIT_MASTER,
			DataCollectionStatus.COMPLETED,
			"admin-user",
			LocalDateTime.of(2026, 6, 14, 11, 0),
			null,
			13,
			null,
			false,
			"완료"
		))
			.isInstanceOf(InvalidDataCollectionException.class)
			.hasMessage("완료된 실행은 완료 시간이 필요합니다.");
	}

	@Test
	@DisplayName("완료된 실행 기록은 실패 사유를 포함할 수 없다")
	void completedRunRejectsFailureMessage() {
		assertThatThrownBy(() -> new DataCollectionRun(
			"collection-completed",
			DataCollectionSource.TRANSIT_MASTER,
			DataCollectionStatus.COMPLETED,
			"admin-user",
			LocalDateTime.of(2026, 6, 14, 11, 0),
			LocalDateTime.of(2026, 6, 14, 11, 1),
			13,
			"failed",
			false,
			"완료"
		))
			.isInstanceOf(InvalidDataCollectionException.class)
			.hasMessage("완료된 실행은 실패 사유를 포함할 수 없습니다.");
	}

	@Test
	@DisplayName("실패한 실행 기록은 실패 사유를 요구한다")
	void failedRunRequiresFailureMessage() {
		assertThatThrownBy(() -> new DataCollectionRun(
			"collection-failed",
			DataCollectionSource.TRANSIT_MASTER,
			DataCollectionStatus.FAILED,
			"admin-user",
			LocalDateTime.of(2026, 6, 14, 11, 0),
			null,
			0,
			" ",
			true,
			"재시도"
		))
			.isInstanceOf(InvalidDataCollectionException.class)
			.hasMessage("실패한 실행은 실패 사유가 필요합니다.");
	}
}
