package com.easysubway.usage.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password",
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("관리자 사용자 활동 요약 API")
class UserActivityAdminApiControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@TestConfiguration
	static class FixedClockConfiguration {

		@Bean
		Clock userActivityApiTestClock() {
			return Clock.fixed(Instant.parse("2026-06-17T00:00:00Z"), ZoneId.of("Asia/Seoul"));
		}
	}

	@Test
	@DisplayName("관리자는 사용자 활동 요약을 JSON으로 조회한다")
	void adminGetsUserActivitySummary() throws Exception {
		mockMvc.perform(get("/api/v1/me/favorites/stations")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isOk());

		var result = mockMvc.perform(get("/admin/usage/activity/summary")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.totalActiveUsers").value(1))
			.andExpect(jsonPath("$.data.totalApiRequests").value(1))
			.andExpect(jsonPath("$.data.totalApiErrors").value(0))
			.andExpect(jsonPath("$.data.apiErrorRatePercent").value("0.0%"))
			.andExpect(jsonPath("$.data.apiErrorAlertLabel").value("정상"))
			.andExpect(jsonPath("$.data.apiErrorAlertDescription").value("최근 7일 API 오류율이 기준치 미만입니다."))
			.andExpect(jsonPath("$.data.apiErrorAlertClass").value("ok"))
			.andExpect(jsonPath("$.data.averageApiResponseMillis").isNumber())
			.andExpect(jsonPath("$.data.averageApiResponseTimeLabel").exists())
			.andExpect(jsonPath("$.data.dailyActivityRows[0].dateLabel").value("2026-06-17"))
			.andExpect(jsonPath("$.data.dailyActivityRows[0].activeUserCount").value(1))
			.andExpect(jsonPath("$.data.dailyActivityRows[0].apiRequestCount").value(1))
			.andExpect(jsonPath("$.data.dailyActivityRows[0].apiErrorCount").value(0))
			.andExpect(jsonPath("$.data.dailyActivityRows[0].apiErrorRatePercent").value("0.0%"))
			.andExpect(jsonPath("$.data.dailyActivityRows[0].averageApiResponseMillis").isNumber())
			.andReturn();

		String json = result.getResponse().getContentAsString();
		assertThat(json)
			.doesNotContain("basic-user")
			.doesNotContain("anonymous-user");
	}

	@Test
	@DisplayName("사용자 활동 요약 API는 관리자 인증을 요구한다")
	void userActivitySummaryRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/usage/activity/summary"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/usage/activity/summary")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());
	}
}
