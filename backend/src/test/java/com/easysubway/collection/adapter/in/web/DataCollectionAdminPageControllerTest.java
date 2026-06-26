package com.easysubway.collection.adapter.in.web;

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
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password",
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 데이터 수집 배치 화면")
class DataCollectionAdminPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("관리자는 데이터 수집 화면에서 실행 버튼과 최근 실행 기록을 확인한다")
	void adminGetsDataCollectionPageWithRunFormAndRecentRuns() throws Exception {
		runTransitMasterCollection();

		String html = mockMvc.perform(get("/admin/data-collections/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("데이터 수집 배치")
			.contains("도시철도 마스터 데이터 수집")
			.contains("수집 실행")
			.contains("최근 실행 기록")
			.contains("완료")
			.contains("재시도")
			.contains("다음 행동")
			.contains("불필요")
			.contains("수집이 완료되었습니다. 최근 데이터 품질 화면에서 반영 결과를 확인하세요.")
			.contains("FETCH")
			.contains("STAGE")
			.contains("PUBLISH")
			.contains("건너뜀")
			.contains("수동 필요")
			.contains("admin-user")
			.contains(">14<")
			.contains("name=\"source\"")
			.contains("value=\"TRANSIT_MASTER\"")
			.contains("name=\"_csrf\"");
	}

	@Test
	@DisplayName("관리자는 데이터 수집 화면에서 배치를 실행한 뒤 목록으로 돌아온다")
	void adminRunsDataCollectionFromPageAndRedirectsToList() throws Exception {
		mockMvc.perform(post("/admin/data-collections/page/run")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("source", "TRANSIT_MASTER"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/data-collections/page"));

		String html = mockMvc.perform(get("/admin/data-collections/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("도시철도 마스터")
			.contains("완료")
			.contains("admin-user");
	}

	@Test
	@DisplayName("관리자 데이터 수집 화면은 관리자 인증을 요구한다")
	void dataCollectionPagesRequireAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/data-collections/page"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/data-collections/page")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/admin/data-collections/page/run")
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("source", "TRANSIT_MASTER"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/admin/data-collections/page/run")
				.with(httpBasic("basic-user", "user-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("source", "TRANSIT_MASTER"))
			.andExpect(status().isForbidden());
	}

	private void runTransitMasterCollection() throws Exception {
		mockMvc.perform(post("/admin/data-collections/runs")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "source": "TRANSIT_MASTER"
					}
					"""))
			.andExpect(status().isOk());
	}
}
