package com.easysubway.admin.batch.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.admin.batch.application.service.AdminBatchOperationService.RunExecution;
import com.easysubway.collection.domain.DataCollectionStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관리자 배치 이력 차트 값")
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
}
