package com.easysubway.admin.operations.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.audit.adapter.out.persistence.InMemoryAdminAuditEventRepository;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import jakarta.servlet.http.HttpSession;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("관리자 공통코드와 장애관리 화면")
class AdminOperationsPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private InMemoryAdminAuditEventRepository auditEventRepository;

	@Test
	@DisplayName("공통코드 화면은 group filter와 enabled/disabled code를 표시한다")
	void codesPageShowsGroupFilterAndCodes() throws Exception {
		String html = mockMvc.perform(get("/admin/codes/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("공통코드")
			.contains("신고 반려 사유")
			.contains("DUPLICATE")
			.contains("신규 선택 가능");
	}

	@Test
	@DisplayName("공통코드 화면은 group filter와 page size를 링크에 표시한다")
	void codesPageShowsPaginationLinks() throws Exception {
		String html = mockMvc.perform(get("/admin/codes/page")
				.param("groupCode", "REPORT_REJECTION_REASON")
				.param("size", "1")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("공통코드 목록 페이지")
			.contains("aria-current=\"page\"")
			.contains("groupCode=REPORT_REJECTION_REASON&amp;page=1&amp;size=1")
			.contains("다음");
	}

	@Test
	@DisplayName("공통코드 화면은 필수 incident code 비활성화 버튼을 숨긴다")
	void codesPageHidesRequiredIncidentDisableAction() throws Exception {
		String html = mockMvc.perform(get("/admin/codes/page")
				.param("groupCode", "INCIDENT_STATUS")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("OPEN")
			.doesNotContain("/admin/codes/INCIDENT_STATUS/OPEN/disable");
	}

	@Test
	@DisplayName("공통코드 변경은 audit을 남긴다")
	void saveCodeWritesAudit() throws Exception {
		mockMvc.perform(post("/admin/codes")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/codes/page"))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("groupCode", "REPORT_REJECTION_REASON")
				.param("code", "PROVIDER_SECRET_MISSING")
				.param("displayName", "처리 범위 아님")
				.param("description", "앱 처리 범위 밖의 제보")
				.param("sortOrder", "30")
				.param("enabled", "true"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/codes/page?groupCode=REPORT_REJECTION_REASON"));

		assertThat(auditEventRepository.findRecent(AdminAuditEventType.COMMON_CODE_CHANGE, 1))
			.singleElement()
			.satisfies(event -> {
				assertThat(event.actor()).isEqualTo("admin-user");
				assertThat(event.targetId())
					.startsWith("code-")
					.doesNotContain("SECRET");
				assertThat(event.action()).isEqualTo("UPSERT_COMMON_CODE");
				assertThat(event.reason()).isEqualTo("enabled=true");
			});
	}

	@Test
	@DisplayName("공통코드 저장은 필수 incident code를 disabled로 만들지 않는다")
	void saveRequiredIncidentCodeKeepsEnabled() throws Exception {
		mockMvc.perform(post("/admin/codes")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/codes/page?groupCode=INCIDENT_STATUS"))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("groupCode", "INCIDENT_STATUS")
				.param("code", "OPEN")
				.param("displayName", "Open")
				.param("description", "처리 전")
				.param("sortOrder", "10"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/codes/page?groupCode=INCIDENT_STATUS"));

		String html = mockMvc.perform(get("/admin/incidents/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html).contains("Open");
		assertThat(auditEventRepository.findRecent(AdminAuditEventType.COMMON_CODE_CHANGE, 1))
			.singleElement()
			.satisfies(event -> assertThat(event.reason()).isEqualTo("enabled=true"));
	}

	@Test
	@DisplayName("장애관리 화면은 enabled code select와 incident 목록을 표시한다")
	void incidentsPageShowsSelectOptions() throws Exception {
		String html = mockMvc.perform(get("/admin/incidents/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("장애관리")
			.contains("Major")
			.contains("Open")
			.contains("name=\"status\" value=\"OPEN\"")
			.doesNotContain("Health incident 생성");
	}

	@Test
	@DisplayName("장애관리 목록은 page size와 현재 페이지를 링크에 표시한다")
	void incidentsPageShowsPaginationLinks() throws Exception {
		openIncident("database DOWN");
		openIncident("redis DOWN");

		String html = mockMvc.perform(get("/admin/incidents/page")
				.param("size", "1")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("Incident 목록 페이지")
			.contains("aria-current=\"page\"")
			.contains("page=1&amp;size=1")
			.contains("다음");
	}

	@Test
	@DisplayName("공통코드 audit target은 hashCode 충돌 code도 구분한다")
	void commonCodeAuditTargetAvoidsHashCodeCollision() throws Exception {
		saveCode("AAO");
		saveCode("AB0");

		List<String> targetIds = auditEventRepository.findRecent(AdminAuditEventType.COMMON_CODE_CHANGE, 2)
			.stream()
			.map(event -> event.targetId())
			.toList();

		assertThat(targetIds)
			.hasSize(2)
			.allSatisfy(targetId -> assertThat(targetId).startsWith("code-"))
			.doesNotHaveDuplicates();
	}

	@Test
	@DisplayName("incident 생성과 해결은 audit을 남긴다")
	void incidentOpenAndResolveWritesAudit() throws Exception {
		mockMvc.perform(post("/admin/incidents")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/incidents/page"))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("severity", "MAJOR")
				.param("status", "OPEN")
				.param("source", "HEALTH")
				.param("summary", "database DOWN")
				.param("owner", "ops"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/incidents/page"));

		String incidentId = auditEventRepository.findRecent(AdminAuditEventType.INCIDENT_CHANGE, 1)
			.get(0)
			.targetId();

		assertThat(incidentId).startsWith("INC-");

		mockMvc.perform(post("/admin/incidents/{incidentId}/resolve", incidentId)
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/incidents/page"))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("resolution", "provider secret upload url rotated"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/incidents/page"));

		assertThat(auditEventRepository.findRecent(AdminAuditEventType.INCIDENT_CHANGE, 2))
			.extracting(event -> event.action())
			.containsExactly("RESOLVE_INCIDENT", "OPEN_INCIDENT");

		assertThat(auditEventRepository.findRecent(AdminAuditEventType.INCIDENT_CHANGE, 1))
			.singleElement()
			.satisfies(event -> {
				assertThat(event.actor()).isEqualTo("admin-user");
				assertThat(event.targetId()).isEqualTo(incidentId);
				assertThat(event.action()).isEqualTo("RESOLVE_INCIDENT");
				assertThat(event.reason()).startsWith("resolutionLength=");
				assertThat(event.reason()).doesNotContain("secret");
			});
	}

	@Test
	@DisplayName("incident 생성 폼은 같은 command token 재전송을 409로 차단한다")
	void incidentOpenRejectsRepeatedCommandToken() throws Exception {
		MockHttpSession session = new MockHttpSession();
		String token = commandTokenFrom(getAdminHtml("/admin/incidents/page", session));

		mockMvc.perform(post("/admin/incidents")
				.session(session)
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("commandToken", token)
				.param("severity", "MAJOR")
				.param("status", "OPEN")
				.param("source", "HEALTH")
				.param("summary", "database DOWN")
				.param("owner", "ops"))
			.andExpect(status().is3xxRedirection());

		String conflictHtml = mockMvc.perform(post("/admin/incidents")
				.session(session)
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("commandToken", token)
				.param("severity", "MAJOR")
				.param("status", "OPEN")
				.param("source", "HEALTH")
				.param("summary", "database DOWN")
				.param("owner", "ops"))
			.andExpect(status().isConflict())
			.andReturn()
			.getResponse()
			.getContentAsString();

		String incidentsHtml = getAdminHtml("/admin/incidents/page", session);

		assertThat(conflictHtml)
			.contains("요청이 최신 상태와 충돌했습니다")
			.contains("이미 처리되었거나 만료된 관리자 요청입니다");
		assertThat(incidentsHtml).containsOnlyOnce("database DOWN");
		assertThat(auditEventRepository.findRecent(AdminAuditEventType.ADMIN_ACTION, 1))
			.singleElement()
			.satisfies(event -> {
				assertThat(event.outcome()).isEqualTo(AdminAuditOutcome.FAILURE);
				assertThat(event.action()).isEqualTo("POST /admin/incidents");
			});
	}

	@Test
	@DisplayName("관리자 form POST는 command token 누락을 409로 차단한다")
	void adminFormPostRejectsMissingCommandToken() throws Exception {
		String html = mockMvc.perform(post("/admin/incidents")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("severity", "MAJOR")
				.param("status", "OPEN")
				.param("source", "HEALTH")
				.param("summary", "database DOWN")
				.param("owner", "ops"))
			.andExpect(status().isConflict())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("요청이 최신 상태와 충돌했습니다")
			.contains("이미 처리되었거나 만료된 관리자 요청입니다");
	}

	private void saveCode(String code) throws Exception {
		mockMvc.perform(post("/admin/codes")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/codes/page"))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("groupCode", "REPORT_REJECTION_REASON")
				.param("code", code)
				.param("displayName", "코드 " + code)
				.param("description", "충돌 검증")
				.param("sortOrder", "30")
				.param("enabled", "true"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/codes/page?groupCode=REPORT_REJECTION_REASON"));
	}

	private void openIncident(String summary) throws Exception {
		mockMvc.perform(post("/admin/incidents")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/incidents/page"))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("severity", "MAJOR")
				.param("status", "OPEN")
				.param("source", "HEALTH")
				.param("summary", summary)
				.param("owner", "ops"))
			.andExpect(status().is3xxRedirection());
	}

	private String getAdminHtml(String path, MockHttpSession session) throws Exception {
		return mockMvc.perform(get(path)
				.session(session)
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private static String commandTokenFrom(String html) {
		Matcher matcher = Pattern.compile("name=\"commandToken\" value=\"([^\"]+)\"").matcher(html);
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}

	private RequestPostProcessor commandToken(String pagePath) {
		return request -> {
			MockHttpSession session = sessionFrom(request);
			try {
				request.setSession(session);
				request.addParameter("commandToken", commandTokenFrom(getAdminHtml(pagePath, session)));
				return request;
			} catch (Exception exception) {
				throw new AssertionError(exception);
			}
		};
	}

	private static MockHttpSession sessionFrom(MockHttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session instanceof MockHttpSession mockHttpSession) {
			return mockHttpSession;
		}
		return new MockHttpSession();
	}
}
