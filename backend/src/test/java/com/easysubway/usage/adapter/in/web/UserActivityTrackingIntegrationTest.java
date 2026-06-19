package com.easysubway.usage.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password",
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DisplayName("사용자 활동 기록 통합")
class UserActivityTrackingIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@TestConfiguration
	static class FixedClockConfiguration {

		@Bean
		Clock userActivityTestClock() {
			return Clock.fixed(Instant.parse("2026-06-17T00:00:00Z"), ZoneId.of("Asia/Seoul"));
		}
	}

	@Test
	@DisplayName("인증 사용자 API 요청은 관리자 활동 현황에 일별 고유 사용자로 반영된다")
	void authenticatedApiRequestAppearsOnAdminActivityDashboard() throws Exception {
		mockMvc.perform(get("/api/v1/me/favorites/stations")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isOk());

		String html = mockMvc.perform(get("/admin/usage/activity/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("사용자 활동 현황")
			.contains("최근 7일 활성 사용자")
			.contains("일별 활성 사용자")
			.contains("2026-06-17")
			.contains(">1<")
			.doesNotContain("basic-user");
	}
}
