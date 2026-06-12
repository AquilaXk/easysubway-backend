package com.easysubway.report.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-test",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
class FacilityReportControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void createReportReturnsSubmittedReportAndCanBeRead() throws Exception {
		String response = mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-1",
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "엘리베이터 문이 열리지 않습니다.",
					  "latitude": 37.302421,
					  "longitude": 126.866221
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").isNotEmpty())
			.andExpect(jsonPath("$.data.stationId").value("station-sangnoksu"))
			.andExpect(jsonPath("$.data.facilityId").value("facility-sangnoksu-elevator-1"))
			.andExpect(jsonPath("$.data.reportType").value("BROKEN"))
			.andExpect(jsonPath("$.data.status").value("SUBMITTED"))
			.andReturn()
			.getResponse()
			.getContentAsString();

		String reportId = JsonPath.read(response, "$.data.id");

		mockMvc.perform(get("/api/v1/reports/{reportId}", reportId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value(reportId))
			.andExpect(jsonPath("$.data.description").value("엘리베이터 문이 열리지 않습니다."));
	}

	@Test
	void createReportReturnsCommonErrorForUnknownFacility() throws Exception {
		mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-1",
					  "stationId": "station-sangnoksu",
					  "facilityId": "missing-facility",
					  "reportType": "BROKEN",
					  "description": "엘리베이터가 멈춰 있습니다."
					}
					"""))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("시설 정보를 찾을 수 없습니다."));
	}

	@Test
	void createReportReturnsCommonErrorForMissingReportType() throws Exception {
		mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-1",
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "description": "신고 유형이 없는 요청입니다."
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("신고 유형을 선택해야 합니다."));
	}

	@Test
	void createReportReturnsCommonErrorForInvalidReportType() throws Exception {
		mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-1",
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKNN",
					  "description": "잘못된 신고 유형입니다."
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("요청 본문을 확인해야 합니다."));
	}

	@Test
	void missingReportReturnsCommonErrorResponse() throws Exception {
		mockMvc.perform(get("/api/v1/reports/missing-report"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("신고 정보를 찾을 수 없습니다."));
	}

	@Test
	void reviewReportStoresAcceptedStatusAndCanBeRead() throws Exception {
		String response = mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-review",
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "엘리베이터 문이 열리지 않습니다."
					}
					"""))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();

		String reportId = JsonPath.read(response, "$.data.id");

		mockMvc.perform(post("/admin/reports/{reportId}/review", reportId)
				.with(httpBasic("admin-test", "admin-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "decision": "ACCEPT"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value(reportId))
			.andExpect(jsonPath("$.data.status").value("ACCEPTED"))
			.andExpect(jsonPath("$.data.reviewedAt").isNotEmpty())
			.andExpect(jsonPath("$.data.reviewedBy").value("admin-test"));

		mockMvc.perform(get("/api/v1/reports/{reportId}", reportId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("ACCEPTED"))
			.andExpect(jsonPath("$.data.reviewedBy").value("admin-test"));
	}

	@Test
	void reviewReportRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(post("/admin/reports/report-1/review")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "decision": "ACCEPT"
					}
					"""))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void reviewReportRejectsInvalidDecisionRequest() throws Exception {
		mockMvc.perform(post("/admin/reports/report-1/review")
				.with(httpBasic("admin-test", "admin-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("검수 결과를 선택해야 합니다."));
	}

	@Test
	void reviewReportReturnsCommonErrorForMissingReport() throws Exception {
		mockMvc.perform(post("/admin/reports/missing-report/review")
				.with(httpBasic("admin-test", "admin-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "decision": "REJECT"
					}
					"""))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("신고 정보를 찾을 수 없습니다."));
	}
}
