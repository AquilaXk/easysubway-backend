package com.easysubway.train.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
	"spring.datasource.url=jdbc:h2:mem:train-search-contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.driver-class-name=org.h2.Driver"
})
@AutoConfigureMockMvc
@DisplayName("기차검색 ITX 제외 HTTP 계약")
class TrainSearchContractControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("station filter와 search query의 ITX-청춘 직접 입력은 같은 error envelope를 반환한다")
	void directItxRequestsReturnTheSameErrorEnvelope() throws Exception {
		for (String path : new String[] { "/api/v1/trains/stations", "/api/v1/trains/search" }) {
			mockMvc.perform(get(path).param("trainType", "ITX_CHEONGCHUN"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.data.code").value("TRAIN_SEARCH_UNSUPPORTED_TRAIN_TYPE"))
				.andExpect(jsonPath("$.message").value("지원하지 않는 열차종입니다."));
		}
	}

	@Test
	@DisplayName("지원 열차종은 #2094 구현 전 빈 결과로 위장하지 않고 unavailable로 닫힌다")
	void supportedTrainTypeFailsClosedUntilRoadmapImplementation() throws Exception {
		mockMvc.perform(get("/api/v1/trains/search").param("trainType", "KTX"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data.code").value("TRAIN_SEARCH_UNAVAILABLE"));
	}
}
