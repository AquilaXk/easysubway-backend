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
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
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
	@DisplayName("배치 실행 목록은 page size와 현재 페이지를 링크에 표시한다")
	void batchPageShowsPaginationLinks() throws Exception {
		saveDataCollectionRunPort.saveRun(failedRun("failed-run-1"));
		saveDataCollectionRunPort.saveRun(failedRun("failed-run-2"));

		String html = mockMvc.perform(get("/admin/batches/page")
				.param("size", "1")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("배치 실행 목록 페이지")
			.contains("aria-current=\"page\"")
			.contains("page=1&amp;size=1")
			.contains("다음");
	}

	@Test
	@DisplayName("BATCH_RETRY 권한이 있는 관리자는 실패 실행을 재처리하고 audit을 남긴다")
	void adminRetriesFailedRunAndWritesAudit() throws Exception {
		saveDataCollectionRunPort.saveRun(failedRun("failed-run"));

		mockMvc.perform(post("/admin/batches/transit-master-collection/runs/failed-run/retry")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("retryRequested", "true"))
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
	@DisplayName("관리자 재처리 거부도 실패 audit을 남긴다")
	void rejectedRetryWritesFailureAudit() throws Exception {
		saveDataCollectionRunPort.saveRun(completedRun("completed-run"));

		mockMvc.perform(post("/admin/batches/transit-master-collection/runs/completed-run/retry")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("retryRequested", "true"))
			.andExpect(status().isBadRequest());

		assertThat(auditEventRepository.findRecent(AdminAuditEventType.BATCH_OPERATION, 1))
			.singleElement()
			.satisfies(event -> {
				assertThat(event.actor()).isEqualTo("admin-user");
				assertThat(event.targetType()).isEqualTo("BATCH_JOB");
				assertThat(event.targetId()).isEqualTo("transit-master-collection");
				assertThat(event.action()).isEqualTo("RETRY_BATCH_RUN");
				assertThat(event.outcome()).isEqualTo(AdminAuditOutcome.FAILURE);
				assertThat(event.reason()).contains("completed-run", "재처리할 수 없는 배치 실행");
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

	@Test
	@DisplayName("관리자 HTML 예외 페이지는 지원하지 않는 method를 405로 표시한다")
	void retryGetRendersMethodNotAllowedAdminHtml() throws Exception {
		String html = mockMvc.perform(get("/admin/batches/transit-master-collection/runs/failed-run/retry")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isMethodNotAllowed())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("통합 관리자")
			.contains("허용되지 않는 요청입니다")
			.contains("상태 코드 405");
	}

	@Test
	@DisplayName("관리자 HTML 예외 페이지는 POST forward도 렌더링한다")
	void adminErrorPageRendersForwardedPost() throws Exception {
		String html = mockMvc.perform(post("/admin/error/page")
				.with(csrf())
				.requestAttr("adminErrorStatus", 403)
				.requestAttr("adminErrorTitle", "권한이 없습니다")
				.requestAttr("adminErrorMessage", "이 관리자 기능을 사용할 권한이 없습니다.")
				.requestAttr("adminErrorDetail", "필요한 역할과 권한을 확인해 주세요."))
			.andExpect(status().isForbidden())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("통합 관리자")
			.contains("권한이 없습니다")
			.contains("상태 코드 403")
			.contains("이 관리자 기능을 사용할 권한이 없습니다.");
	}

	@Test
	@DisplayName("관리자 HTML 예외 페이지는 속성이 없어도 기본 오류 화면을 표시한다")
	void adminErrorPageRendersSafeDefaults() throws Exception {
		String html = mockMvc.perform(get("/admin/error/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isInternalServerError())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("통합 관리자")
			.contains("요청을 처리하지 못했습니다")
			.contains("상태 코드 500")
			.contains("관리자 요청 처리 중 오류가 발생했습니다.");
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
}
