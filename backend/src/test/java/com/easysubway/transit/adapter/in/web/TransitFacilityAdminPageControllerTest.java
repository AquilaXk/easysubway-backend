package com.easysubway.transit.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
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
	@DisplayName("시설 상태 화면은 page size와 현재 페이지를 링크에 표시한다")
	void facilityStatusPageShowsPaginationLinks() throws Exception {
		String html = mockMvc.perform(get("/admin/facilities/page")
				.param("size", "1")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("시설 상태 목록 페이지")
			.contains("aria-current=\"page\"")
			.contains("page=1&amp;size=1")
			.contains("다음");
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
	@DisplayName("시설 상태 변경 validation 실패는 관리자 shell 안에서 field error를 보여준다")
	void facilityStatusValidationErrorRendersAdminHtml() throws Exception {
		String html = mockMvc.perform(post("/admin/facilities/facility-sangnoksu-elevator-1/page/status")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED))
			.andExpect(status().isBadRequest())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("통합 관리자")
			.contains("입력값을 확인해 주세요")
			.contains("시설 상태를 선택해야 합니다.")
			.contains("1번 출구 엘리베이터");
	}

	@Test
	@DisplayName("시설 편집 validation 실패는 입력값과 선택 상태를 보존한다")
	void facilityEditorValidationErrorPreservesInput() throws Exception {
		String html = mockMvc.perform(post("/admin/facilities/editor/page")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("stationId", "station-sangnoksu")
				.param("type", "ELEVATOR")
				.param("floorFrom", "B1")
				.param("floorTo", "1F")
				.param("status", "BROKEN")
				.param("dataConfidence", "HIGH")
				.param("dataSourceType", "OFFICIAL_API")
				.param("description", "입력값 보존 메모"))
			.andExpect(status().isBadRequest())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("시설명을 입력해야 합니다.")
			.contains("value=\"B1\"")
			.contains("value=\"1F\"")
			.contains("입력값 보존 메모");
	}

	@Test
	@DisplayName("시설 편집 숫자 변환 실패는 위도 원본 입력값을 보존한다")
	void facilityEditorTypeMismatchPreservesRejectedLatitude() throws Exception {
		String html = mockMvc.perform(post("/admin/facilities/editor/page")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("stationId", "station-sangnoksu")
				.param("type", "ELEVATOR")
				.param("name", "1번 출구 엘리베이터")
				.param("floorFrom", "B1")
				.param("floorTo", "1F")
				.param("latitude", "not-a-number")
				.param("longitude", "126.866768")
				.param("status", "BROKEN")
				.param("dataConfidence", "HIGH")
				.param("dataSourceType", "OFFICIAL_API")
				.param("description", "숫자 변환 실패 메모"))
			.andExpect(status().isBadRequest())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("입력값을 확인해 주세요")
			.contains("value=\"not-a-number\"")
			.contains("value=\"126.866768\"")
			.contains("숫자 변환 실패 메모");
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
				.andExpect(status().isForbidden())
				.andExpect(forwardedUrl("/admin/error/page"));
		}
	}
