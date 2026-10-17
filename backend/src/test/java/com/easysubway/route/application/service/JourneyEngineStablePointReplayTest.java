package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.route.application.service.JourneyEngineDifferentialHarness.Mode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #3036: 출발 시각 고정 p50 flake의 제거를 실제 CI 측정 기록으로 재생해 확인한다.
 *
 * <p>예열 2회 방법의 CI JVM 기록은 {@code stable-point-measurement-replay.json}에 출처와 기대 건수가 있다. 예열 30회·동기 JIT 방법의
 * CI JVM 기록은 기준선의 원자료({@code engine-baseline-measurements.json})를 그대로 쓴다. 판정은 실제 게이트
 * ({@link JourneyEnginePerformanceGateTest#findRegressions})의 구조화된 결과를 쓰고, 측정 JSON은 커밋된 기준선에서 보정 시간과 출발
 * 시각 고정 p50만 바꾼 것이라 다른 지표는 항상 기준선과 같다.</p>
 */
@DisplayName("#3036 출발 시각 고정 p50 측정 재생")
class JourneyEngineStablePointReplayTest {

	private static final Path REPLAY = Path.of("src/test/resources/journey-benchmark/stable-point-measurement-replay.json");
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String STABLE_POINT = Mode.DEPART_AT.name();
	private static final String P50 = "p50Nanos";
	/**
	 * 모든 러너 유형에서 반드시 잡아야 하는 둔화 배율. 기준선이 러너 유형 사이(최대 약 1.6배 차이)에 놓여 있어서 가장 빠른
	 * 유형은 약 2.6배부터, 가장 느린 유형은 약 1.5배부터 잡는다(6.4절).
	 */
	private static final double REAL_REGRESSION = 3.0;
	/** 가장 느린 러너 유형에서도 허용폭(2.0배) 안이라 설계상 통과시키는 둔화 배율(7절). */
	private static final double WITHIN_TOLERANCE = 1.2;
	private static final double NO_SLOWDOWN = 1.0;

	private static JsonNode replay;
	private static JsonNode committedBaseline;
	private static List<JsonNode> steadyJvms;
	private static Set<String> stablePointSubjects;

	@BeforeAll
	static void load() throws IOException {
		replay = JSON.readTree(Files.readString(REPLAY, StandardCharsets.UTF_8));
		committedBaseline = JSON.readTree(Files.readString(JourneyEnginePerformanceGateTest.BASELINE, StandardCharsets.UTF_8));
		JsonNode raw = JSON.readTree(Files.readString(JourneyEngineBaselineDerivation.MEASUREMENTS, StandardCharsets.UTF_8));
		steadyJvms = StreamSupport.stream(raw.path("jobs").spliterator(), false)
			.flatMap(job -> StreamSupport.stream(job.path("jvms").spliterator(), false)).map(JourneyEngineStablePointReplayTest::stablePoint).toList();
		List<String> workloads = new ArrayList<>();
		committedBaseline.path("workloads").fieldNames().forEachRemaining(workloads::add);
		stablePointSubjects = workloads.stream()
			.map(workload -> JourneyEnginePerformanceGateTest.metricSubject(workload, STABLE_POINT, P50)).collect(Collectors.toSet());
	}

	/** 원자료의 JVM 하나에서 보정 시간과 번들별 출발 시각 고정 p50만 뽑는다. */
	private static JsonNode stablePoint(JsonNode jvm) {
		ObjectNode p50ByWorkload = JSON.createObjectNode();
		jvm.path("workloads").fields().forEachRemaining(entry -> p50ByWorkload.set(entry.getKey(),
			entry.getValue().path("modes").path(STABLE_POINT).path(P50)));
		return JSON.createObjectNode().put("calibrationNanos", jvm.path("calibrationNanos").asLong()).set("p50Nanos", p50ByWorkload);
	}

	private static List<JsonNode> legacyJvms() {
		return StreamSupport.stream(replay.path("legacyCiJvms").path("jvms").spliterator(), false).toList();
	}

	/** {@code template}에서 보정 시간과 번들별 출발 시각 고정 p50만 {@code scale}배로 바꾼 측정 JSON. */
	private static JsonNode measurement(JsonNode template, long calibration, JsonNode p50ByWorkload, double scale) {
		ObjectNode measured = template.deepCopy();
		measured.put("calibrationNanos", calibration);
		p50ByWorkload.fields().forEachRemaining(entry -> ((ObjectNode) measured.at("/workloads/" + entry.getKey() + "/modes/" + STABLE_POINT))
			.put(P50, Math.round(entry.getValue().asLong() * scale)));
		return measured;
	}

	private static JsonNode measurement(JsonNode template, JsonNode jvm, double scale) {
		return measurement(template, jvm.path("calibrationNanos").asLong(), jvm.path(P50), scale);
	}

	/** 예열 2회 시절 기준선: 커밋 전 기준선의 보정 시간과 p50. */
	private static JsonNode legacyBaseline() {
		JsonNode legacy = replay.path("legacyBaseline");
		return measurement(committedBaseline, legacy.path("calibrationNanos").asLong(), legacy.path(P50), NO_SLOWDOWN);
	}

	/** 둔화 {@code scale}배를 주입한 JVM 측정에서 출발 시각 고정 p50 한도를 넘은 (JVM, 번들) 쌍의 수. */
	private static long flaggedPairs(JsonNode baseline, List<JsonNode> jvms, double scale) {
		return jvms.stream().flatMap(jvm -> JourneyEnginePerformanceGateTest.findRegressions(baseline, measurement(baseline, jvm, scale)).stream())
			.filter(regression -> stablePointSubjects.contains(regression.subject())).count();
	}

	private static long pairsOf(List<JsonNode> jvms) {
		return (long) jvms.size() * stablePointSubjects.size();
	}

	@Test
	@DisplayName("예열 2회 방법의 건강한 CI JVM 일부가 옛 기준선의 출발 시각 고정 p50 한도를 넘는다(flake 재현)")
	void legacyWarmupFlakesOnHealthyJvms() {
		List<JsonNode> legacy = legacyJvms();
		JsonNode recorded = replay.path("legacyCiJvms");
		assertThat(legacy).hasSize(recorded.path("jvmCount").asInt());
		assertThat(flaggedPairs(legacyBaseline(), legacy, NO_SLOWDOWN)).isEqualTo(recorded.path("singleMeasurementFalseAlarms").asLong());
		// backend #532의 실패: 새 JVM 두 번을 더 재서 낸 중앙값도 한도를 넘었다.
		JsonNode failure = replay.path("legacyCiConfirmedFailure");
		assertThat(JourneyEnginePerformanceGateTest.findRegressions(legacyBaseline(), measurement(legacyBaseline(), failure, NO_SLOWDOWN)))
			.singleElement().extracting(JourneyEnginePerformanceGateTest.Regression::subject)
			.isEqualTo(JourneyEnginePerformanceGateTest.metricSubject(failure.path("failedWorkload").asText(), STABLE_POINT, P50));
	}

	@Test
	@DisplayName("재생 자료의 예열 회차가 게이트가 쓰는 예열 회차와 같다")
	void legacyWarmupWasShorterThanTheGateWarmup() {
		assertThat(replay.path("legacyCiJvms").path("warmupRounds").asInt()).isLessThan(JourneyEnginePerformanceGateTest.WARMUP_ROUNDS);
	}

	@Test
	@DisplayName("예열 30회·동기 JIT 방법의 건강한 CI JVM은 한 번의 측정으로도 출발 시각 고정 p50 한도를 넘지 않는다")
	void steadyWarmupDoesNotFlakeOnHealthyJvms() {
		assertThat(steadyJvms).isNotEmpty();
		assertThat(flaggedPairs(committedBaseline, steadyJvms, NO_SLOWDOWN)).isZero();
		assertThat(flaggedPairs(committedBaseline, steadyJvms, WITHIN_TOLERANCE)).isZero();
	}

	@Test
	@DisplayName("새 방법은 진짜 회귀를 모든 JVM과 번들에서 잡고, 예열 2회 방법은 일부를 놓친다")
	void steadyWarmupCatchesARealRegressionTheLegacyMethodMisses() {
		assertThat(flaggedPairs(committedBaseline, steadyJvms, REAL_REGRESSION)).isEqualTo(pairsOf(steadyJvms));
		// 같은 회귀를 예열 2회 시절 JVM에 주입하면 옛 기준선은 한도가 정상 상태의 5~9배에 있어 일부를 놓친다.
		assertThat(flaggedPairs(legacyBaseline(), legacyJvms(), REAL_REGRESSION)).isLessThan(pairsOf(legacyJvms()));
	}
}
