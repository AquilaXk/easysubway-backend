package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #3036: 출발 시각 고정 p50 flake의 제거를 실제 CI 측정 기록으로 재생해 확인한다.
 *
 * <p>재생 자료는 {@code stable-point-measurement-replay.json}이고 묶음마다 출처가 있다. 예열 2회 방법의 CI JVM 28개는
 * flake를 재현하고, 예열 30회·동기 JIT 방법의 CI JVM은 같은 판정이 건강한 JVM을 거절하지 않으면서 진짜 회귀를 놓치지 않음을
 * 보인다. 판정은 실제 게이트({@link JourneyEnginePerformanceGateTest#regressions})를 그대로 부르고, 측정 JSON은 커밋된
 * 기준선에서 보정 시간과 출발 시각 고정 p50만 바꾼 것이라 다른 지표는 항상 기준선과 같다.</p>
 */
@DisplayName("#3036 출발 시각 고정 p50 측정 재생")
class JourneyEngineStablePointReplayTest {

	private static final Path REPLAY = Path.of("src/test/resources/journey-benchmark/stable-point-measurement-replay.json");
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String STABLE_POINT_P50 = "DEPART_AT.p50Nanos";
	/**
	 * 모든 러너 유형에서 반드시 잡아야 하는 둔화 배율. 기준선이 러너 유형 사이(최대 약 1.6배 차이)에 놓여 있어서 가장 빠른
	 * 유형은 약 2.6배부터, 가장 느린 유형은 약 1.5배부터 잡는다(6.4절).
	 */
	private static final double REAL_REGRESSION = 3.0;
	/** 가장 느린 러너 유형에서도 허용폭(2.0배) 안이라 설계상 통과시키는 둔화 배율(7절). */
	private static final double WITHIN_TOLERANCE = 1.2;
	private static final int BUNDLES = 3;

	private static JsonNode replay;
	private static JsonNode committedBaseline;

	@BeforeAll
	static void load() throws IOException {
		replay = JSON.readTree(Files.readString(REPLAY, StandardCharsets.UTF_8));
		committedBaseline = JSON.readTree(Files.readString(JourneyEnginePerformanceGateTest.BASELINE, StandardCharsets.UTF_8));
	}

	private static List<JsonNode> jvms(String group) {
		return StreamSupport.stream(replay.path(group).path("jvms").spliterator(), false).toList();
	}

	/** {@code template}에서 보정 시간과 번들별 출발 시각 고정 p50만 {@code scale}배로 바꾼 측정 JSON. */
	private static JsonNode measurement(JsonNode template, long calibration, JsonNode p50ByWorkload, double scale) {
		ObjectNode measured = template.deepCopy();
		measured.put("calibrationNanos", calibration);
		p50ByWorkload.fields().forEachRemaining(entry -> ((ObjectNode) measured.at("/workloads/" + entry.getKey() + "/modes/DEPART_AT"))
			.put("p50Nanos", Math.round(entry.getValue().asLong() * scale)));
		return measured;
	}

	private static JsonNode measurement(JsonNode template, JsonNode jvm, double scale) {
		return measurement(template, jvm.path("calibrationNanos").asLong(), jvm.path("p50Nanos"), scale);
	}

	/** 예열 2회 시절 기준선: 커밋 전 기준선의 보정 시간과 p50. */
	private static JsonNode legacyBaseline() {
		JsonNode legacy = replay.path("legacyBaseline");
		return measurement(committedBaseline, legacy.path("calibrationNanos").asLong(), legacy.path("p50Nanos"), 1.0);
	}

	/** 둔화 {@code scale}배를 주입한 JVM 측정에서 출발 시각 고정 p50 한도를 넘은 (JVM, 번들) 쌍의 수. */
	private static long flaggedPairs(JsonNode baseline, List<JsonNode> jvms, double scale) {
		return jvms.stream().flatMap(jvm -> JourneyEnginePerformanceGateTest.regressions(baseline, measurement(baseline, jvm, scale)).stream())
			.filter(problem -> problem.contains(STABLE_POINT_P50)).count();
	}

	@Test
	@DisplayName("예열 2회 방법의 건강한 CI JVM 일부가 옛 기준선의 출발 시각 고정 p50 한도를 넘는다(flake 재현)")
	void legacyWarmupFlakesOnHealthyJvms() {
		List<JsonNode> legacy = jvms("legacyCiJvms");
		assertThat(legacy).hasSize(28);
		// 코드가 같은 JVM 28개 중 둘이 한 번의 측정만으로 한도(정규화 0.80)를 넘는다. 둘 다 line4다.
		assertThat(flaggedPairs(legacyBaseline(), legacy, 1.0)).isEqualTo(2);
		// backend #532의 실패: 새 JVM 두 번을 더 재서 낸 중앙값도 한도를 넘었다.
		JsonNode failure = replay.path("legacyCiConfirmedFailure");
		assertThat(JourneyEnginePerformanceGateTest.regressions(legacyBaseline(), measurement(legacyBaseline(), failure, 1.0)))
			.singleElement().asString().contains("line4-corridor-slice", STABLE_POINT_P50);
	}

	@Test
	@DisplayName("재생 자료의 예열 회차가 게이트가 쓰는 예열 회차와 같다")
	void steadyReplayWasRecordedWithTheGateWarmup() {
		assertThat(replay.path("steadyCiJvms").path("warmupRounds").asInt())
			.isEqualTo(JourneyEnginePerformanceGateTest.WARMUP_ROUNDS);
		assertThat(replay.path("legacyCiJvms").path("warmupRounds").asInt())
			.isLessThan(JourneyEnginePerformanceGateTest.WARMUP_ROUNDS);
	}

	@Test
	@DisplayName("예열 30회·동기 JIT 방법의 건강한 CI JVM은 한 번의 측정으로도 출발 시각 고정 p50 한도를 넘지 않는다")
	void steadyWarmupDoesNotFlakeOnHealthyJvms() {
		List<JsonNode> steady = jvms("steadyCiJvms");
		assertThat(steady).hasSizeGreaterThanOrEqualTo(9);
		assertThat(flaggedPairs(committedBaseline, steady, 1.0)).isZero();
		assertThat(flaggedPairs(committedBaseline, steady, WITHIN_TOLERANCE)).isZero();
	}

	@Test
	@DisplayName("새 방법은 진짜 회귀를 모든 JVM과 번들에서 잡고, 예열 2회 방법은 일부를 놓친다")
	void steadyWarmupCatchesARealRegressionTheLegacyMethodMisses() {
		List<JsonNode> steady = jvms("steadyCiJvms");
		assertThat(flaggedPairs(committedBaseline, steady, REAL_REGRESSION)).isEqualTo((long) steady.size() * BUNDLES);
		// 같은 회귀를 예열 2회 시절 JVM에 주입하면 옛 기준선은 한도가 정상 상태의 5~9배에 있어 일부를 놓친다.
		List<JsonNode> legacy = jvms("legacyCiJvms");
		assertThat(flaggedPairs(legacyBaseline(), legacy, REAL_REGRESSION)).isLessThan((long) legacy.size() * BUNDLES);
	}
}
