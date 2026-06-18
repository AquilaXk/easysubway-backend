package com.easysubway.operator.adapter.in.web;

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
	"easysubway.operator.username=operator-user",
	"easysubway.operator.password=operator-test-password",
	"easysubway.user.username=anonymous-user-1",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("운영기관 알림 발송 현황 API")
class OperatorPushNotificationReportControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("운영기관 계정은 알림 발송 현황과 최근 실패 사유를 JSON으로 조회한다")
	void operatorGetsPushNotificationReport() throws Exception {
		registerAndroidDevice();
		dispatchReportStatusNotification();
		deliverPendingNotifications();
		dispatchReportStatusNotification();

		mockMvc.perform(get("/operator/api/push-notification-report")
				.with(httpBasic("operator-user", "operator-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.totalCount").value(2))
			.andExpect(jsonPath("$.data.pendingCount").value(1))
			.andExpect(jsonPath("$.data.sentCount").value(0))
			.andExpect(jsonPath("$.data.failedCount").value(1))
			.andExpect(jsonPath("$.data.latestFailureReason")
				.value("외부 푸시 발송 어댑터가 설정되지 않았습니다."))
			.andExpect(jsonPath("$.data.statusRows[0].label").value("대기 중"))
			.andExpect(jsonPath("$.data.statusRows[0].description").value("아직 발송 처리 전"))
			.andExpect(jsonPath("$.data.statusRows[0].count").value(1))
			.andExpect(jsonPath("$.data.statusRows[1].label").value("발송 완료"))
			.andExpect(jsonPath("$.data.statusRows[1].count").value(0))
			.andExpect(jsonPath("$.data.statusRows[2].label").value("발송 실패"))
			.andExpect(jsonPath("$.data.statusRows[2].description")
				.value("발송 어댑터 실패 또는 예외 · 최근 실패: 외부 푸시 발송 어댑터가 설정되지 않았습니다."))
			.andExpect(jsonPath("$.data.statusRows[2].count").value(1))
			.andExpect(jsonPath("$.data.notificationId").doesNotExist())
			.andExpect(jsonPath("$.data.userId").doesNotExist())
			.andExpect(jsonPath("$.data.deviceToken").doesNotExist());
	}

	@Test
	@DisplayName("운영기관 알림 발송 현황 API는 운영기관 계정 인증을 요구한다")
	void pushNotificationReportRequiresOperatorAuthentication() throws Exception {
		mockMvc.perform(get("/operator/api/push-notification-report"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/operator/api/push-notification-report")
				.with(httpBasic("anonymous-user-1", "user-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/operator/api/push-notification-report")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isForbidden());
	}

	private void registerAndroidDevice() throws Exception {
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

	private void dispatchReportStatusNotification() throws Exception {
		mockMvc.perform(post("/admin/notifications/push")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-1",
					  "type": "REPORT_STATUS",
					  "title": "신고 처리 알림",
					  "body": "제보한 내용이 확인되었습니다."
					}
					"""))
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
