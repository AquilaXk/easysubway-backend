package com.easysubway.quality.adapter.in.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password",
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 데이터 품질 요약 API")
class DataQualityControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("관리자는 마스터 데이터 품질 요약을 조회한다")
	void adminGetsDataQualitySummary() throws Exception {
		mockMvc.perform(get("/admin/data-quality/summary")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.totalStations").value(2))
			.andExpect(jsonPath("$.data.totalExits").value(3))
			.andExpect(jsonPath("$.data.totalFacilities").value(3))
			.andExpect(jsonPath("$.data.stationQualityCounts.LEVEL_1").value(2))
			.andExpect(jsonPath("$.data.exitConfidenceCounts.HIGH").value(2))
			.andExpect(jsonPath("$.data.exitConfidenceCounts.MEDIUM").value(1))
			.andExpect(jsonPath("$.data.facilityConfidenceCounts.HIGH").value(1))
			.andExpect(jsonPath("$.data.facilityConfidenceCounts.MEDIUM").value(1))
			.andExpect(jsonPath("$.data.facilityConfidenceCounts.NEEDS_VERIFICATION").value(1))
			.andExpect(jsonPath("$.data.needsVerificationFacilityCount").value(1))
			.andExpect(jsonPath("$.data.missingStationVerificationDateCount").value(0));
	}

	@Test
	@DisplayName("데이터 품질 요약 API는 관리자만 사용할 수 있다")
	void dataQualitySummaryRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/data-quality/summary"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/data-quality/summary")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());
	}
}
