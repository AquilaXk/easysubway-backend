package com.easysubway.report.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.assertj.core.api.Assertions;
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
@DisplayName("시설 신고 API")
class FacilityReportControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("시설 신고를 생성하고 같은 식별자로 조회한다")
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
	@DisplayName("존재하지 않는 시설 신고 요청은 공통 404 응답을 반환한다")
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
	@DisplayName("신고 유형이 없는 요청은 공통 400 응답을 반환한다")
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
	@DisplayName("알 수 없는 신고 유형은 공통 400 응답을 반환한다")
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
	@DisplayName("존재하지 않는 신고 조회는 공통 404 응답을 반환한다")
	void missingReportReturnsCommonErrorResponse() throws Exception {
		mockMvc.perform(get("/api/v1/reports/missing-report"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("신고 정보를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("관리자는 신고를 승인하고 조회 결과에서 검수 상태를 확인할 수 있다")
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
	@DisplayName("신고 검수는 관리자 인증을 요구한다")
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
	@DisplayName("검수 결정값이 없는 요청은 공통 400 응답을 반환한다")
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
	@DisplayName("존재하지 않는 신고 검수는 공통 404 응답을 반환한다")
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

	@Test
	@DisplayName("관리자는 신고 목록을 최신순으로 조회하고 상태별로 좁혀 본다")
	void adminListsReportsByNewestFirstAndStatus() throws Exception {
		String submittedReportId = createReport("anonymous-user-list-submitted", "승강기 문이 닫히지 않습니다.");
		String acceptedReportId = createReport("anonymous-user-list-accepted", "엘리베이터 위치가 다릅니다.");

		mockMvc.perform(post("/admin/reports/{reportId}/review", acceptedReportId)
				.with(httpBasic("admin-test", "admin-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "decision": "ACCEPT"
					}
					"""))
			.andExpect(status().isOk());

		String listResponse = mockMvc.perform(get("/admin/reports")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data").isArray())
			.andReturn()
			.getResponse()
			.getContentAsString();

		List<String> reportIds = JsonPath.read(listResponse, "$.data[*].id");
		Assertions.assertThat(reportIds)
			.contains(acceptedReportId, submittedReportId);
		Assertions.assertThat(reportIds.indexOf(acceptedReportId))
			.isLessThan(reportIds.indexOf(submittedReportId));

		String submittedOnlyResponse = mockMvc.perform(get("/admin/reports")
				.queryParam("status", "SUBMITTED")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andReturn()
			.getResponse()
			.getContentAsString();

		List<String> submittedIds = JsonPath.read(submittedOnlyResponse, "$.data[*].id");
		Assertions.assertThat(submittedIds).contains(submittedReportId);
		Assertions.assertThat(submittedIds).doesNotContain(acceptedReportId);
	}

	@Test
	@DisplayName("관리자 신고 목록은 관리자만 사용할 수 있다")
	void adminReportListRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/reports"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/reports")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("관리자 신고 목록은 잘못된 상태 필터를 공통 오류 형식으로 응답한다")
	void adminReportListRejectsInvalidStatusWithCommonErrorResponse() throws Exception {
		mockMvc.perform(get("/admin/reports")
				.queryParam("status", "submitted")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("요청 값을 확인해야 합니다."));
	}

	@Test
	@DisplayName("관리자는 신고 상세에서 사진과 위치 정보를 확인한다")
	void adminReadsReportDetailWithPhotoAndLocation() throws Exception {
		String response = mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-admin-detail",
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "엘리베이터 앞 안내문이 떨어져 있습니다.",
					  "photoUrl": "https://cdn.example.test/reports/elevator-notice.jpg",
					  "latitude": 37.302421,
					  "longitude": 126.866221
					}
					"""))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();

		String reportId = JsonPath.read(response, "$.data.id");

		mockMvc.perform(get("/admin/reports/{reportId}", reportId)
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value(reportId))
			.andExpect(jsonPath("$.data.userId").value("anonymous-user-admin-detail"))
			.andExpect(jsonPath("$.data.description").value("엘리베이터 앞 안내문이 떨어져 있습니다."))
			.andExpect(jsonPath("$.data.photoUrl").value("https://cdn.example.test/reports/elevator-notice.jpg"))
			.andExpect(jsonPath("$.data.latitude").value(37.302421))
			.andExpect(jsonPath("$.data.longitude").value(126.866221))
			.andExpect(jsonPath("$.data.status").value("SUBMITTED"));
	}

	@Test
	@DisplayName("관리자 신고 상세는 관리자만 사용할 수 있다")
	void adminReportDetailRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/reports/report-1"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/reports/report-1")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("존재하지 않는 관리자 신고 상세는 공통 404 응답을 반환한다")
	void adminReportDetailReturnsCommonErrorForMissingReport() throws Exception {
		mockMvc.perform(get("/admin/reports/missing-report")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("신고 정보를 찾을 수 없습니다."));
	}

	private String createReport(String userId, String description) throws Exception {
		String response = mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "%s",
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "%s"
					}
					""".formatted(userId, description)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();

		return JsonPath.read(response, "$.data.id");
	}
}
