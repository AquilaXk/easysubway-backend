package com.easysubway.operator.adapter.in.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password",
	"easysubway.operator.username=operator-user",
	"easysubway.operator.password=operator-test-password",
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("운영기관 반복 고장 시설 통계 API")
class OperatorRepeatedBrokenFacilitiesControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("운영기관 계정은 반복 고장 시설 통계를 JSON으로 조회한다")
	void operatorGetsRepeatedBrokenFacilities() throws Exception {
		createBrokenReport("첫 번째 엘리베이터 고장 신고");
		createBrokenReport("두 번째 엘리베이터 고장 신고");
		createReport("facility-sangnoksu-elevator-1", "LOCATION_WRONG", "위치 설명 오류 신고");
		createBrokenReport("station-sangnoksu", "facility-sangnoksu-escalator-1", "에스컬레이터 단일 고장 신고");

		mockMvc.perform(get("/operator/api/repeated-broken-facilities")
				.with(httpBasic("operator-user", "operator-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.totalRepeatedFacilityCount").value(1))
			.andExpect(jsonPath("$.data.rows[0].stationName").value("상록수"))
			.andExpect(jsonPath("$.data.rows[0].facilityName").value("1번 출구 엘리베이터"))
			.andExpect(jsonPath("$.data.rows[0].statusLabel").value("정상"))
			.andExpect(jsonPath("$.data.rows[0].reportCount").value(2))
			.andExpect(jsonPath("$.data.rows[0].stationId").doesNotExist())
			.andExpect(jsonPath("$.data.rows[0].facilityId").doesNotExist())
			.andExpect(jsonPath("$.data.rows[0].userId").doesNotExist())
			.andExpect(jsonPath("$.data.rows[0].description").doesNotExist());
	}

	@Test
	@DisplayName("운영기관 반복 고장 시설 통계 API는 운영기관 계정 인증을 요구한다")
	void repeatedBrokenFacilitiesRequiresOperatorAuthentication() throws Exception {
		mockMvc.perform(get("/operator/api/repeated-broken-facilities"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/operator/api/repeated-broken-facilities")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/operator/api/repeated-broken-facilities")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isForbidden());
	}

	private void createBrokenReport(String description) throws Exception {
		createBrokenReport("station-sangnoksu", "facility-sangnoksu-elevator-1", description);
	}

	private void createBrokenReport(String stationId, String facilityId, String description) throws Exception {
		createReport(stationId, facilityId, "BROKEN", description);
	}

	private void createReport(String facilityId, String reportType, String description) throws Exception {
		createReport("station-sangnoksu", facilityId, reportType, description);
	}

	private void createReport(String stationId, String facilityId, String reportType, String description) throws Exception {
		mockMvc.perform(post("/api/v1/reports")
				.with(httpBasic("basic-user", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "stationId": "%s",
					  "facilityId": "%s",
					  "reportType": "%s",
					  "description": "%s"
					}
					""".formatted(stationId, facilityId, reportType, description)))
			.andExpect(status().isCreated());
	}
}
