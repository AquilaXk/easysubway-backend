package com.easysubway.notification.adapter.in.web;

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
	"easysubway.user.username=anonymous-user-1",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("관리자 푸시 알림 현황 페이지")
class PushNotificationAdminPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private NotificationPreferenceUseCase notificationPreferenceUseCase;

	@Test
	@DisplayName("관리자는 푸시 알림 outbox의 전체와 상태별 건수를 확인한다")
	void adminGetsPushNotificationDashboardPage() throws Exception {
		registerDevice();
		dispatchNotification("REPORT_STATUS", "신고 처리 알림");
		deliverPendingNotifications();
		dispatchNotification("FAVORITE_STATION_FACILITY", "시설 변경 알림");

		String html = mockMvc.perform(get("/admin/notifications/push/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("푸시 알림 현황")
			.contains("전체 알림")
			.contains(">2<")
			.contains("대기 중")
			.contains("발송 완료")
			.contains("발송 실패")
			.contains("발송 시도")
			.contains("발송 성공률")
			.contains("발송 실패율")
			.contains("0.0%")
			.contains("100.0%")
			.contains("점검 필요")
			.contains("아직 발송 처리 전")
			.contains("외부 발송 성공")
			.contains("발송 어댑터 실패 또는 예외")
			.contains("최근 실패: 외부 푸시 발송 어댑터가 설정되지 않았습니다.")
			.doesNotContain("secret-device-token");
	}

	@Test
	@DisplayName("푸시 알림 현황 페이지는 관리자 인증을 요구한다")
	void pushNotificationDashboardRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/notifications/push/page"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/notifications/push/page")
				.with(httpBasic("anonymous-user-1", "user-test-password")))
			.andExpect(status().isForbidden());
	}

	private void registerDevice() {
		notificationPreferenceUseCase.registerDevice(new RegisterDeviceCommand(
			"anonymous-user-1",
			DevicePlatform.ANDROID,
			"secret-device-token"
		));
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
