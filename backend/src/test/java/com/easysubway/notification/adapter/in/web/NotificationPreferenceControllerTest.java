package com.easysubway.notification.adapter.in.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
	"easysubway.user.username=anonymous-user-1",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DisplayName("알림 설정 API")
class NotificationPreferenceControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("인증 사용자 기준으로 기기 토큰을 등록한다")
	void registerDeviceUsesAuthenticatedUser() throws Exception {
		mockMvc.perform(post("/api/v1/devices")
				.with(httpBasic("anonymous-user-1", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "spoofed-user",
					  "platform": "ANDROID",
					  "deviceToken": "device-token-1"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.userId").value("anonymous-user-1"))
			.andExpect(jsonPath("$.data.platform").value("ANDROID"))
			.andExpect(jsonPath("$.data.deviceToken").value("device-token-1"))
			.andExpect(jsonPath("$.data.registeredAt").isNotEmpty());
	}

	@Test
	@DisplayName("인증 사용자 기준으로 알림 설정을 저장하고 조회한다")
	void notificationSettingsCanBeSavedAndRead() throws Exception {
		mockMvc.perform(put("/api/v1/me/notification-settings")
				.with(httpBasic("anonymous-user-1", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "spoofed-user",
					  "favoriteStationFacilityAlerts": true,
					  "favoriteRouteFacilityAlerts": false,
					  "reportStatusAlerts": true,
					  "dataQualityAlerts": true
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.userId").value("anonymous-user-1"))
			.andExpect(jsonPath("$.data.favoriteStationFacilityAlerts").value(true))
			.andExpect(jsonPath("$.data.favoriteRouteFacilityAlerts").value(false))
			.andExpect(jsonPath("$.data.reportStatusAlerts").value(true))
			.andExpect(jsonPath("$.data.dataQualityAlerts").value(true));

		mockMvc.perform(get("/api/v1/me/notification-settings")
				.with(httpBasic("anonymous-user-1", "user-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.favoriteRouteFacilityAlerts").value(false))
			.andExpect(jsonPath("$.data.dataQualityAlerts").value(true));
	}

	@Test
	@DisplayName("알림 API는 인증된 사용자만 사용할 수 있다")
	void notificationApisRequireAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/me/notification-settings"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/api/v1/devices")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "platform": "IOS",
					  "deviceToken": "device-token-1"
					}
					"""))
			.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("알 수 없는 기기 플랫폼은 공통 400 응답으로 거부한다")
	void registerDeviceRejectsUnknownPlatform() throws Exception {
		mockMvc.perform(post("/api/v1/devices")
				.with(httpBasic("anonymous-user-1", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "platform": "WATCH",
					  "deviceToken": "device-token-1"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("요청 본문을 확인해야 합니다."));
	}

	@Test
	@DisplayName("빈 기기 토큰은 공통 400 응답으로 거부한다")
	void registerDeviceRejectsBlankToken() throws Exception {
		mockMvc.perform(post("/api/v1/devices")
				.with(httpBasic("anonymous-user-1", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "platform": "IOS",
					  "deviceToken": ""
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("기기 토큰이 필요합니다."));
	}
}
