package com.easysubway.realtime.adapter.in.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.realtime.application.RealtimeProviderControl;
import org.junit.jupiter.api.AfterEach;
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
@DisplayName("관리자 실시간 provider health summary API")
class RealtimeProviderAdminControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private RealtimeProviderControl providerControl;

	@AfterEach
	void resetProviderControl() {
		providerControl.enableProvider("seoul-topis");
	}

	@Test
	@DisplayName("관리자는 실시간 provider health summary를 조회한다")
	void adminGetsRealtimeProviderHealthSummary() throws Exception {
		mockMvc.perform(get("/admin/realtime/providers/health")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.providerId").value("seoul-topis"))
			.andExpect(jsonPath("$.data.providerEnabled").value(true))
			.andExpect(jsonPath("$.data.providerCallCount").isNumber())
			.andExpect(jsonPath("$.data.providerTimeoutCount").isNumber())
			.andExpect(jsonPath("$.data.providerQuotaExceededCount").isNumber())
			.andExpect(jsonPath("$.data.providerEmptyResultCount").isNumber())
			.andExpect(jsonPath("$.data.freshResultRatio").isNumber())
			.andExpect(jsonPath("$.data.staleResultRatio").isNumber())
			.andExpect(jsonPath("$.data.unsupportedRatio").isNumber())
			.andExpect(jsonPath("$.data.averageProviderLatencyMs").isNumber())
			.andExpect(content().string(not(containsString("상록수"))))
			.andExpect(content().string(not(containsString("4123"))))
			.andExpect(content().string(not(containsString("1004"))));
	}

	@Test
	@DisplayName("실시간 provider health summary는 관리자만 사용할 수 있다")
	void realtimeProviderHealthSummaryRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/realtime/providers/health"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/realtime/providers/health")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("관리자는 실시간 provider kill switch를 토글한다")
	void adminTogglesRealtimeProviderKillSwitch() throws Exception {
		mockMvc.perform(post("/admin/realtime/providers/seoul-topis/disable")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.param("reason", "MAINTENANCE"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.providerEnabled").value(false))
			.andExpect(jsonPath("$.data.disabledReason").value("MAINTENANCE"));

		mockMvc.perform(get("/admin/realtime/providers/health")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.providerEnabled").value(false))
			.andExpect(jsonPath("$.data.disabledReason").value("MAINTENANCE"));

		mockMvc.perform(post("/admin/realtime/providers/seoul-topis/enable")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.providerEnabled").value(true))
			.andExpect(jsonPath("$.data.disabledReason").doesNotExist());
	}
}
