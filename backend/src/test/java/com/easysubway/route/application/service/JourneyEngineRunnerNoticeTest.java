package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#3036 게이트 실패 메시지의 러너 정보와 보정 시간 범위 경고")
class JourneyEngineRunnerNoticeTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final long RANGE_MIN = 70_000_000L;
	private static final long RANGE_MAX = 98_000_000L;
	private static final String CPU = "Test CPU";

	private static JsonNode baseline() {
		return JSON.createObjectNode().set("method", JSON.createObjectNode().set("calibrationRangeNanos",
			JSON.createObjectNode().put("min", RANGE_MIN).put("max", RANGE_MAX)));
	}

	private static JsonNode measurement(long calibration) {
		return JSON.createObjectNode().put("calibrationNanos", calibration);
	}

	@Test
	@DisplayName("범위 안의 보정 시간은 러너 정보만 남기고 경고하지 않는다")
	void insideTheRangeOnlyDescribesTheRunner() {
		assertThat(JourneyEngineRunnerNotice.describe(baseline(), List.of(measurement(RANGE_MIN), measurement(RANGE_MAX)), CPU))
			.contains(CPU, "70.0", "98.0", "70~98").doesNotContain("경고");
	}

	@Test
	@DisplayName("측정 하나라도 범위 밖이면 재산정 기준과 함께 경고한다")
	void outsideTheRangeWarnsWithTheRecalibrationRule() {
		assertThat(JourneyEngineRunnerNotice.describe(baseline(), List.of(measurement(RANGE_MAX), measurement(RANGE_MAX + 1)), CPU))
			.contains("경고", "새 러너 CPU 유형", JourneyEngineRunnerNotice.RECALIBRATION_REPEATS + "번 반복");
		assertThat(JourneyEngineRunnerNotice.describe(baseline(), List.of(measurement(RANGE_MIN - 1)), CPU)).contains("경고");
	}

	@Test
	@DisplayName("기준선에 보정 시간 범위가 없어도 예외 없이 범위가 없다는 경고만 남긴다")
	void aBaselineWithoutARangeIsReportedNotThrown() {
		assertThat(JourneyEngineRunnerNotice.describe(JSON.createObjectNode(), List.of(measurement(RANGE_MIN)), CPU))
			.contains("경고", "calibrationRangeNanos", CPU);
	}

	@Test
	@DisplayName("CPU 모델은 관측할 수 없어도 사유가 있는 문자열을 돌려준다")
	void cpuModelIsNeverEmpty() {
		assertThat(JourneyEngineRunnerNotice.cpuModel()).isNotBlank();
	}
}
