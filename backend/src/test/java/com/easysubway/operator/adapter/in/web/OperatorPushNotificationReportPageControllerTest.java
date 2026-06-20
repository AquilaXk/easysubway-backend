package com.easysubway.operator.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.notification.application.port.in.NotificationPreferenceUseCase;
import com.easysubway.notification.application.port.in.RegisterDeviceCommand;
import com.easysubway.notification.domain.DevicePlatform;
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
@DisplayName("운영기관 알림 발송 현황 화면")
class OperatorPushNotificationReportPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private NotificationPreferenceUseCase notificationPreferenceUseCase;

	@Test
	@DisplayName("운영기관 계정은 읽기 전용 알림 발송 현황을 확인한다")
	void operatorGetsPushNotificationReportPage() throws Exception {
		registerAndroidDevice();
		dispatchReportStatusNotification();
		deliverPendingNotifications();
		dispatchReportStatusNotification();

		String html = mockMvc.perform(get("/operator/push-notification-report/page")
				.with(httpBasic("operator-user", "operator-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("운영기관 알림 발송 현황")
			.contains("읽기 전용 리포트")
			.contains("전체 알림")
			.contains("대기 중")
			.contains("발송 완료")
			.contains("발송 실패")
			.contains("상태별 발송 현황")
			.contains("아직 발송 처리 전")
			.contains("외부 발송 성공")
			.contains("푸시 발송 처리 중 오류가 발생했습니다. 관리자 점검이 필요합니다.")
			.doesNotContain("notificationId")
			.doesNotContain("userId")
			.doesNotContain("deviceToken")
			.doesNotContain("secret-device-token")
			.doesNotContain("외부 푸시 발송 어댑터가 설정되지 않았습니다.")
			.doesNotContain("name=\"_csrf\"")
			.doesNotContain("<form")
			.doesNotContain("/admin/notifications");
	}

	@Test
	@DisplayName("운영기관 알림 발송 현황 화면은 운영기관 계정 인증을 요구한다")
	void pushNotificationReportPageRequiresOperatorAuthentication() throws Exception {
		mockMvc.perform(get("/operator/push-notification-report/page"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/operator/push-notification-report/page")
				.with(httpBasic("anonymous-user-1", "user-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/operator/push-notification-report/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isForbidden());
	}

	private void registerAndroidDevice() {
		notificationPreferenceUseCase.registerDevice(new RegisterDeviceCommand(
			"anonymous-user-1",
			DevicePlatform.ANDROID,
			"secret-device-token"
		));
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
