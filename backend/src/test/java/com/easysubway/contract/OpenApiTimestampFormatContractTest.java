package com.easysubway.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

/**
 * 공개 OpenAPI 계약에서 시각 필드({@code ...At})는 {@code format: date-time}(offset 필수)이어야 한다(이슈 #534).
 *
 * <p>`format` 없이 `type: string`만 두면 구현이 시간대 없는 값을 내보내도 계약 위반이 아니게 된다.
 * Journey V3 계약은 별도 계약 테스트가 소유하므로 여기서 다루지 않는다.
 */
@DisplayName("OpenAPI 시각 필드 형식 계약")
class OpenApiTimestampFormatContractTest {

	private static final Path CONTRACTS_DIRECTORY = Path.of("..", "contracts", "api");
	private static final String TIMESTAMP_NAME_SUFFIX = "At";

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"report-api.openapi.yaml", "realtime-api.openapi.yaml", "train-api.openapi.yaml"})
	@DisplayName("...At 속성은 format date-time 문자열이다")
	void timestampPropertiesAreDateTime(String fileName) {
		List<String> violations = timestampProperties(load(fileName), "").stream()
			.filter(property -> !property.isDateTimeString())
			.map(TimestampProperty::path)
			.toList();

		assertThat(violations).isEmpty();
	}

	@Test
	@DisplayName("검사 대상 계약에는 시각 속성이 실제로 있다")
	void timestampPropertiesExist() {
		assertThat(timestampProperties(load("report-api.openapi.yaml"), ""))
			.extracting(TimestampProperty::name)
			.contains("createdAt", "reviewedAt", "expiresAt");
	}

	@Test
	@DisplayName("계약은 시각을 시간대 표기가 없는 지역 시각으로 설명하지 않는다")
	void contractsDoNotDescribeZonelessTimestamps() throws IOException {
		for (String fileName : List.of("report-api.openapi.yaml", "realtime-api.openapi.yaml")) {
			assertThat(Files.readString(CONTRACTS_DIRECTORY.resolve(fileName), StandardCharsets.UTF_8))
				.as(fileName)
				.doesNotContain("시간대 표기가 없는");
		}
	}

	/** 문서 전체를 훑어 이름이 {@code ...At}으로 끝나는 모든 속성을 모은다. */
	private static List<TimestampProperty> timestampProperties(Object node, String path) {
		List<TimestampProperty> found = new ArrayList<>();
		if (node instanceof Map<?, ?> map) {
			if (map.get("properties") instanceof Map<?, ?> properties) {
				properties.forEach((name, schema) -> {
					if (String.valueOf(name).endsWith(TIMESTAMP_NAME_SUFFIX) && schema instanceof Map<?, ?> definition) {
						found.add(new TimestampProperty(path, String.valueOf(name), definition));
					}
				});
			}
			map.forEach((key, value) -> found.addAll(timestampProperties(value, path + "/" + key)));
		} else if (node instanceof List<?> list) {
			list.forEach(item -> found.addAll(timestampProperties(item, path)));
		}
		return found;
	}

	private record TimestampProperty(String parentPath, String name, Map<?, ?> definition) {

		String path() {
			return parentPath + "." + name;
		}

		boolean isDateTimeString() {
			return "string".equals(definition.get("type")) && "date-time".equals(definition.get("format"));
		}
	}

	private static Map<String, Object> load(String fileName) {
		Path path = CONTRACTS_DIRECTORY.resolve(fileName);
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			Map<String, Object> document = new Yaml().load(reader);
			assertThat(document).as(fileName + " 를 읽지 못했다").isNotNull();
			return document;
		} catch (IOException exception) {
			throw new IllegalStateException("계약 파일을 읽지 못했다: " + path, exception);
		}
	}
}
