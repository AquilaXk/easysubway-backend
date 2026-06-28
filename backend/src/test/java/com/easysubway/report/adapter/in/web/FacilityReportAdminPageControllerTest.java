package com.easysubway.report.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.authorization.AdminPermission;
import com.easysubway.admin.audit.adapter.out.persistence.InMemoryAdminAuditEventRepository;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.HttpSession;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-test",
	"easysubway.admin.password=admin-test-password",
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 시설 신고 화면")
class FacilityReportAdminPageControllerTest {

	private static final String VALID_PNG_BASE64 =
		"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private InMemoryAdminAuditEventRepository auditEventRepository;

	@Test
	@DisplayName("관리자는 신고 목록 화면에서 접수 상태와 상세 링크를 확인한다")
	void adminReportListPageShowsReportsAndDetailLinks() throws Exception {
		String reportId = createReport("관리자 목록에서 볼 신고");

		String html = mockMvc.perform(get("/admin/reports/page")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("시설 신고 검수")
			.contains("접수됨")
			.contains("관리자 목록에서 볼 신고")
			.contains("/admin/reports/%s/page".formatted(reportId))
			.contains("status=SUBMITTED")
			.contains("신고 처리 시간");
	}

	@Test
	@DisplayName("관리자는 신고 목록 화면에서 다음 페이지로 이동한다")
	void adminReportListPageShowsNextPageLink() throws Exception {
		createReport("페이지 이동 신고 1");
		createReport("페이지 이동 신고 2");

		String html = mockMvc.perform(get("/admin/reports/page")
				.param("size", "1")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("신고 목록 페이지")
			.contains(">1</a>")
			.contains("다음")
			.contains("page=1")
			.contains("size=1");
	}

	@Test
	@DisplayName("관리자는 번호 페이지 링크에서 상태와 크기를 유지한다")
	void adminReportListPageShowsNumberedPageLinks() throws Exception {
		createReport("번호 페이지 신고 1");
		createReport("번호 페이지 신고 2");

		String html = mockMvc.perform(get("/admin/reports/page")
				.param("status", "SUBMITTED")
				.param("size", "1")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("신고 목록 페이지")
			.contains("aria-current=\"page\"")
			.contains("status=SUBMITTED")
			.contains("size=1")
			.contains(">1</a>")
			.contains(">2</a>");
	}

	@Test
	@DisplayName("관리자 신고 목록은 빈 결과에서 의미 없는 번호 링크를 숨긴다")
	void adminReportListPageHidesPaginationForEmptyResult() throws Exception {
		String html = mockMvc.perform(get("/admin/reports/page")
				.param("status", "RESOLVED")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("확인할 신고가 없습니다.")
			.doesNotContain("신고 목록 페이지");
	}

	@Test
	@DisplayName("관리자 신고 목록은 범위를 벗어난 page를 보정 URL로 돌려보낸다")
	void adminReportListPageRedirectsOutOfRangePage() throws Exception {
		mockMvc.perform(get("/admin/reports/page")
				.param("status", "RESOLVED")
				.param("page", "99")
				.param("size", "1")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/reports/page?status=RESOLVED&page=0&size=1"));
	}

	@Test
	@DisplayName("관리자는 신고 목록 화면에서 최근 24시간 신고 급증 경고를 확인한다")
	void adminReportListPageShowsRecentReportSurgeAlert() throws Exception {
		for (int index = 1; index <= 10; index++) {
			createReport("최근 급증 신고 %02d".formatted(index));
		}

		String html = mockMvc.perform(get("/admin/reports/page")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("신고 급증")
			.contains("점검 필요")
			.contains("신고가 평소보다 많습니다")
			.containsPattern("최근 24시간 신고 \\d+건");
	}

	@Test
	@DisplayName("관리자는 신고 상세 화면에서 사진과 위치와 검수 버튼을 확인한다")
	void adminReportDetailPageShowsPhotoLocationAndReviewActions() throws Exception {
		String reportId = createReportWithPhotoAndLocation("상세에서 확인할 신고");

		String html = mockMvc.perform(get("/admin/reports/{reportId}/page", reportId)
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("신고 상세")
			.contains("상세에서 확인할 신고")
			.contains("elevator-notice.png")
			.contains("/admin/reports/%s/photo/thumbnail".formatted(reportId))
			.doesNotContain("객체 키")
			.doesNotContain("facility-reports/")
			.contains("37.302421")
			.contains("126.866221")
			.contains("name=\"decision\" value=\"ACCEPT\"")
			.contains("name=\"decision\" value=\"REJECT\"")
			.contains("name=\"decision\" value=\"MARK_DUPLICATE\"");
		assertThat(auditEventRepository.findRecent(AdminAuditEventType.PRIVACY_READ, 1))
			.singleElement()
			.satisfies(event -> {
				assertThat(event.actor()).isEqualTo("admin-test");
				assertThat(event.targetType()).isEqualTo("FACILITY_REPORT");
				assertThat(event.targetId()).isEqualTo(reportId);
				assertThat(event.action()).isEqualTo("VIEW_REPORT_DETAIL");
				assertThat(event.reason()).contains("신고 상세 조회");
			});
	}

	@Test
	@DisplayName("관리자는 신고 번호 기준 endpoint로 신고 사진 thumbnail과 원본을 조회한다")
	void adminReportPhotoEndpointsLoadReportBoundPhotoAndWritePrivacyAudit() throws Exception {
		String reportId = createReportWithPhotoAndLocation("사진 endpoint로 확인할 신고");

		mockMvc.perform(get("/admin/reports/{reportId}/photo/thumbnail", reportId)
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store, private"))
			.andExpect(header().string("Content-Type", "image/png"));

		mockMvc.perform(get("/admin/reports/{reportId}/photo/original", reportId)
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store, private"))
			.andExpect(header().string("Content-Type", "image/png"));

		assertThat(auditEventRepository.findRecent(AdminAuditEventType.PRIVACY_READ, 2))
			.extracting(event -> event.action() + ":" + event.targetType() + ":" + event.targetId() + ":" + event.reason())
			.containsExactly(
				"VIEW_REPORT_PHOTO_ORIGINAL:FACILITY_REPORT_PHOTO:" + reportId + ":업무 맥락: 신고 원본 사진 조회",
				"VIEW_REPORT_PHOTO_THUMBNAIL:FACILITY_REPORT_PHOTO:" + reportId + ":업무 맥락: 신고 사진 미리보기 조회"
			);
	}

	@Test
	@DisplayName("신고 검수 권한만 있는 관리자는 신고 사진 endpoint에 접근할 수 없다")
	void adminReportPhotoEndpointsRequirePhotoReadPermission() throws Exception {
		String reportId = createReportWithPhotoAndLocation("사진 권한 경계를 확인할 신고");
		RequestPostProcessor reportReviewer = user("report-reviewer")
			.authorities(new SimpleGrantedAuthority(AdminPermission.REPORT_REVIEW.authority()));

		mockMvc.perform(get("/admin/reports/{reportId}/photo/thumbnail", reportId)
				.with(reportReviewer))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/admin/reports/{reportId}/photo/original", reportId)
				.with(reportReviewer))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("관리자 신고 사진 조회는 object key query endpoint를 열지 않는다")
	void adminReportPhotoQueryEndpointIsNotExposed() throws Exception {
		createReportWithPhotoAndLocation("object key 조회를 막을 신고");

		mockMvc.perform(get("/admin/reports/photos")
				.param("objectKey", "facility-reports/other-report/original.png")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("관리자는 상세 화면에서 승인한 뒤 상세 화면으로 돌아온다")
	void adminReportDetailPageReviewsReportAndRedirectsToDetail() throws Exception {
		String reportId = createReport("승인할 신고");

		mockMvc.perform(post("/admin/reports/{reportId}/page/review", reportId)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/reports/%s/page".formatted(reportId)))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("decision", "ACCEPT"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/reports/%s/page".formatted(reportId)));

		String html = mockMvc.perform(get("/admin/reports/{reportId}/page", reportId)
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("반영됨")
			.contains("admin-test");
	}

	@Test
	@DisplayName("신고 검수 폼은 같은 command token 재전송을 409로 차단한다")
	void reportReviewRejectsRepeatedCommandToken() throws Exception {
		String reportId = createReport("중복 제출을 막을 신고");
		MockHttpSession session = new MockHttpSession();
		String token = commandTokenFrom(getAdminHtml("/admin/reports/%s/page".formatted(reportId), session));

		mockMvc.perform(post("/admin/reports/{reportId}/page/review", reportId)
				.session(session)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("commandToken", token)
				.param("decision", "ACCEPT"))
			.andExpect(status().is3xxRedirection());

		String conflictHtml = mockMvc.perform(post("/admin/reports/{reportId}/page/review", reportId)
				.session(session)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("commandToken", token)
				.param("decision", "REJECT"))
			.andExpect(status().isConflict())
			.andReturn()
			.getResponse()
			.getContentAsString();

		String detailHtml = getAdminHtml("/admin/reports/%s/page".formatted(reportId), session);

		assertThat(conflictHtml)
			.contains("요청이 최신 상태와 충돌했습니다")
			.contains("이미 처리되었거나 만료된 관리자 요청입니다");
		assertThat(detailHtml)
			.contains("반영됨")
			.doesNotContain("반려됨");
		assertThat(auditEventRepository.findRecent(AdminAuditEventType.ADMIN_ACTION, 1))
			.singleElement()
			.satisfies(event -> {
				assertThat(event.outcome()).isEqualTo(AdminAuditOutcome.FAILURE);
				assertThat(event.action()).isEqualTo("POST /admin/reports/{reportId}/page/review");
			});
	}

	@Test
	@DisplayName("신고 검수 폼은 판정 누락 오류와 입력값을 상세 화면에 표시한다")
	void reportReviewValidationErrorRendersAdminHtml() throws Exception {
		String originalReportId = createReport("기준 신고");
		String reportId = createReport("판정 누락 신고");

		String html = mockMvc.perform(post("/admin/reports/{reportId}/page/review", reportId)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/reports/%s/page".formatted(reportId)))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("duplicateOfReportId", originalReportId))
			.andExpect(status().isBadRequest())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("신고 상세")
			.contains("입력값을 확인해 주세요")
			.contains("신고 판정 값을 선택해야 합니다.")
			.contains("판정 누락 신고")
			.contains("value=\"%s\"".formatted(originalReportId));
		assertThat(auditEventRepository.findRecent(AdminAuditEventType.PRIVACY_READ, 1))
			.singleElement()
			.satisfies(event -> {
				assertThat(event.actor()).isEqualTo("admin-test");
				assertThat(event.targetType()).isEqualTo("FACILITY_REPORT");
				assertThat(event.targetId()).isEqualTo(reportId);
				assertThat(event.action()).isEqualTo("VIEW_REPORT_DETAIL");
			});
	}

	@Test
	@DisplayName("관리자는 신고 상세 화면에서 검수 감사 이력을 확인한다")
	void adminReportDetailPageShowsReviewAuditHistory() throws Exception {
		String reportId = createReport("감사 이력을 확인할 신고");

		mockMvc.perform(post("/admin/reports/{reportId}/page/review", reportId)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/reports/%s/page".formatted(reportId)))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("decision", "REJECT"))
			.andExpect(status().is3xxRedirection());

		String html = mockMvc.perform(get("/admin/reports/{reportId}/page", reportId)
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("감사 이력")
			.contains("admin-test")
			.contains("반려")
			.contains("접수됨")
			.contains("반려됨");
	}

	@Test
	@DisplayName("관리자는 상세 화면에서 중복 처리 기준 신고를 확인한다")
	void adminReportDetailPageShowsDuplicateOriginalReport() throws Exception {
		String originalReportId = createReport("먼저 접수된 고장 신고");
		String duplicatedReportId = createReport("같은 시설에 대해 다시 들어온 신고");

		mockMvc.perform(post("/admin/reports/{reportId}/page/review", duplicatedReportId)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/reports/%s/page".formatted(duplicatedReportId)))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("decision", "MARK_DUPLICATE")
				.param("duplicateOfReportId", originalReportId))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/reports/%s/page".formatted(duplicatedReportId)));

		String html = mockMvc.perform(get("/admin/reports/{reportId}/page", duplicatedReportId)
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("중복")
			.contains("기준 신고")
			.contains(originalReportId);
	}

	@Test
	@DisplayName("관리자 신고 화면은 관리자 인증을 요구한다")
	void adminReportPagesRequireAdminAuthentication() throws Exception {
		String reportId = createReport("인증 검증용 신고");

		mockMvc.perform(get("/admin/reports/page"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/reports/page")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/admin/reports/{reportId}/page", reportId))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/reports/{reportId}/page", reportId)
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/admin/reports/{reportId}/photo/thumbnail", reportId)
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/admin/reports/{reportId}/page/review", reportId)
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("decision", "ACCEPT"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/admin/reports/{reportId}/page/review", reportId)
				.with(httpBasic("basic-user", "user-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("decision", "ACCEPT"))
			.andExpect(status().isForbidden());
	}

	private String createReport(String description) throws Exception {
		return createReport(description, "");
	}

	private String getAdminHtml(String path, MockHttpSession session) throws Exception {
		return mockMvc.perform(get(path)
				.session(session)
				.with(httpBasic("admin-test", "admin-test-password")))
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

	private String createReportWithPhotoAndLocation(String description) throws Exception {
		return createReport(
			description,
			"""
				,
						  "photoFileName": "elevator-notice.png",
						  "photoContentType": "image/png",
						  "photoDataBase64": "%s",
						  "latitude": 37.302421,
						  "longitude": 126.866221
					"""
					.formatted(VALID_PNG_BASE64)
			);
	}

	private String createReport(String description, String optionalJson) throws Exception {
		String response = mockMvc.perform(post("/api/v1/reports")
				.with(httpBasic("basic-user", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "%s"%s
					}
					""".formatted(description, optionalJson)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(response, "$.data.id");
	}
}
