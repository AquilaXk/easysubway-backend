package com.easysubway.admin.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.audit.adapter.out.persistence.InMemoryAdminAuditEventRepository;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import com.easysubway.admin.identity.adapter.out.persistence.InMemoryAdminIdentityRepository;
import com.easysubway.collection.application.port.out.SaveDataCollectionRunPort;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionRunStep;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStatus;
import com.easysubway.collection.domain.DataCollectionStepStatus;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password",
	"easysubway.admin.lockout.max-failures=2",
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("관리자 E2E 회귀 게이트")
class AdminE2EFlowTest {

	private static final Pattern COMMAND_TOKEN = Pattern.compile("name=\"commandToken\" value=\"([^\"]+)\"");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private InMemoryAdminIdentityRepository identityRepository;

	@Autowired
	private InMemoryAdminAuditEventRepository auditEventRepository;

	@Autowired
	private SaveDataCollectionRunPort saveDataCollectionRunPort;

	@Test
	@DisplayName("관리자 로그인 성공, 잠금, 로그아웃 흐름을 검증한다")
	void adminLoginLockoutAndLogoutFlow() throws Exception {
		MvcResult loginResult = mockMvc.perform(formLogin("/admin/login")
				.user("admin-user")
				.password("admin-test-password"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/admin/dashboard/page"))
			.andReturn();
		MockHttpSession session = sessionFrom(loginResult);

		mockMvc.perform(get("/admin/dashboard/page")
				.session(session))
			.andExpect(status().isOk());

		mockMvc.perform(post("/console/admin/logout")
				.contextPath("/console")
				.session(session)
				.with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/console/admin/login?logout"));

		mockMvc.perform(get("/admin/dashboard/page")
				.session(session)
				.header("Accept", "text/html"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrlPattern("**/admin/login"));

		mockMvc.perform(formLogin("/admin/login")
				.user("admin-user")
				.password("wrong-password"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/admin/login?error"));
		mockMvc.perform(formLogin("/admin/login")
				.user("admin-user")
				.password("wrong-password"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/admin/login?error"));
		mockMvc.perform(formLogin("/admin/login")
				.user("admin-user")
				.password("admin-test-password"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/admin/login?error"));

		assertThat(identityRepository.findByLoginId("admin-user").orElseThrow().failedLoginCount()).isEqualTo(2);
		assertThat(identityRepository.audits())
			.extracting(audit -> audit.loginId() + ":" + audit.outcome())
			.contains("admin-user:SUCCESS", "admin-user:FAILED", "admin-user:LOCKED");
	}

	@Test
	@DisplayName("관리자 주요 업무는 검수, 시설 변경, 배치 재처리, 감사 조회를 한 흐름으로 수행한다")
	void adminCoreOperationFlow() throws Exception {
		saveDataCollectionRunPort.saveRun(failedRun("admin-e2e-failed-run"));
		RequestPostProcessor admin = fullAdmin();

		String reportId = createReport("E2E 흐름에서 승인할 시설 신고");
		MockHttpSession reportSession = new MockHttpSession();
		String reportToken = commandTokenFrom(getAdminHtml("/admin/reports/%s/page".formatted(reportId), reportSession, admin));

		mockMvc.perform(post("/admin/reports/{reportId}/page/review", reportId)
				.session(reportSession)
				.with(admin)
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("commandToken", reportToken)
				.param("decision", "ACCEPT"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/reports/%s/page".formatted(reportId)));
		assertThat(getAdminHtml("/admin/reports/%s/page".formatted(reportId), reportSession, admin))
			.contains("반영됨", "admin-e2e");

		MockHttpSession facilitySession = new MockHttpSession();
		String facilityToken = commandTokenFrom(getAdminHtml("/admin/facilities/page", facilitySession, admin));
		mockMvc.perform(post("/admin/facilities/facility-sangnoksu-elevator-1/page/status")
				.session(facilitySession)
				.with(admin)
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("commandToken", facilityToken)
				.param("status", "BROKEN"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/facilities/page"));
		assertThat(getAdminHtml("/admin/facilities/page", facilitySession, admin))
			.contains("1번 출구 엘리베이터", "고장");

		MockHttpSession batchSession = new MockHttpSession();
		String batchToken = commandTokenFrom(getAdminHtml("/admin/batches/page", batchSession, admin));
		mockMvc.perform(post("/admin/batches/transit-master-collection/runs/admin-e2e-failed-run/retry")
				.session(batchSession)
				.with(admin)
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("commandToken", batchToken)
				.param("retryRequested", "true"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/batches/page"));

		String auditHtml = mockMvc.perform(get("/admin/audits/page")
				.with(admin))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String privacyHtml = mockMvc.perform(get("/admin/audits/privacy/page")
				.with(admin))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(auditHtml).contains("관리자 감사", "RETRY_BATCH_RUN");
		assertThat(privacyHtml).contains("개인정보 조회 로그", "VIEW_REPORT_DETAIL");
		assertThat(auditEventRepository.findRecent(AdminAuditEventType.BATCH_OPERATION, 1))
			.singleElement()
			.satisfies(event -> {
				assertThat(event.actor()).isEqualTo("admin-e2e");
				assertThat(event.outcome()).isEqualTo(AdminAuditOutcome.SUCCESS);
				assertThat(event.reason()).contains("admin-e2e-failed-run");
			});
	}

	@Test
	@DisplayName("관리자 mutation은 유효한 세션과 command token이 있어도 CSRF 없이는 거부된다")
	void adminMutationRequiresCsrfToken() throws Exception {
		RequestPostProcessor admin = fullAdmin();
		String reportId = createReport("CSRF 없이 검수하면 안 되는 신고");
		MockHttpSession session = new MockHttpSession();
		String commandToken = commandTokenFrom(getAdminHtml("/admin/reports/%s/page".formatted(reportId), session, admin));

		mockMvc.perform(post("/admin/reports/{reportId}/page/review", reportId)
				.session(session)
				.with(admin)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("commandToken", commandToken)
				.param("decision", "ACCEPT"))
			.andExpect(status().isForbidden());

		assertThat(getAdminHtml("/admin/reports/%s/page".formatted(reportId), session, admin))
			.doesNotContain("반영됨");
	}

	@Test
	@DisplayName("관리자 오류 shell은 403, 409, validation 실패를 같은 화면 기준으로 표시한다")
	void adminErrorShellCoversForbiddenConflictAndValidation() throws Exception {
		RequestPostProcessor admin = fullAdmin();
		RequestPostProcessor viewer = adminUser("viewer", "admin.view");

		mockMvc.perform(get("/admin/audits/page")
				.with(viewer))
			.andExpect(status().isForbidden())
			.andExpect(forwardedUrl("/admin/error/page"));
		String forbiddenHtml = mockMvc.perform(post("/admin/error/page")
				.with(csrf())
				.requestAttr("adminErrorStatus", 403)
				.requestAttr("adminErrorTitle", "권한이 없습니다")
				.requestAttr("adminErrorMessage", "이 관리자 기능을 사용할 권한이 없습니다.")
				.requestAttr("adminErrorDetail", "필요한 역할과 권한을 확인해 주세요."))
			.andExpect(status().isForbidden())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertAdminErrorShell(forbiddenHtml, "권한이 없습니다", "상태 코드 403");

		String reportId = createReport("중복 제출 E2E 신고");
		MockHttpSession reportSession = new MockHttpSession();
		String token = commandTokenFrom(getAdminHtml("/admin/reports/%s/page".formatted(reportId), reportSession, admin));
		mockMvc.perform(post("/admin/reports/{reportId}/page/review", reportId)
				.session(reportSession)
				.with(admin)
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("commandToken", token)
				.param("decision", "ACCEPT"))
			.andExpect(status().is3xxRedirection());
		String conflictHtml = mockMvc.perform(post("/admin/reports/{reportId}/page/review", reportId)
				.session(reportSession)
				.with(admin)
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("commandToken", token)
				.param("decision", "REJECT"))
			.andExpect(status().isConflict())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertAdminErrorShell(conflictHtml, "요청이 최신 상태와 충돌했습니다", "상태 코드 409");

		MockHttpSession facilitySession = new MockHttpSession();
		String facilityToken = commandTokenFrom(getAdminHtml("/admin/facilities/page", facilitySession, admin));
		String validationHtml = mockMvc.perform(post("/admin/facilities/facility-sangnoksu-elevator-1/page/status")
				.session(facilitySession)
				.with(admin)
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("commandToken", facilityToken))
			.andExpect(status().isBadRequest())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(validationHtml)
			.contains("통합 관리자")
			.contains("role=\"alert\"")
			.contains("입력값을 확인해 주세요")
			.contains("시설 상태를 선택해야 합니다.");
	}

	private String createReport(String description) throws Exception {
		String response = mockMvc.perform(post("/api/v1/reports")
				.with(httpBasic("basic-user", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "%s"
					}
					""".formatted(description)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(response, "$.data.id");
	}

	private String getAdminHtml(String path, MockHttpSession session, RequestPostProcessor admin) throws Exception {
		return mockMvc.perform(get(path)
				.session(session)
				.with(admin))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private static String commandTokenFrom(String html) {
		Matcher matcher = COMMAND_TOKEN.matcher(html);
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}

	private static void assertAdminErrorShell(String html, String title, String statusLabel) {
		assertThat(html)
			.contains("통합 관리자")
			.contains("class=\"admin-shell\"")
			.contains("role=\"alert\"")
			.contains("id=\"admin-error-title\"")
			.contains(title)
			.contains(statusLabel);
	}

	private static RequestPostProcessor fullAdmin() {
		return adminUser(
			"admin-e2e",
			"admin.view",
			"admin.report.review",
			"admin.report.photo.read",
			"admin.master.edit",
			"admin.field.operate",
			"admin.data.operate",
			"admin.security.audit",
			"admin.security.admin",
			"admin.audit.read",
			"admin.privacy-log.read",
			"admin.batch.retry",
			"admin.operations.manage"
		);
	}

	private static RequestPostProcessor adminUser(String username, String... authorities) {
		return user(username).authorities(
			List.of(authorities).stream()
				.map(SimpleGrantedAuthority::new)
				.toList()
		);
	}

	private static MockHttpSession sessionFrom(MvcResult result) {
		HttpSession session = result.getRequest().getSession(false);
		assertThat(session).isInstanceOf(MockHttpSession.class);
		return (MockHttpSession) session;
	}

	private static DataCollectionRun failedRun(String runId) {
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
