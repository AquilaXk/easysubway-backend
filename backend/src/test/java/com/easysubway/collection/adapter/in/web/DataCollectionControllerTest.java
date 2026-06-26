package com.easysubway.collection.adapter.in.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
	@DisplayName("관리자는 수집 가능한 데이터 소스 목록을 조회한다")
	void adminListsDataSources() throws Exception {
		mockMvc.perform(get("/admin/data-sources")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value("TRANSIT_MASTER"))
			.andExpect(jsonPath("$.data[0].label").value("도시철도 마스터"))
			.andExpect(jsonPath("$.data[0].description").value("운영기관, 노선, 역, 출구, 접근성 시설 기준 데이터"))
			.andExpect(jsonPath("$.data[0].syncPath").value("/admin/data-sources/TRANSIT_MASTER/sync"));
	}

	@Test
	@DisplayName("관리자는 도시철도 마스터 데이터 수집 배치를 실행하고 기록을 조회한다")
	void adminRunsTransitMasterCollectionAndListsRuns() throws Exception {
		mockMvc.perform(post("/admin/data-collections/runs")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
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
			.andExpect(jsonPath("$.data.collectedCount").value(14))
			.andExpect(jsonPath("$.data.retryable").value(false))
			.andExpect(jsonPath("$.data.steps[0].name").value("FETCH"))
			.andExpect(jsonPath("$.data.steps[0].status").value("COMPLETED"))
			.andExpect(jsonPath("$.data.steps[1].name").value("ARCHIVE"))
			.andExpect(jsonPath("$.data.steps[1].status").value("SKIPPED"))
			.andExpect(jsonPath("$.data.steps[5].name").value("STAGE"))
			.andExpect(jsonPath("$.data.steps[5].status").value("SKIPPED"))
			.andExpect(jsonPath("$.data.steps[6].status").value("MANUAL_REQUIRED"))
			.andExpect(jsonPath("$.data.operatorAction")
				.value("수집이 완료되었습니다. 최근 데이터 품질 화면에서 반영 결과를 확인하세요."));

		mockMvc.perform(get("/admin/data-collections/runs")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].source").value("TRANSIT_MASTER"))
			.andExpect(jsonPath("$.data[0].status").value("COMPLETED"))
			.andExpect(jsonPath("$.data[0].requestedBy").value("admin-user"))
			.andExpect(jsonPath("$.data[0].retryable").value(false))
			.andExpect(jsonPath("$.data[0].steps[0].name").value("FETCH"))
			.andExpect(jsonPath("$.data[0].steps[1].status").value("SKIPPED"))
			.andExpect(jsonPath("$.data[0].operatorAction")
				.value("수집이 완료되었습니다. 최근 데이터 품질 화면에서 반영 결과를 확인하세요."));
	}

	@Test
	@DisplayName("관리자는 데이터 소스 식별자로 동기화를 실행한다")
	void adminSyncsDataSourceById() throws Exception {
		mockMvc.perform(post("/admin/data-sources/TRANSIT_MASTER/sync")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.runId").isNotEmpty())
			.andExpect(jsonPath("$.data.source").value("TRANSIT_MASTER"))
			.andExpect(jsonPath("$.data.status").value("COMPLETED"))
			.andExpect(jsonPath("$.data.requestedBy").value("admin-user"));
	}

	@Test
	@DisplayName("데이터 수집 배치 API는 관리자만 사용할 수 있다")
	void dataCollectionApisRequireAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/data-sources"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/admin/data-sources/TRANSIT_MASTER/sync")
				.with(csrf()))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/admin/data-collections/runs")
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "source": "TRANSIT_MASTER"
					}
					"""))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/data-collections/runs"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/data-sources")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/admin/data-sources/TRANSIT_MASTER/sync")
				.with(httpBasic("basic-user", "user-test-password"))
				.with(csrf()))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/admin/data-collections/runs")
				.with(httpBasic("basic-user", "user-test-password"))
				.with(csrf())
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
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").value("수집 대상을 선택해야 합니다."));
	}

	@Test
	@DisplayName("알 수 없는 데이터 소스 동기화 요청은 공통 오류 응답을 반환한다")
	void unknownDataSourceSyncReturnsCommonError() throws Exception {
		mockMvc.perform(post("/admin/data-sources/unknown-source/sync")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("알 수 없는 데이터 소스입니다."));
	}
}
