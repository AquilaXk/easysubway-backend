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
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 역 구조도 요약 화면")
class TransitStationLayoutAdminPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("관리자는 역 구조도 기준 자료와 내부 이동 그래프를 읽기 전용으로 확인한다")
	void adminGetsStationLayoutPage() throws Exception {
		String html = mockMvc.perform(get("/admin/stations/station-sangnoksu/layouts/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("역 구조도 요약")
			.contains("상록수")
			.contains("수도권 4호선")
			.contains("구조도 기준 자료")
			.contains("상록수역 역사 안내도")
			.contains("운영기관 안내도 확인용")
			.contains("상업적 사용 불가")
			.contains("출처 표시 필요")
			.contains("쉬운 내부 구조도")
			.contains("DRAFT")
			.contains("OFFICIAL_DIAGRAM_REFERENCED")
			.contains("B1")
			.contains("내부 이동 노드")
			.contains("1번 출구 엘리베이터")
			.contains("휠체어 이동 가능")
			.contains("내부 이동 간선")
			.contains("node-sangnoksu-elevator-1")
			.contains("node-sangnoksu-faregate")
			.contains("계단 없음")
			.contains("엘리베이터 필요")
			.contains("75초")
			.contains("신뢰도 92")
			.doesNotContain("{\"nodes\":[],\"edges\":[]}")
			.doesNotContain("<form")
			.doesNotContain("name=\"_csrf\"")
			.doesNotContain("<img");
	}

	@Test
	@DisplayName("관리자 역 구조도 요약 화면은 관리자 인증을 요구한다")
	void stationLayoutPageRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/stations/station-sangnoksu/layouts/page"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/stations/station-sangnoksu/layouts/page")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("존재하지 않는 역의 구조도 요약 화면은 공통 404 응답을 반환한다")
	void missingStationLayoutPageReturnsCommonErrorResponse() throws Exception {
		mockMvc.perform(get("/admin/stations/unknown-station/layouts/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("역 정보를 찾을 수 없습니다."));
	}
}
