package com.easysubway.admin.batch.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.easysubway.admin.audit.application.service.AdminAuditWriter;
import com.easysubway.admin.batch.application.service.AdminBatchOperationService;
import com.easysubway.admin.batch.application.service.AdminBatchOperationService.JobExecutionHistory;
import com.easysubway.admin.batch.application.service.AdminBatchOperationService.RunExecution;
import com.easysubway.admin.batch.domain.AdminBatchJob;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionRunStep;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStatus;
import com.easysubway.collection.domain.DataCollectionStepStatus;
import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;

@DisplayName("관리자 배치 이력 차트 값 및 시간대 변환")
class AdminBatchPageControllerValueTest {

	@Test
	@DisplayName("null 소요 시간은 boxed 0이고 기존 Long 값은 같은 객체를 보존한다")
	void durationValueAvoidsUnboxRebox() {
		LocalDateTime startedAt = LocalDateTime.of(2026, 8, 10, 0, 0);
		assertThat(AdminBatchPageController.durationMillisOrZero(
			new RunExecution(startedAt, DataCollectionStatus.RUNNING, null)))
			.isEqualTo(Long.valueOf(0L));

		Long measured = Long.valueOf(42_000L);
		assertThat(AdminBatchPageController.durationMillisOrZero(
			new RunExecution(startedAt, DataCollectionStatus.COMPLETED, measured)))
			.isSameAs(measured);
	}

	@Test
	@DisplayName("배치 실행 이력 차트·셀·행은 UTC 시각을 한국 표준시(KST)로 변환하고 직렬화 실패 시 예외를 던진다")
	void convertsBatchTimestampsToKstAndFailsClosedOnJsonError() {
		LocalDateTime utcStartedAt = LocalDateTime.of(2026, 6, 27, 15, 30);
		LocalDateTime utcCompletedAt = utcStartedAt.plusMinutes(2);
		DataCollectionRun run = new DataCollectionRun(
			"run-kst-1",
			DataCollectionSource.TRANSIT_MASTER,
			DataCollectionStatus.COMPLETED,
			"batch-test",
			utcStartedAt,
			utcCompletedAt,
			1,
			null,
			false,
			"완료",
			List.of(new DataCollectionRunStep("FETCH", DataCollectionStepStatus.COMPLETED, null, null, null, 1, null))
		);

		AdminBatchPageController.HistoryCell cell = AdminBatchPageController.HistoryCell.from(
			new RunExecution(utcStartedAt, DataCollectionStatus.COMPLETED, 120_000L)
		);
		assertThat(cell.startedAt()).isEqualTo("2026-06-28T00:30");

		AdminBatchPageController.BatchRunRow row = AdminBatchPageController.BatchRunRow.from(run).orElseThrow();
		assertThat(row.startedAt()).isEqualTo(LocalDateTime.of(2026, 6, 28, 0, 30));
		assertThat(row.completedAt()).isEqualTo(LocalDateTime.of(2026, 6, 28, 0, 32));

		AdminBatchOperationService service = mock(AdminBatchOperationService.class);
		when(service.listExecutions(anyInt(), anyInt())).thenReturn(List.of(run));
		when(service.jobHistories(anyInt())).thenReturn(List.of(
			new JobExecutionHistory(
				"transit-master-collection",
				"도시철도 마스터 수집",
				List.of(new RunExecution(utcStartedAt, DataCollectionStatus.COMPLETED, 120_000L))
			)
		));

		AdminBatchPageController controller = new AdminBatchPageController(
			service,
			AdminAuditWriter.noop(),
			new ObjectMapper()
		);
		ConcurrentModel model = new ConcurrentModel();
		controller.batchLive(0, 20, model);
		assertThat(model.getAttribute("histories"))
			.asInstanceOf(InstanceOfAssertFactories.list(AdminBatchPageController.BatchHistoryChart.class))
			.singleElement()
			.satisfies(history ->
				assertThat(history.json()).contains("06-28 00:30").doesNotContain("06-27 15:30")
			);

		ObjectMapper failingMapper = new ObjectMapper() {
			@Override
			public String writeValueAsString(Object value) throws JsonProcessingException {
				throw new JsonGenerationException("boom", (JsonGenerator) null);
			}
		};
		AdminBatchPageController failingController = new AdminBatchPageController(
			service,
			AdminAuditWriter.noop(),
			failingMapper
		);
		assertThatThrownBy(() -> failingController.batchLive(0, 20, new ConcurrentModel()))
			.isInstanceOf(IllegalStateException.class);
	}
}
