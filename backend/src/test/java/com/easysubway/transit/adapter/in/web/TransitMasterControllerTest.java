package com.easysubway.transit.adapter.in.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class TransitMasterControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void operatorsReturnsSeededTransitOperators() throws Exception {
		mockMvc.perform(get("/api/v1/operators"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value("seoul-metro"))
			.andExpect(jsonPath("$.data[0].name").value("서울교통공사"))
			.andExpect(jsonPath("$.data[0].region").value("수도권"))
			.andExpect(jsonPath("$.data[0].dataSourceType").value("OFFICIAL_FILE"))
			.andExpect(jsonPath("$.data[0].active").value(true));
	}

	@Test
	void linesCanBeFilteredByOperator() throws Exception {
		mockMvc.perform(get("/api/v1/lines").param("operatorId", "seoul-metro"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value("seoul-4"))
			.andExpect(jsonPath("$.data[0].operatorId").value("seoul-metro"))
			.andExpect(jsonPath("$.data[0].name").value("수도권 4호선"))
			.andExpect(jsonPath("$.data[0].color").value("#00A5DE"));
	}

	@Test
	void stationsCanBeSearchedByKoreanName() throws Exception {
		mockMvc.perform(get("/api/v1/stations").param("query", "상록수"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value("station-sangnoksu"))
			.andExpect(jsonPath("$.data[0].nameKo").value("상록수"))
			.andExpect(jsonPath("$.data[0].dataQualityLevel").value("LEVEL_1"))
			.andExpect(jsonPath("$.data[0].lines[0].id").value("seoul-4"));
	}

	@Test
	void publicStationSearchIgnoresInvalidBasicAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/stations")
				.param("query", "상록수")
				.with(httpBasic("wrong-admin", "wrong-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value("station-sangnoksu"));
	}

	@Test
	void stationDetailIncludesConnectedLinesAndQuality() throws Exception {
		mockMvc.perform(get("/api/v1/stations/station-sangnoksu"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value("station-sangnoksu"))
			.andExpect(jsonPath("$.data.nameEn").value("Sangnoksu"))
			.andExpect(jsonPath("$.data.latitude").value(37.302795))
			.andExpect(jsonPath("$.data.longitude").value(126.866489))
			.andExpect(jsonPath("$.data.dataQualityLevel").value("LEVEL_1"))
			.andExpect(jsonPath("$.data.lines[0].stationCode").value("448"));
	}

	@Test
	void stationExitsIncludeAccessibilitySignals() throws Exception {
		mockMvc.perform(get("/api/v1/stations/station-sangnoksu/exits"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value("exit-sangnoksu-1"))
			.andExpect(jsonPath("$.data[0].exitNumber").value("1"))
			.andExpect(jsonPath("$.data[0].name").value("1번 출구"))
			.andExpect(jsonPath("$.data[0].hasElevatorConnection").value(true))
			.andExpect(jsonPath("$.data[0].hasStairOnlyPath").value(false))
			.andExpect(jsonPath("$.data[0].dataConfidence").value("HIGH"));
	}

	@Test
	void stationFacilitiesIncludeTypeStatusAndConfidence() throws Exception {
		mockMvc.perform(get("/api/v1/stations/station-sangnoksu/facilities"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value("facility-sangnoksu-elevator-1"))
			.andExpect(jsonPath("$.data[0].type").value("ELEVATOR"))
			.andExpect(jsonPath("$.data[0].name").value("1번 출구 엘리베이터"))
			.andExpect(jsonPath("$.data[0].exitId").value("exit-sangnoksu-1"))
			.andExpect(jsonPath("$.data[0].status").value("NORMAL"))
			.andExpect(jsonPath("$.data[0].dataConfidence").value("HIGH"))
			.andExpect(jsonPath("$.data[0].lastUpdatedAt").value("2026-06-12"));
	}

	@Test
	void missingStationReturnsCommonErrorResponse() throws Exception {
		mockMvc.perform(get("/api/v1/stations/unknown-station"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("역 정보를 찾을 수 없습니다."));
	}

	@Test
	void missingStationExitsReturnCommonErrorResponse() throws Exception {
		mockMvc.perform(get("/api/v1/stations/unknown-station/exits"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("역 정보를 찾을 수 없습니다."));
	}

	@Test
	void missingStationFacilitiesReturnCommonErrorResponse() throws Exception {
		mockMvc.perform(get("/api/v1/stations/unknown-station/facilities"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("역 정보를 찾을 수 없습니다."));
	}
}
