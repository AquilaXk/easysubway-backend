package com.easysubway.transit.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 시설 상태 화면")
class TransitFacilityAdminPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("관리자는 시설 상태 화면에서 시설과 현재 상태를 확인한다")
	void adminGetsFacilityStatusPage() throws Exception {
		String html = mockMvc.perform(get("/admin/facilities/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("시설 상태 관리")
			.contains("상록수")
			.contains("1번 출구 엘리베이터")
			.contains("엘리베이터")
			.contains("정상")
			.contains("장애인 화장실")
			.contains("확인 필요")
			.contains("정보 신뢰도 높음")
			.contains("name=\"status\"")
			.contains("name=\"_csrf\"");
	}

	@Test
	@DisplayName("관리자는 시설 상태 화면에서 상태를 변경한 뒤 목록으로 돌아온다")
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	void adminUpdatesFacilityStatusFromPageAndRedirectsToList() throws Exception {
		mockMvc.perform(post("/admin/facilities/facility-sangnoksu-elevator-1/page/status")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("status", "BROKEN"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/facilities/page"));

		String html = mockMvc.perform(get("/admin/facilities/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("1번 출구 엘리베이터")
			.contains("고장");
	}

	@Test
	@DisplayName("관리자 시설 상태 화면은 관리자 인증을 요구한다")
	void facilityStatusPagesRequireAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/facilities/page"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/facilities/page")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/admin/facilities/facility-sangnoksu-elevator-1/page/status")
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("status", "BROKEN"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/admin/facilities/facility-sangnoksu-elevator-1/page/status")
				.with(httpBasic("basic-user", "user-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("status", "BROKEN"))
			.andExpect(status().isForbidden());
	}
}
