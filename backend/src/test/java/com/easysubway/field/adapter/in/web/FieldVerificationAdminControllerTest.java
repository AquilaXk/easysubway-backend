package com.easysubway.field.adapter.in.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password",
	"easysubway.user.username=anonymous-user-1",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("관리자 현장 검증 API")
class FieldVerificationAdminControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("관리자는 역별 현장 검증 세션과 항목을 조회한다")
	void adminGetsStationFieldVerification() throws Exception {
		mockMvc.perform(get("/admin/field-verifications/stations/station-sangnoksu")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.sessionId").value("field-verification-sangnoksu-2026-06"))
			.andExpect(jsonPath("$.data.stationId").value("station-sangnoksu"))
			.andExpect(jsonPath("$.data.stationName").value("상록수역"))
			.andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
			.andExpect(jsonPath("$.data.items[0].type").value("EXIT"))
			.andExpect(jsonPath("$.data.items[0].label").value("출구"))
			.andExpect(jsonPath("$.data.items[4].type").value("PLATFORM_TRANSFER"))
			.andExpect(jsonPath("$.data.items[4].label").value("승강장/환승 동선"));
	}

	@Test
	@DisplayName("현장 검증 API는 관리자 인증을 요구한다")
	void fieldVerificationRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/field-verifications/stations/station-sangnoksu"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/field-verifications/stations/station-sangnoksu")
				.with(httpBasic("anonymous-user-1", "user-test-password")))
			.andExpect(status().isForbidden());
	}
}
