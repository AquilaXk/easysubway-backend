package com.easysubway.favorite.adapter.in.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
@DisplayName("즐겨찾기 역 API")
class FavoriteStationControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("인증 사용자 기준으로 역을 저장하고 목록 조회와 삭제를 처리한다")
	void favoriteStationsCanBeSavedListedAndRemoved() throws Exception {
		mockMvc.perform(put("/api/v1/me/favorites/stations/station-sangnoksu")
				.with(httpBasic("anonymous-user-1", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "spoofed-user"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.userId").value("anonymous-user-1"))
			.andExpect(jsonPath("$.data.stationId").value("station-sangnoksu"))
			.andExpect(jsonPath("$.data.nameKo").value("상록수"))
			.andExpect(jsonPath("$.data.region").value("수도권"))
			.andExpect(jsonPath("$.data.dataQualityLevel").value("LEVEL_1"))
			.andExpect(jsonPath("$.data.lines[0].name").value("수도권 4호선"))
			.andExpect(jsonPath("$.data.addedAt").isNotEmpty());

		mockMvc.perform(get("/api/v1/me/favorites/stations")
				.with(httpBasic("anonymous-user-1", "user-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].stationId").value("station-sangnoksu"))
			.andExpect(jsonPath("$.data[0].nameKo").value("상록수"));

		mockMvc.perform(delete("/api/v1/me/favorites/stations/station-sangnoksu")
				.with(httpBasic("anonymous-user-1", "user-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));

		mockMvc.perform(get("/api/v1/me/favorites/stations")
				.with(httpBasic("anonymous-user-1", "user-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").isEmpty());
	}

	@Test
	@DisplayName("존재하지 않는 역은 공통 404 응답으로 거부한다")
	void favoriteStationsRejectUnknownStation() throws Exception {
		mockMvc.perform(put("/api/v1/me/favorites/stations/missing-station")
				.with(httpBasic("anonymous-user-1", "user-test-password")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("역 정보를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("즐겨찾기 목록은 인증된 사용자만 조회할 수 있다")
	void favoriteStationsRequireAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/me/favorites/stations"))
			.andExpect(status().isUnauthorized());
	}
}
