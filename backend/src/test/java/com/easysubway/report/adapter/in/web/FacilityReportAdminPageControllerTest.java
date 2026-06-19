package com.easysubway.report.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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
			.contains("신고 처리 시간")
			.contains("처리 완료 신고 없음");
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
			.contains("객체 키")
			.contains("/admin/reports/photos?objectKey=facility-reports/")
			.contains("37.302421")
			.contains("126.866221")
			.contains("name=\"decision\" value=\"ACCEPT\"")
			.contains("name=\"decision\" value=\"REJECT\"")
			.contains("name=\"decision\" value=\"MARK_DUPLICATE\"");
	}

	@Test
	@DisplayName("관리자는 상세 화면에서 승인한 뒤 상세 화면으로 돌아온다")
	void adminReportDetailPageReviewsReportAndRedirectsToDetail() throws Exception {
		String reportId = createReport("승인할 신고");

		mockMvc.perform(post("/admin/reports/{reportId}/page/review", reportId)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
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
	@DisplayName("관리자는 신고 상세 화면에서 검수 감사 이력을 확인한다")
	void adminReportDetailPageShowsReviewAuditHistory() throws Exception {
		String reportId = createReport("감사 이력을 확인할 신고");

		mockMvc.perform(post("/admin/reports/{reportId}/page/review", reportId)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
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
