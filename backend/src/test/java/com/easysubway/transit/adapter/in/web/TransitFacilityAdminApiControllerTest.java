package com.easysubway.transit.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
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
	"easysubway.user.username=anonymous-user-1",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 시설 상태 요약 API")
class TransitFacilityAdminApiControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("관리자는 시설 상태 요약을 JSON으로 조회한다")
	void adminGetsFacilityStatusSummary() throws Exception {
		var result = mockMvc.perform(get("/admin/facilities/summary")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].facilityId").value("facility-sangnoksu-elevator-1"))
			.andExpect(jsonPath("$.data[0].stationId").value("station-sangnoksu"))
			.andExpect(jsonPath("$.data[0].stationName").value("상록수"))
			.andExpect(jsonPath("$.data[0].facilityName").value("1번 출구 엘리베이터"))
			.andExpect(jsonPath("$.data[0].typeLabel").value("엘리베이터"))
			.andExpect(jsonPath("$.data[0].status").value("NORMAL"))
			.andExpect(jsonPath("$.data[0].statusLabel").value("정상"))
			.andExpect(jsonPath("$.data[0].confidenceLabel").value("최근 확인된 정보"))
			.andExpect(jsonPath("$.data[0].sourceLabel").value("공식 안내"))
			.andExpect(jsonPath("$.data[0].lastUpdatedAt").value("2026-06-12"))
			.andExpect(jsonPath("$.data[2].facilityName").value("장애인 화장실"))
			.andExpect(jsonPath("$.data[2].statusLabel").value("확인 필요"))
			.andReturn();

		String json = result.getResponse().getContentAsString();
		assertThat(json)
			.doesNotContain("anonymous-user-1")
			.doesNotContain("deviceToken")
			.doesNotContain("photoDataBase64")
			.doesNotContain("description");
	}

	@Test
	@DisplayName("시설 상태 요약 API는 관리자 인증을 요구한다")
	void facilityStatusSummaryRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/facilities/summary"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/facilities/summary")
				.with(httpBasic("anonymous-user-1", "user-test-password")))
			.andExpect(status().isForbidden());
	}
}
