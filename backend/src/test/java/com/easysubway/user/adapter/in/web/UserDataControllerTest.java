package com.easysubway.user.adapter.in.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
	"easysubway.user.username=configured-user",
	"easysubway.user.password=configured-password"
})
@AutoConfigureMockMvc
@DisplayName("사용자 데이터 삭제 API")
class UserDataControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("인증 사용자는 연결된 데이터를 삭제하고 신고 기록은 익명화한다")
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	void deleteCurrentUserDataClearsLinkedDataAndAnonymizesReports() throws Exception {
		saveLinkedUserData();
		String reportId = createReportWithPersonalData();
		dispatchPushNotification();

		mockMvc.perform(delete("/api/v1/me")
				.with(httpBasic("configured-user", "configured-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.userId").value("configured-user"))
			.andExpect(jsonPath("$.data.deletedFavoriteStationCount").value(1))
			.andExpect(jsonPath("$.data.deletedFavoriteFacilityCount").value(1))
			.andExpect(jsonPath("$.data.deletedFavoriteRouteCount").value(1))
			.andExpect(jsonPath("$.data.anonymizedRouteFeedbackCount").value(1))
			.andExpect(jsonPath("$.data.notificationSettingsDeleted").value(true))
			.andExpect(jsonPath("$.data.deletedRegisteredDeviceCount").value(1))
			.andExpect(jsonPath("$.data.deletedPushNotificationCount").value(1))
			.andExpect(jsonPath("$.data.mobilityProfileDeleted").value(true))
			.andExpect(jsonPath("$.data.anonymizedReportCount").value(1))
			.andExpect(jsonPath("$.data.anonymousCredentialsDeleted").value(false));

		mockMvc.perform(get("/api/v1/me/favorites/stations")
				.with(httpBasic("configured-user", "configured-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").isEmpty());
		mockMvc.perform(get("/api/v1/me/favorites/facilities")
				.with(httpBasic("configured-user", "configured-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").isEmpty());
		mockMvc.perform(get("/api/v1/me/favorites/routes")
				.with(httpBasic("configured-user", "configured-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").isEmpty());
		mockMvc.perform(get("/api/v1/me/reports")
				.with(httpBasic("configured-user", "configured-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").isEmpty());
		mockMvc.perform(get("/api/v1/me/mobility-profile")
				.param("userId", "configured-user"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.mobilityType").value("SENIOR"));
		mockMvc.perform(get("/admin/reports/{reportId}", reportId)
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.userId").value("__easysubway_deleted_facility_report__"))
			.andExpect(jsonPath("$.data.description").value("사용자 데이터 삭제로 신고 내용이 삭제되었습니다."))
			.andExpect(jsonPath("$.data.photoFileName").doesNotExist())
			.andExpect(jsonPath("$.data.photoDataBase64").doesNotExist())
			.andExpect(jsonPath("$.data.latitude").doesNotExist())
			.andExpect(jsonPath("$.data.longitude").doesNotExist());
	}

	@Test
	@DisplayName("사용자 데이터 삭제 API는 인증을 요구한다")
	void deleteCurrentUserDataRequiresAuthentication() throws Exception {
		mockMvc.perform(delete("/api/v1/me"))
			.andExpect(status().isUnauthorized());
	}

	private void saveLinkedUserData() throws Exception {
		mockMvc.perform(put("/api/v1/me/favorites/stations/station-sangnoksu")
				.with(httpBasic("configured-user", "configured-password")))
			.andExpect(status().isOk());
		mockMvc.perform(put("/api/v1/me/favorites/facilities/facility-sangnoksu-elevator-1")
				.with(httpBasic("configured-user", "configured-password")))
			.andExpect(status().isOk());
		String routeSearchId = createRouteSearch();
		mockMvc.perform(post("/api/v1/me/favorites/routes")
				.with(httpBasic("configured-user", "configured-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "routeSearchId": "%s"
					}
					""".formatted(routeSearchId)))
			.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/routes/{routeSearchId}/feedback", routeSearchId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "configured-user",
					  "rating": "NOT_HELPFUL",
					  "comment": "전화번호 010-1111-2222가 포함된 의견입니다."
					}
					"""))
			.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/devices")
				.with(httpBasic("configured-user", "configured-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "platform": "ANDROID",
					  "deviceToken": "device-token-user-delete"
					}
					"""))
			.andExpect(status().isOk());
		mockMvc.perform(put("/api/v1/me/notification-settings")
				.with(httpBasic("configured-user", "configured-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "favoriteStationFacilityAlerts": false,
					  "favoriteRouteFacilityAlerts": false,
					  "reportStatusAlerts": true,
					  "dataQualityAlerts": true
					}
					"""))
			.andExpect(status().isOk());
		mockMvc.perform(put("/api/v1/me/mobility-profile")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "configured-user",
					  "mobilityType": "PREGNANT",
					  "avoidStairs": true,
					  "requireElevator": false,
					  "allowEscalator": true,
					  "minimizeTransfers": true,
					  "avoidLongWalks": true,
					  "largeText": true,
					  "highContrast": false,
					  "simpleView": true
					}
					"""))
			.andExpect(status().isOk());
	}

	private String createRouteSearch() throws Exception {
		var result = mockMvc.perform(post("/api/v1/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "mobilityType": "SENIOR"
					}
					"""))
			.andExpect(status().isOk())
			.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.data.routeSearchId");
	}

	private String createReportWithPersonalData() throws Exception {
		var result = mockMvc.perform(post("/api/v1/reports")
				.with(httpBasic("configured-user", "configured-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "전화번호 010-0000-0000이 사진에 보입니다.",
					  "photoFileName": "personal-photo.jpg",
					  "photoContentType": "image/jpeg",
					  "photoDataBase64": "cGVyc29uYWw=",
					  "latitude": 37.302421,
					  "longitude": 126.866221
					}
					"""))
			.andExpect(status().isCreated())
			.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
	}

	private void dispatchPushNotification() throws Exception {
		mockMvc.perform(post("/admin/notifications/push")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "configured-user",
					  "type": "REPORT_STATUS",
					  "title": "신고 처리",
					  "body": "처리 상태가 바뀌었습니다."
					}
					"""))
			.andExpect(status().isOk());
	}
}
