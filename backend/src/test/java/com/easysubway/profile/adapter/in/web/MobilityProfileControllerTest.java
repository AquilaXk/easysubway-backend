package com.easysubway.profile.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class MobilityProfileControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void getProfileReturnsDefaultProfileForAnonymousUser() throws Exception {
		mockMvc.perform(get("/api/v1/me/mobility-profile")
				.param("userId", "anonymous-user-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.userId").value("anonymous-user-1"))
			.andExpect(jsonPath("$.data.mobilityType").value("SENIOR"))
			.andExpect(jsonPath("$.data.avoidStairs").value(true))
			.andExpect(jsonPath("$.data.requireElevator").value(false))
			.andExpect(jsonPath("$.data.allowEscalator").value(true))
			.andExpect(jsonPath("$.data.minimizeTransfers").value(true))
			.andExpect(jsonPath("$.data.avoidLongWalks").value(true))
			.andExpect(jsonPath("$.data.largeText").value(false))
			.andExpect(jsonPath("$.data.highContrast").value(false))
			.andExpect(jsonPath("$.data.simpleView").value(false));
	}

	@Test
	void putProfileStoresAndReturnsMobilityProfile() throws Exception {
		mockMvc.perform(put("/api/v1/me/mobility-profile")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-2",
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
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.userId").value("anonymous-user-2"))
			.andExpect(jsonPath("$.data.mobilityType").value("PREGNANT"))
			.andExpect(jsonPath("$.data.largeText").value(true))
			.andExpect(jsonPath("$.data.simpleView").value(true));

		mockMvc.perform(get("/api/v1/me/mobility-profile")
				.param("userId", "anonymous-user-2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.mobilityType").value("PREGNANT"))
			.andExpect(jsonPath("$.data.largeText").value(true))
			.andExpect(jsonPath("$.data.simpleView").value(true));
	}

	@Test
	void putProfileRejectsWheelchairProfileThatAllowsStairs() throws Exception {
		mockMvc.perform(put("/api/v1/me/mobility-profile")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-3",
					  "mobilityType": "WHEELCHAIR",
					  "avoidStairs": false,
					  "requireElevator": true,
					  "allowEscalator": false,
					  "minimizeTransfers": true,
					  "avoidLongWalks": true,
					  "largeText": false,
					  "highContrast": false,
					  "simpleView": true
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("휠체어 프로필은 계단 없는 경로만 저장할 수 있습니다."));
	}

	@Test
	void putProfileReturnsCommonErrorForInvalidMobilityType() throws Exception {
		mockMvc.perform(put("/api/v1/me/mobility-profile")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-4",
					  "mobilityType": "UNKNOWN",
					  "avoidStairs": true,
					  "requireElevator": false,
					  "allowEscalator": true,
					  "minimizeTransfers": true,
					  "avoidLongWalks": true,
					  "largeText": false,
					  "highContrast": false,
					  "simpleView": false
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("요청 본문을 확인해야 합니다."));
	}

	@Test
	void getProfileRequiresUserId() throws Exception {
		mockMvc.perform(get("/api/v1/me/mobility-profile"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("사용자 식별자가 필요합니다."));
	}
}
