package com.easysubway.realtime.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.realtime.application.RealtimeProvider;
import com.easysubway.realtime.application.RealtimeQuery;
import com.easysubway.realtime.domain.RealtimeArrival;
import com.easysubway.realtime.domain.RealtimeTrainPosition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"spring.profiles.active=test",
	"spring.flyway.enabled=false",
	"spring.datasource.url=jdbc:h2:mem:realtime-golden;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.driver-class-name=org.h2.Driver"
})
@Import(RealtimeApiGoldenFixtureTest.TestRealtimeProviderConfiguration.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("실시간 API golden fixture")
class RealtimeApiGoldenFixtureTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	@DisplayName("실시간 도착 응답은 golden fixture와 일치한다")
	void arrivalsMatchGoldenFixture() throws Exception {
		String body = mockMvc.perform(get("/api/v1/realtime/arrivals")
				.param("stationId", "station-sangnoksu")
				.param("lineId", "seoul-4")
				.param("providerLineId", "1004")
				.param("stationQueryName", "상록수"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertFixture("realtime-arrivals.ok.json", normalizeReceivedAt(body, "arrivals"));
	}

	@Test
	@DisplayName("열차 위치 응답은 golden fixture와 일치한다")
	void trainPositionsMatchGoldenFixture() throws Exception {
		String body = mockMvc.perform(get("/api/v1/realtime/train-positions")
				.param("lineId", "seoul-4")
				.param("providerLineId", "1004")
				.param("lineName", "4호선"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertFixture("realtime-train-positions.ok.json", normalizeReceivedAt(body, "trainPositions"));
	}

	private JsonNode normalizeReceivedAt(String body, String listName) throws Exception {
		ObjectNode root = (ObjectNode) objectMapper.readTree(body);
		JsonNode data = root.path("data");
		if (data instanceof ObjectNode object && object.has("receivedAt")) {
			object.put("receivedAt", "__RECEIVED_AT__");
		}
		JsonNode items = root.path("data").path(listName);
		if (items.isArray()) {
			for (JsonNode item : items) {
				if (item instanceof ObjectNode object) {
					if (object.has("receivedAt")) {
						object.put("receivedAt", "__RECEIVED_AT__");
					}
					if (object.has("providerReceivedAt")) {
						object.put("providerReceivedAt", "__RECEIVED_AT__");
					}
				}
			}
		}
		return root;
	}

	private void assertFixture(String fileName, JsonNode actual) throws Exception {
		JsonNode expected = objectMapper.readTree(
			Files.readString(repositoryRoot().resolve("contracts/api/fixtures").resolve(fileName))
		);
		assertThat(actual).isEqualTo(expected);
	}

	private Path repositoryRoot() {
		Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath();
		while (path != null && !Files.exists(path.resolve("contracts/api/fixtures"))) {
			path = path.getParent();
		}
		return path;
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestRealtimeProviderConfiguration {
		@Bean
		@Primary
		RealtimeProvider testRealtimeProvider() {
			return new RealtimeProvider() {
				@Override
				public List<RealtimeArrival> arrivals(RealtimeQuery query) {
					return List.of(new RealtimeArrival("4", "상록수", "당고개", "상행", "4123", 180,
						"3분 후", "전역 출발", Instant.now().toString()));
				}

				@Override
				public List<RealtimeTrainPosition> trainPositions(RealtimeQuery query) {
					return List.of(new RealtimeTrainPosition("4", "상록수", "4123", "운행중", "상행",
						"당고개", Instant.now().toString()));
				}
			};
		}
	}
}
