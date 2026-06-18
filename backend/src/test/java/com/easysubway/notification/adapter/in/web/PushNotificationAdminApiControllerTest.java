package com.easysubway.notification.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
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
@DisplayName("관리자 푸시 알림 요약 API")
class PushNotificationAdminApiControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("관리자는 푸시 알림 outbox 요약을 JSON으로 조회한다")
	void adminGetsPushNotificationSummary() throws Exception {
		registerDevice();
		dispatchNotification("REPORT_STATUS", "신고 처리 알림");
		deliverPendingNotifications();
		dispatchNotification("FAVORITE_STATION_FACILITY", "시설 변경 알림");

		var result = mockMvc.perform(get("/admin/notifications/push/summary")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.totalCount").value(2))
			.andExpect(jsonPath("$.data.pendingCount").value(1))
			.andExpect(jsonPath("$.data.sentCount").value(0))
			.andExpect(jsonPath("$.data.failedCount").value(1))
			.andExpect(jsonPath("$.data.deliveryAttemptCount").value(1))
			.andExpect(jsonPath("$.data.successRateLabel").value("0.0%"))
			.andExpect(jsonPath("$.data.failureRateLabel").value("100.0%"))
			.andExpect(jsonPath("$.data.failureAlertLabel").value("점검 필요"))
			.andExpect(jsonPath("$.data.failureAlertClass").value("failure"))
			.andExpect(jsonPath("$.data.latestFailureReason").value("외부 푸시 발송 어댑터가 설정되지 않았습니다."))
			.andExpect(jsonPath("$.data.statusRows[0].label").value("대기 중"))
			.andExpect(jsonPath("$.data.statusRows[0].count").value(1))
			.andExpect(jsonPath("$.data.statusRows[1].label").value("발송 완료"))
			.andExpect(jsonPath("$.data.statusRows[1].count").value(0))
			.andExpect(jsonPath("$.data.statusRows[2].label").value("발송 실패"))
			.andExpect(jsonPath("$.data.statusRows[2].count").value(1))
			.andReturn();

		String json = result.getResponse().getContentAsString();
		assertThat(json)
			.doesNotContain("secret-device-token")
			.doesNotContain("anonymous-user-1");
	}

	@Test
	@DisplayName("푸시 알림 요약 API는 관리자 인증을 요구한다")
	void pushNotificationSummaryRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/notifications/push/summary"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/notifications/push/summary")
				.with(httpBasic("anonymous-user-1", "user-test-password")))
			.andExpect(status().isForbidden());
	}

	private void registerDevice() throws Exception {
		mockMvc.perform(post("/api/v1/devices")
				.with(httpBasic("anonymous-user-1", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "platform": "ANDROID",
					  "deviceToken": "secret-device-token"
					}
					"""))
			.andExpect(status().isOk());
	}

	private void dispatchNotification(String type, String title) throws Exception {
		mockMvc.perform(post("/admin/notifications/push")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-1",
					  "type": "%s",
					  "title": "%s",
					  "body": "상록수역 시설 상태를 확인하세요."
					}
					""".formatted(type, title)))
			.andExpect(status().isOk());
	}

	private void deliverPendingNotifications() throws Exception {
		mockMvc.perform(post("/admin/notifications/push/deliveries")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-1"
					}
					"""))
			.andExpect(status().isOk());
	}
}
