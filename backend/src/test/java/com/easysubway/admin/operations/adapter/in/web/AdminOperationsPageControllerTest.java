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
import com.easysubway.admin.operations.adapter.out.persistence.InMemoryAdminIncidentRepository;
import com.easysubway.admin.operations.domain.AdminIncident;
import com.easysubway.admin.operations.domain.AdminIncidentStatus;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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

	@Autowired
	private ConflictSimulatingIncidentRepository incidentRepository;

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
			.contains("RECEIVED")
			.doesNotContain("/admin/codes/INCIDENT_STATUS/RECEIVED/disable");
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
				.param("code", "RECEIVED")
				.param("displayName", "접수")
				.param("description", "접수됨, 조치 대기")
				.param("sortOrder", "10"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/codes/page?groupCode=INCIDENT_STATUS"));

		String html = mockMvc.perform(get("/admin/incidents/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html).contains("접수");
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
			.contains("접수")
			.contains("name=\"status\" value=\"RECEIVED\"")
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
	@DisplayName("incident 생성과 전이·종결은 타임라인 audit을 남긴다")
	void incidentOpenAndTransitionWritesAudit() throws Exception {
		openIncident("database DOWN");

		String incidentId = auditEventRepository.findRecent(AdminAuditEventType.INCIDENT_CHANGE, 1)
			.getFirst()
			.targetId();

		assertThat(incidentId).startsWith("INC-");

		transition(incidentId, "IN_PROGRESS", null, null);
		transition(incidentId, "MONITORING", null, null);
		transition(incidentId, "RESOLVED", "provider secret upload url rotated", "종결 처리");

		assertThat(auditEventRepository.findRecent(AdminAuditEventType.INCIDENT_CHANGE, 4))
			.extracting(event -> event.action())
			.containsExactly("RESOLVE_INCIDENT", "TRANSITION_INCIDENT", "TRANSITION_INCIDENT", "OPEN_INCIDENT");

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
	@DisplayName("동시 전이 충돌 시 전이 요청은 409와 복구 안내를 반환하고 성공 전이·audit을 남기지 않는다")
	void incidentTransitionConflictReturns409WithoutSuccessAudit() throws Exception {
		openIncident("database DOWN");
		String incidentId = auditEventRepository.findRecent(AdminAuditEventType.INCIDENT_CHANGE, 1)
			.getFirst()
			.targetId();

		// 다른 세션이 먼저 상태를 바꾼 것처럼 다음 compare-and-set를 충돌로 만든다.
		incidentRepository.simulateNextConflict();

		String conflictHtml = mockMvc.perform(post("/admin/incidents/{incidentId}/transition", incidentId)
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/incidents/page"))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("targetStatus", "IN_PROGRESS")
				.param("note", "재개 시도"))
			.andExpect(status().isConflict())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(conflictHtml)
			.contains("요청이 최신 상태와 충돌했습니다")
			.contains("화면을 새로고침한 뒤 다시 시도해 주세요");

		// 충돌 요청은 성공 전이 audit(TRANSITION_INCIDENT)을 남기지 않는다. OPEN_INCIDENT만 존재한다.
		assertThat(auditEventRepository.findRecent(AdminAuditEventType.INCIDENT_CHANGE, 10))
			.extracting(event -> event.action())
			.containsExactly("OPEN_INCIDENT");

		// 상태는 여전히 접수(RECEIVED)이며 다음 전이 버튼(IN_PROGRESS)이 그대로 노출된다.
		String html = getAdminHtml("/admin/incidents/page", new MockHttpSession());
		assertThat(html).contains("name=\"targetStatus\" value=\"IN_PROGRESS\"");
	}

	@Test
	@DisplayName("장애 화면은 60초 자동 갱신 폴러를 렌더링하고 live fragment는 목록 영역만 반환한다")
	void incidentsPageRendersAutoRefreshPollerAndLiveFragment() throws Exception {
		openIncident("database DOWN");

		String page = getAdminHtml("/admin/incidents/page", new MockHttpSession());
		assertThat(page)
			.contains("x-data=\"autoRefresh\"")
			.contains("data-refresh-url=\"/admin/incidents/page/live\"")
			.contains("data-refresh-interval=\"60000\"")
			.contains("data-refresh-active=\"true\"");

		String fragment = getAdminHtml("/admin/incidents/page/live", new MockHttpSession());
		assertThat(fragment)
			.contains("id=\"incident-live\"")
			.contains("최근 Incident")
			.doesNotContain("admin-shell")
			.doesNotContain("Incident 생성");
	}

	@Test
	@DisplayName("역·노선 연결 장애는 역 허브 딥링크를 표시한다")
	void incidentWithStationLinkShowsHubDeepLink() throws Exception {
		mockMvc.perform(post("/admin/incidents")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/incidents/page"))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("severity", "MAJOR")
				.param("status", "RECEIVED")
				.param("source", "HEALTH")
				.param("summary", "platform gate down")
				.param("owner", "ops")
				.param("stationId", "STN-1")
				.param("lineId", "L1"))
			.andExpect(status().is3xxRedirection());

		String html = getAdminHtml("/admin/incidents/page", new MockHttpSession());

		assertThat(html)
			.contains("/admin/stations/STN-1/page")
			.contains("노선 L1");
	}

	@Test
	@DisplayName("장애 화면은 전이 타임라인과 다음 전이 버튼을 표시한다")
	void incidentsPageShowsTimelineAndTransitionOptions() throws Exception {
		openIncident("database DOWN");

		String html = mockMvc.perform(get("/admin/incidents/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("타임라인")
			.contains("접수")
			.contains("name=\"targetStatus\" value=\"IN_PROGRESS\"")
			.contains("/transition");
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
				.param("status", "RECEIVED")
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
				.param("status", "RECEIVED")
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
				.param("status", "RECEIVED")
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
				.param("status", "RECEIVED")
				.param("source", "HEALTH")
				.param("summary", summary)
				.param("owner", "ops"))
			.andExpect(status().is3xxRedirection());
	}

	private void transition(String incidentId, String targetStatus, String resolution, String note) throws Exception {
		var builder = post("/admin/incidents/{incidentId}/transition", incidentId)
			.with(httpBasic("admin-user", "admin-test-password"))
			.with(csrf())
			.with(commandToken("/admin/incidents/page"))
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.param("targetStatus", targetStatus);
		if (resolution != null) {
			builder = builder.param("resolution", resolution);
		}
		if (note != null) {
			builder = builder.param("note", note);
		}
		mockMvc.perform(builder)
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/incidents/page"));
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

	@TestConfiguration
	static class ConflictRepositoryConfig {

		@Bean
		@Primary
		ConflictSimulatingIncidentRepository conflictSimulatingIncidentRepository() {
			return new ConflictSimulatingIncidentRepository();
		}
	}

	/**
	 * 다음 compare-and-set 호출을 한 번 충돌(영향 행 0)로 만들어, 다른 세션이 먼저 상태를 바꾼 상황을
	 * 결정적으로 재현하는 인메모리 저장소.
	 */
	static class ConflictSimulatingIncidentRepository extends InMemoryAdminIncidentRepository {

		private volatile boolean conflictNext;

		void simulateNextConflict() {
			this.conflictNext = true;
		}

		@Override
		public boolean compareAndSetStatus(AdminIncident next, AdminIncidentStatus expectedStatus) {
			if (conflictNext) {
				conflictNext = false;
				return false;
			}
			return super.compareAndSetStatus(next, expectedStatus);
		}
	}
}
