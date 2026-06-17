package com.easysubway.quality.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password",
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 데이터 품질 대시보드")
class DataQualityAdminPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("관리자는 데이터 품질 대시보드에서 주요 집계와 보강 대상을 확인한다")
	void adminGetsDataQualityDashboardPage() throws Exception {
		String acceptedReportId = createReport("검증률에 반영할 승인 신고");
		String pendingReportId = createReport("검증률에 반영할 대기 신고");
		acceptReport(acceptedReportId);

		String html = mockMvc.perform(get("/admin/data-quality/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("데이터 품질 대시보드")
			.contains("전체 역")
			.contains(">2<")
			.contains("전체 출구")
			.contains("전체 시설")
			.contains(">3<")
			.contains("확인 필요한 시설")
			.contains("갱신 지연 시설")
			.contains("검수일 없는 역")
			.contains("Level 1")
			.contains("기본 정보 확인")
			.contains("높음")
			.contains("보통")
			.contains("확인 필요")
			.contains("지역별 데이터 품질")
			.contains("수도권")
			.contains("운영기관")
			.contains("노선")
			.contains("역")
			.contains("Level 2")
			.contains("Level 3")
			.contains("Level 4")
			.contains("시설 상태 갱신 지연")
			.contains("상태")
			.contains("지연 시설")
			.contains("사용자 제보 검증률")
			.contains("전체 제보")
			.contains("검증 완료")
			.contains("검증 대기")
			.contains("50%")
			.contains("접수됨")
			.contains("반영됨")
			.contains("반복 고장 신고 시설")
			.contains("역")
			.contains("시설")
			.contains("현재 상태")
			.contains("고장 신고 수")
			.contains("상록수")
			.contains("1번 출구 엘리베이터")
			.contains("정상")
			.doesNotContain("station-sangnoksu")
			.doesNotContain("exit-sangnoksu")
			.doesNotContain("facility-sangnoksu")
			.doesNotContain(acceptedReportId)
			.doesNotContain(pendingReportId);
	}

	@Test
	@DisplayName("데이터 품질 대시보드는 관리자 인증을 요구한다")
	void dataQualityDashboardRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/data-quality/page"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/data-quality/page")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());
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

	private void acceptReport(String reportId) throws Exception {
		mockMvc.perform(post("/admin/reports/{reportId}/page/review", reportId)
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("decision", "ACCEPT"))
			.andExpect(status().is3xxRedirection());
	}
}
