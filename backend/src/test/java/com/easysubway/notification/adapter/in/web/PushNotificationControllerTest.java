package com.easysubway.notification.adapter.in.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
@DisplayName("푸시 알림 발송 API")
class PushNotificationControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private NotificationPreferenceUseCase notificationPreferenceUseCase;

	@Test
	@DisplayName("관리자는 사용자 기기에 푸시 알림 발송 후보를 만들고 토큰은 응답하지 않는다")
	void adminDispatchesPushNotificationWithoutExposingDeviceToken() throws Exception {
		registerDevice();

		mockMvc.perform(post("/admin/notifications/push")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-1",
					  "type": "FAVORITE_STATION_FACILITY",
					  "title": "엘리베이터 운행 변경",
					  "body": "상록수역 엘리베이터 상태를 확인하세요."
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.requestedUserId").value("anonymous-user-1"))
			.andExpect(jsonPath("$.data.type").value("FAVORITE_STATION_FACILITY"))
			.andExpect(jsonPath("$.data.createdCount").value(1))
			.andExpect(jsonPath("$.data.notifications[0].notificationId").isNotEmpty())
			.andExpect(jsonPath("$.data.notifications[0].platform").value("ANDROID"))
			.andExpect(jsonPath("$.data.notifications[0].status").value("PENDING"))
			.andExpect(jsonPath("$.data.notifications[0].deviceToken").doesNotExist());
	}

	@Test
	@DisplayName("푸시 알림 발송 API는 관리자만 사용할 수 있다")
	void pushNotificationDispatchRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(post("/admin/notifications/push")
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
			.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("푸시 알림 발송 요청은 필수 값을 Bean Validation으로 검증한다")
	void pushNotificationDispatchRequiresValidRequestFields() throws Exception {
		registerDevice();

		mockMvc.perform(post("/admin/notifications/push")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": " ",
					  "type": "REPORT_STATUS",
					  "title": "신고 처리 알림",
					  "body": "제보한 내용이 확인되었습니다."
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("사용자 식별자가 필요합니다."));

		mockMvc.perform(post("/admin/notifications/push")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-1",
					  "title": "신고 처리 알림",
					  "body": "제보한 내용이 확인되었습니다."
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("알림 종류를 선택해야 합니다."));

		mockMvc.perform(post("/admin/notifications/push/deliveries")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-1"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.processedCount").value(0));
	}

	@Test
	@DisplayName("푸시 알림 발송 요청의 알 수 없는 enum은 요청 본문 오류로 응답한다")
	void pushNotificationDispatchRejectsUnknownTypeAsUnreadableBody() throws Exception {
		mockMvc.perform(post("/admin/notifications/push")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-1",
					  "type": "UNKNOWN",
					  "title": "신고 처리 알림",
					  "body": "제보한 내용이 확인되었습니다."
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("요청 본문을 확인해야 합니다."));
	}

	@Test
	@DisplayName("관리자는 대기 중인 푸시 알림 발송 처리를 실행할 수 있다")
	void adminDeliversPendingPushNotifications() throws Exception {
		registerDevice();

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

		mockMvc.perform(post("/admin/notifications/push/deliveries")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-1"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.requestedUserId").value("anonymous-user-1"))
			.andExpect(jsonPath("$.data.processedCount").value(1))
			.andExpect(jsonPath("$.data.sentCount").value(0))
			.andExpect(jsonPath("$.data.failedCount").value(1))
			.andExpect(jsonPath("$.data.notifications[0].status").value("FAILED"))
			.andExpect(jsonPath("$.data.notifications[0].deviceToken").doesNotExist());
	}

	@Test
	@DisplayName("푸시 알림 발송 처리 요청은 사용자 식별자를 요구한다")
	void pushNotificationDeliveryRequiresUserId() throws Exception {
		mockMvc.perform(post("/admin/notifications/push/deliveries")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": " "
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("사용자 식별자가 필요합니다."));
	}

	private void registerDevice() {
		notificationPreferenceUseCase.registerDevice(new RegisterDeviceCommand(
			"anonymous-user-1",
			DevicePlatform.ANDROID,
			"secret-device-token"
		));
	}
}
