package com.easysubway.realtime.adapter.in.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"spring.profiles.active=test",
	"spring.flyway.enabled=false",
	"spring.datasource.url=jdbc:h2:mem:realtime-controller;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"EASYSUBWAY_SEOUL_TOPIS_FIXTURE_ENABLED=true"
})
@AutoConfigureMockMvc
@DisplayName("서울 실시간 gateway API")
class RealtimeControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("실시간 도착은 공개 API에서 normalized fresh 응답으로 반환된다")
	void arrivalsReturnNormalizedFreshItems() throws Exception {
		mockMvc.perform(get("/api/v1/realtime/arrivals")
				.param("stationId", "station-sangnoksu")
				.param("lineId", "seoul-4")
				.param("providerLineId", "1004")
				.param("stationQueryName", "상록수"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.status").value("FRESH"))
			.andExpect(jsonPath("$.data.arrivals[0].stationName").value("상록수"))
			.andExpect(jsonPath("$.data.arrivals[0].etaSeconds").value(180))
			.andExpect(jsonPath("$.data.arrivals[0].message").value("3분 후"));
	}

	@Test
	@DisplayName("지원하지 않는 역은 정적 기능을 깨지 않는 unsupported 응답으로 반환된다")
	void unsupportedStationDoesNotFailRequest() throws Exception {
		mockMvc.perform(get("/api/v1/realtime/arrivals")
				.param("stationId", "station-outside")
				.param("lineId", "other")
				.param("stationQueryName", "외부역"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("UNSUPPORTED"))
			.andExpect(jsonPath("$.data.fallbackCode").value("MAPPING_MISSING"))
			.andExpect(jsonPath("$.data.message").value("서울 TOPIS 실시간 지원 범위 밖입니다."));
	}

	@Test
	@DisplayName("공개 실시간 API는 provider credential query parameter를 받지 않는다")
	void realtimePublicApiRejectsProviderCredentialParameters() throws Exception {
		mockMvc.perform(get("/api/v1/realtime/arrivals")
				.param("stationId", "station-sangnoksu")
				.param("lineId", "seoul-4")
				.param("stationQueryName", "상록수")
				.param("serviceKey", "raw-provider-secret"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").value("실시간 provider credential은 앱/API 요청에 포함할 수 없습니다."))
			.andExpect(content().string(not(containsString("raw-provider-secret"))))
			.andExpect(content().string(not(containsString("상록수"))));
	}

	@Test
	@DisplayName("열차 위치 API도 provider URL proxy parameter를 거부한다")
	void trainPositionsRejectProviderUrlProxyParameter() throws Exception {
		mockMvc.perform(get("/api/v1/realtime/train-positions")
				.param("lineId", "seoul-4")
				.param("lineName", "4호선")
				.param("providerUrl", "http://swopenapi.seoul.go.kr/api/subway/raw-key/json/realtimePosition/0/10/4호선"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(content().string(not(containsString("raw-key"))))
			.andExpect(content().string(not(containsString("swopenapi.seoul.go.kr"))))
			.andExpect(content().string(not(containsString("4호선"))));
	}

	@Test
	@DisplayName("열차 위치는 GPS가 아닌 운행 정보 snapshot 안내를 포함한다")
	void trainPositionsIncludeOperationSnapshotNotice() throws Exception {
		mockMvc.perform(get("/api/v1/realtime/train-positions")
				.param("lineId", "seoul-4")
				.param("providerLineId", "1004")
				.param("lineName", "4호선"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("FRESH"))
			.andExpect(jsonPath("$.data.trainPositions[0].stationName").value("상록수"))
			.andExpect(jsonPath("$.data.sourceNotice").value("열차 위치는 GPS가 아니라 운행 정보 기준 위치입니다."));
	}
}
