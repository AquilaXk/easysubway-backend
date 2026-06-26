package com.easysubway.admin.batch.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.collection.adapter.out.persistence.InMemoryDataCollectionRunRepository;
import com.easysubway.collection.application.port.in.DataCollectionUseCase;
import com.easysubway.collection.application.port.in.RunDataCollectionCommand;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionRunStep;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStatus;
import com.easysubway.collection.domain.DataCollectionStepStatus;
import com.easysubway.collection.domain.InvalidDataCollectionException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관리자 배치 운영 서비스")
class AdminBatchOperationServiceTest {

	private final InMemoryDataCollectionRunRepository repository = new InMemoryDataCollectionRunRepository();
	private final AtomicReference<RunDataCollectionCommand> retriedCommand = new AtomicReference<>();
	private final DataCollectionUseCase useCase = new DataCollectionUseCase() {
		@Override
		public DataCollectionRun runCollection(RunDataCollectionCommand command) {
			retriedCommand.set(command);
			DataCollectionRun retried = completedRun("retry-run");
			repository.saveRun(retried);
			return retried;
		}

		@Override
		public Optional<DataCollectionRun> getLatestCompletedRun(DataCollectionSource source) {
			return Optional.empty();
		}

		@Override
		public List<DataCollectionRun> listRecentRuns(int limit) {
			return repository.loadRecentRuns(limit);
		}
	};
	private final AdminBatchOperationService service = new AdminBatchOperationService(repository, useCase);

	@Test
	@DisplayName("registry에 있는 실패·retryable 실행만 재처리한다")
	void retryAllowedFailedRun() {
		repository.saveRun(failedRun("failed-run", true));

		DataCollectionRun retried = service.retry("transit-master-collection", "failed-run", "admin-user");

		assertThat(retried.runId()).isEqualTo("retry-run");
		assertThat(retriedCommand.get().source()).isEqualTo(DataCollectionSource.TRANSIT_MASTER);
		assertThat(retriedCommand.get().requestedBy()).isEqualTo("admin-user");
	}

	@Test
	@DisplayName("registry 밖 job id와 성공 실행 재처리는 거부한다")
	void retryRejectsUnknownJobAndCompletedRun() {
		repository.saveRun(completedRun("completed-run"));

		assertThatThrownBy(() -> service.retry("unknown-job", "completed-run", "admin-user"))
			.isInstanceOf(InvalidDataCollectionException.class)
			.hasMessageContaining("허용되지 않은 배치 작업");
		assertThatThrownBy(() -> service.retry("transit-master-collection", "completed-run", "admin-user"))
			.isInstanceOf(InvalidDataCollectionException.class)
			.hasMessageContaining("재처리할 수 없는 배치 실행");
	}

	private DataCollectionRun completedRun(String runId) {
		LocalDateTime now = LocalDateTime.of(2026, 6, 27, 0, 0);
		return new DataCollectionRun(
			runId,
			DataCollectionSource.TRANSIT_MASTER,
			DataCollectionStatus.COMPLETED,
			"batch-test",
			now,
			now.plusMinutes(1),
			1,
			null,
			false,
			"수집 완료",
			List.of(new DataCollectionRunStep("FETCH", DataCollectionStepStatus.COMPLETED, null, null, null, 1, null))
		);
	}

	private DataCollectionRun failedRun(String runId, boolean retryable) {
		LocalDateTime now = LocalDateTime.of(2026, 6, 27, 0, 0);
		return new DataCollectionRun(
			runId,
			DataCollectionSource.TRANSIT_MASTER,
			DataCollectionStatus.FAILED,
			"batch-test",
			now,
			now.plusMinutes(1),
			0,
			"FETCH 실패",
			retryable,
			"원인 확인 후 재처리하세요.",
			List.of(new DataCollectionRunStep("FETCH", DataCollectionStepStatus.FAILED, null, null, null, 0, "source timeout"))
		);
	}
}
