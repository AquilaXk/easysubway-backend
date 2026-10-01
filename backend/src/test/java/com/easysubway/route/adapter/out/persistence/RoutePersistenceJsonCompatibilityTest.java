package com.easysubway.route.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.route.domain.RouteStep;
import com.easysubway.route.domain.RouteWarning;
import com.easysubway.route.domain.RouteWarningCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("경로 저장소 JSON 호환성 테스트")
class RoutePersistenceJsonCompatibilityTest {

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@Test
	@DisplayName("warnings_json fixture를 읽고 다시 직렬화했을 때 바이트가 동일하다")
	void warningsJsonRoundTripPreservesByteEquality() throws Exception {
		String expectedJson = loadResource("/route/persistence/legacy-warnings.json");

		List<RouteWarningJson> dtoList = objectMapper.readValue(expectedJson, new TypeReference<List<RouteWarningJson>>() {});
		List<RouteWarning> domainList = dtoList.stream().map(RouteWarningJson::toDomain).toList();

		assertThat(domainList).containsExactly(
			new RouteWarning(RouteWarningCode.LOW_DATA_CONFIDENCE),
			new RouteWarning(RouteWarningCode.STAIR_ONLY_ACCESS)
		);

		List<RouteWarningJson> roundTripDto = domainList.stream().map(RouteWarningJson::from).toList();
		String actualJson = objectMapper.writeValueAsString(roundTripDto);

		assertThat(actualJson).isEqualTo(expectedJson);
	}

	@Test
	@DisplayName("warnings_json에 미확인 필드가 있어도 JsonIgnoreProperties로 안전하게 역직렬화된다")
	void warningsJsonIgnoresUnknownProperties() throws Exception {
		String jsonWithUnknownProperties = """
			[{"code":"STAIR_ONLY_ACCESS","unknown_attribute":"legacy_value","extraNumber":42}]
			""";

		List<RouteWarningJson> dtoList = objectMapper.readValue(jsonWithUnknownProperties, new TypeReference<List<RouteWarningJson>>() {});
		List<RouteWarning> domainList = dtoList.stream().map(RouteWarningJson::toDomain).toList();

		assertThat(domainList).containsExactly(
			new RouteWarning(RouteWarningCode.STAIR_ONLY_ACCESS)
		);
	}

	@Test
	@DisplayName("steps_json fixture를 읽고 다시 직렬화했을 때 바이트가 동일하다")
	void stepsJsonRoundTripPreservesByteEquality() throws Exception {
		String expectedJson = loadResource("/route/persistence/legacy-steps.json");

		List<RouteStep> steps = objectMapper.readValue(expectedJson, new TypeReference<List<RouteStep>>() {});
		assertThat(steps).hasSize(1);
		assertThat(steps.getFirst().lineName()).isEqualTo("수도권 4호선");

		String actualJson = objectMapper.writeValueAsString(steps);
		assertThat(actualJson).isEqualTo(expectedJson);
	}

	private String loadResource(String path) throws Exception {
		try (InputStream in = getClass().getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalArgumentException("Resource not found: " + path);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
		}
	}
}
