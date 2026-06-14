package com.easysubway.collection.adapter.in.web;

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
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password",
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DisplayName("데이터 수집 배치 API")
class DataCollectionControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("관리자는 도시철도 마스터 데이터 수집 배치를 실행하고 기록을 조회한다")
	void adminRunsTransitMasterCollectionAndListsRuns() throws Exception {
		mockMvc.perform(post("/admin/data-collections/runs")
				.with(httpBasic("admin-user", "admin-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "source": "TRANSIT_MASTER"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.runId").isNotEmpty())
			.andExpect(jsonPath("$.data.source").value("TRANSIT_MASTER"))
			.andExpect(jsonPath("$.data.status").value("COMPLETED"))
			.andExpect(jsonPath("$.data.requestedBy").value("admin-user"))
			.andExpect(jsonPath("$.data.collectedCount").value(13));

		mockMvc.perform(get("/admin/data-collections/runs")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].source").value("TRANSIT_MASTER"))
			.andExpect(jsonPath("$.data[0].status").value("COMPLETED"))
			.andExpect(jsonPath("$.data[0].requestedBy").value("admin-user"));
	}

	@Test
	@DisplayName("데이터 수집 배치 API는 관리자만 사용할 수 있다")
	void dataCollectionApisRequireAdminAuthentication() throws Exception {
		mockMvc.perform(post("/admin/data-collections/runs")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "source": "TRANSIT_MASTER"
					}
					"""))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/data-collections/runs"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/admin/data-collections/runs")
				.with(httpBasic("basic-user", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "source": "TRANSIT_MASTER"
					}
					"""))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/admin/data-collections/runs")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("데이터 수집 배치 실행 요청은 수집 대상을 요구한다")
	void dataCollectionRunRequestRequiresSource() throws Exception {
		mockMvc.perform(post("/admin/data-collections/runs")
				.with(httpBasic("admin-user", "admin-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").value("수집 대상을 선택해야 합니다."));
	}
}
