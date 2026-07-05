package com.easysubway.admin.savedview.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.audit.adapter.out.persistence.InMemoryAdminAuditEventRepository;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import com.easysubway.admin.savedview.application.port.in.AdminSavedViewUseCase;
import com.easysubway.admin.savedview.domain.AdminSavedView;
import com.jayway.jsonpath.JsonPath;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-test",
	"easysubway.admin.password=admin-test-password",
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 저장된 뷰 컨트롤러")
class AdminSavedViewControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AdminSavedViewUseCase savedViewUseCase;

	@Autowired
	private InMemoryAdminAuditEventRepository auditEventRepository;

	@Test
	@DisplayName("관리자는 현재 검색을 저장된 뷰로 저장하고 감사가 남는다")
	void saveViewPersistsAndAudits() throws Exception {
		MockHttpSession session = new MockHttpSession();
		String token = issueCommandToken(session);

		mockMvc.perform(post("/admin/saved-views")
				.session(session)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("commandToken", token)
				.param("programId", "a-reports")
				.param("name", "미확인 급증")
				.param("queryParams", "status=SUBMITTED&sort=created_at,asc")
				.param("returnTo", "/admin/reports/page"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/reports/page"));

		assertThat(savedViewUseCase.listViews("admin-test", "a-reports"))
			.singleElement()
			.satisfies(view -> {
				assertThat(view.name()).isEqualTo("미확인 급증");
				assertThat(view.queryParams()).isEqualTo("status=SUBMITTED&sort=created_at,asc");
			});
		// AdminOperatorAuditFilter가 POST 요청 자체도 ADMIN_ACTION으로 감사하므로 최근 건 중 SAVE_VIEW를 찾는다.
		assertThat(auditEventRepository.findRecent(AdminAuditEventType.ADMIN_ACTION, 5))
			.anySatisfy(event -> {
				assertThat(event.targetType()).isEqualTo("ADMIN_SAVED_VIEW");
				assertThat(event.action()).isEqualTo("SAVE_VIEW");
				assertThat(event.outcome()).isEqualTo(AdminAuditOutcome.SUCCESS);
			});
	}

	@Test
	@DisplayName("관리자는 저장된 뷰를 기본으로 지정하고 삭제한다")
	void setDefaultAndDeleteView() throws Exception {
		AdminSavedView view = savedViewUseCase.saveView(
			new com.easysubway.admin.savedview.application.port.in.SaveAdminSavedViewCommand(
				"admin-test", "a-reports", "기본 후보", "status=SUBMITTED", false));

		MockHttpSession session = new MockHttpSession();

		mockMvc.perform(post("/admin/saved-views/{id}/default", view.viewId())
				.session(session)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("commandToken", issueCommandToken(session))
				.param("returnTo", "/admin/reports/page"))
			.andExpect(status().is3xxRedirection());

		assertThat(savedViewUseCase.listViews("admin-test", "a-reports"))
			.filteredOn(AdminSavedView::isDefault)
			.extracting(AdminSavedView::viewId)
			.containsExactly(view.viewId());

		mockMvc.perform(post("/admin/saved-views/{id}/delete", view.viewId())
				.session(session)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("commandToken", issueCommandToken(session))
				.param("returnTo", "/admin/reports/page"))
			.andExpect(status().is3xxRedirection());

		assertThat(savedViewUseCase.listViews("admin-test", "a-reports")).isEmpty();
	}

	@Test
	@DisplayName("저장된 뷰 변경은 관리자 인증을 요구한다")
	void savedViewMutationRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(post("/admin/saved-views")
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("programId", "a-reports")
				.param("name", "무권한"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/admin/saved-views")
				.with(httpBasic("basic-user", "user-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("programId", "a-reports")
				.param("name", "무권한"))
			.andExpect(status().isForbidden());
	}

	// command token은 세션 스코프라 관리자 페이지 한 번 렌더로 발급된다(상세 화면이 렌더한다).
	private String issueCommandToken(MockHttpSession session) throws Exception {
		String reportId = createReport("토큰 발급용 신고");
		String html = mockMvc.perform(get("/admin/reports/{id}/page", reportId)
				.session(session)
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		Matcher matcher = Pattern.compile("name=\"commandToken\" value=\"([^\"]+)\"").matcher(html);
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
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
}
