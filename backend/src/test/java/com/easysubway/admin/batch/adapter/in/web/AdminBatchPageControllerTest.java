package com.easysubway.admin.batch.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.audit.adapter.out.persistence.InMemoryAdminAuditEventRepository;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import com.easysubway.collection.application.port.out.SaveDataCollectionRunPort;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionRunStep;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStatus;
import com.easysubway.collection.domain.DataCollectionStepStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("관리자 배치 운영 화면")
class AdminBatchPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SaveDataCollectionRunPort saveDataCollectionRunPort;

	@Autowired
	private InMemoryAdminAuditEventRepository auditEventRepository;

	@Test
	@DisplayName("관리자는 허용 batch registry와 실패 step, 재처리 버튼을 확인한다")
	void adminViewsBatchRegistryAndFailedSteps() throws Exception {
		saveDataCollectionRunPort.saveRun(failedRun("failed-run"));

		String html = mockMvc.perform(get("/admin/batches/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("배치 운영")
			.contains("transit-master-collection")
			.contains("transitMasterCollectionJob")
			.contains("도시철도 마스터 수집")
			.contains("failed-run")
			.contains("FETCH")
			.contains("source timeout")
			.contains("재처리")
			.contains("/admin/batches/transit-master-collection/runs/failed-run/retry");
	}

	@Test
	@DisplayName("BATCH_RETRY 권한이 있는 관리자는 실패 실행을 재처리하고 audit을 남긴다")
	void adminRetriesFailedRunAndWritesAudit() throws Exception {
		saveDataCollectionRunPort.saveRun(failedRun("failed-run"));

		mockMvc.perform(post("/admin/batches/transit-master-collection/runs/failed-run/retry")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/batches/page"));

		assertThat(auditEventRepository.findRecent(AdminAuditEventType.BATCH_OPERATION, 1))
			.singleElement()
			.satisfies(event -> {
				assertThat(event.actor()).isEqualTo("admin-user");
				assertThat(event.targetType()).isEqualTo("BATCH_JOB");
				assertThat(event.targetId()).isEqualTo("transit-master-collection");
				assertThat(event.action()).isEqualTo("RETRY_BATCH_RUN");
				assertThat(event.reason()).contains("failed-run");
			});
	}

	@Test
	@DisplayName("BATCH_RETRY 권한이 없으면 재처리 entrypoint에 접근할 수 없다")
	void retryRequiresBatchRetryPermission() throws Exception {
		saveDataCollectionRunPort.saveRun(failedRun("failed-run"));

		mockMvc.perform(post("/admin/batches/transit-master-collection/runs/failed-run/retry")
				.with(user("operator").authorities(new SimpleGrantedAuthority("admin.data.operate")))
				.with(csrf()))
			.andExpect(status().isForbidden());
	}

	private DataCollectionRun failedRun(String runId) {
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
			true,
			"원인 확인 후 재처리하세요.",
			List.of(new DataCollectionRunStep("FETCH", DataCollectionStepStatus.FAILED, null, null, null, 0, "source timeout"))
		);
	}
}
