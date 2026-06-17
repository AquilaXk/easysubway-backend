package com.easysubway.route.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
	"easysubway.user.username=anonymous-user-1",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("관리자 경로 검색 현황 페이지")
class RouteSearchAdminPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("관리자는 경로 검색의 전체, 상태별, 이동 프로필별 건수를 확인한다")
	void adminGetsRouteSearchDashboardPage() throws Exception {
		createRouteSearch("SENIOR");
		createRouteSearch("WHEELCHAIR");

		String html = mockMvc.perform(get("/admin/routes/searches/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("경로 검색 현황")
			.contains("전체 검색")
			.contains(">2<")
			.contains("경로 찾음")
			.contains("경로 차단")
			.contains(">0<")
			.contains("이동 프로필별 검색")
			.contains("고령자")
			.contains("휠체어 사용자")
			.doesNotContain("routeSearchId")
			.doesNotContain("station-sangnoksu");
	}

	@Test
	@DisplayName("경로 검색 현황 페이지는 관리자 인증을 요구한다")
	void routeSearchDashboardRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/routes/searches/page"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/routes/searches/page")
				.with(httpBasic("anonymous-user-1", "user-test-password")))
			.andExpect(status().isForbidden());
	}

	private void createRouteSearch(String mobilityType) throws Exception {
		mockMvc.perform(post("/api/v1/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "mobilityType": "%s"
					}
					""".formatted(mobilityType)))
			.andExpect(status().isOk());
	}
}
