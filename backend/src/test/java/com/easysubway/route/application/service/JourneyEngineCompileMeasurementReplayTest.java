package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #518: 컴파일 시간 측정의 flake 제거를 실제 기록으로 재생해 확인한다.
 *
 * <p>재생 자료는 {@code compile-measurement-replay.json}이고 각 묶음에 출처(run id, 기록 방식)가 있다.
 * 옛 방법의 CI 측정 16회, 새 JVM의 연속 컴파일 기록(로컬 CPU 경합·단독, CI 실측), 새 방법의 JVM별 컴파일 추정 19회로
 * 이루어진다.</p>
 */
@DisplayName("#518 컴파일 시간 측정 재생")
class JourneyEngineCompileMeasurementReplayTest {

	private static final Path REPLAY = Path.of("src/test/resources/journey-benchmark/compile-measurement-replay.json");
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final int TRACE_LENGTH = JourneyEnginePerformanceGateTest.COMPILE_WARMUPS
		+ JourneyEnginePerformanceGateTest.COMPILE_SAMPLES;
	/** 진짜 회귀로 주입하는 컴파일 둔화 배율. */
	private static final int REGRESSION_FACTOR = 3;
	/** 관측된 가장 나쁜 JVM 상태에서도 컴파일 한도까지 남겨야 하는 배율(#519 리뷰 F3). */
	private static final double REQUIRED_MARGIN = 1.5;
	private static final double MILLIS_TO_NANOS = 1_000_000.0;

	private static JsonNode replay;
	private static JsonNode committedBaseline;

	@BeforeAll
	static void load() throws IOException {
		replay = JSON.readTree(Files.readString(REPLAY, StandardCharsets.UTF_8));
		committedBaseline = JSON.readTree(Files.readString(JourneyEnginePerformanceGateTest.BASELINE, StandardCharsets.UTF_8));
	}

	private static List<JsonNode> list(JsonNode array) {
		return StreamSupport.stream(array.spliterator(), false).toList();
	}

	private static long[] nanos(JsonNode millis) {
		return StreamSupport.stream(millis.spliterator(), false).mapToLong(value -> Math.round(value.asDouble() * MILLIS_TO_NANOS)).toArray();
	}

	private static List<String> compileRegressions(JsonNode baseline, long calibration, long compileNanos) {
		return JourneyEnginePerformanceGateTest.regressions(baseline, baselineWithCompile(calibration, compileNanos));
	}

	private static JsonNode baselineWithCompile(long calibration, long compileNanos) {
		return JSON.valueToTree(withCompileNanos(JourneyEnginePerformanceGateTest.sample(1_000, 2_000_000, 1_000_000, calibration,
			"bundle"), compileNanos));
	}

	private static Map<String, Object> withCompileNanos(Map<String, Object> sample, long nanos) {
		@SuppressWarnings("unchecked")
		Map<String, Object> workload = new LinkedHashMap<>((Map<String, Object>) ((Map<String, Object>) sample.get("workloads"))
			.get("sample-grid"));
		workload.put("compile", Map.of("nanos", nanos, "retainedBytes", 1_000_000));
		Map<String, Object> result = new LinkedHashMap<>(sample);
		result.put("workloads", Map.of("sample-grid", workload));
		return result;
	}

	@Test
	@DisplayName("옛 방법의 CI 측정 16회를 옛 기준선으로 판정하면 정확히 한 번 실패한다")
	void legacyMeasurementsAgainstTheOldBaselineFailExactlyOnce() {
		JsonNode legacy = replay.get("legacyCiMeasurements");
		long oldCompile = legacy.at("/baseline/metroCompileNanos").asLong();
		long oldCalibration = legacy.at("/baseline/calibrationNanos").asLong();
		JsonNode baseline = baselineWithCompile(oldCalibration, oldCompile);
		List<String> failed = new ArrayList<>();
		for (JsonNode row : list(legacy.get("rows"))) {
			long calibration = Math.round(row.get("calibrationMillis").asDouble() * MILLIS_TO_NANOS);
			long compile = Math.round(row.get("metroCompileMillis").asDouble() * MILLIS_TO_NANOS);
			if (!compileRegressions(baseline, calibration, compile).isEmpty()) {
				failed.add("run " + row.get("run").asLong() + " attempt " + row.get("attempt").asInt());
			}
		}
		assertThat(list(legacy.get("rows"))).hasSize(16);
		// backend #517 CI 첫 시도(run 38994797369)가 이 게이트를 막은 바로 그 측정이다.
		assertThat(failed).containsExactly("run 38994797369 attempt 1");
	}

	@Test
	@DisplayName("연속 컴파일 기록은 예열 구간을 버린 정상 상태 중앙값으로 판정해 통과하고 3배 회귀는 잡는다")
	void recordedColdTracesAreJudgedAtSteadyState() {
		JsonNode traces = replay.get("coldTraces");
		long calibration = 100_000_000L;
		JsonNode baseline = baselineWithCompile(calibration, Math.round(traces.get("localSteadyMillis").asDouble() * MILLIS_TO_NANOS));
		List<JsonNode> local = list(traces.get("local"));
		assertThat(local.stream().map(row -> row.get("source").asText()).distinct()).containsExactlyInAnyOrder(
			"local-aarch64-contended", "local-aarch64-quiet");
		for (JsonNode row : local) {
			long[] nanos = nanos(row.get("millis"));
			assertThat(nanos).as("%s 기록 길이", row.get("source").asText()).hasSize(TRACE_LENGTH);
			assertThat(compileRegressions(baseline, calibration, JourneyEnginePerformanceGateTest.steadyMedian(nanos)))
				.as("정상 상태 %s", Arrays.toString(nanos)).isEmpty();
			long[] regressed = Arrays.stream(nanos).map(value -> value * REGRESSION_FACTOR).toArray();
			assertThat(compileRegressions(baseline, calibration, JourneyEnginePerformanceGateTest.steadyMedian(regressed)))
				.as("%dx %s", REGRESSION_FACTOR, Arrays.toString(nanos)).singleElement().asString().contains("compile.nanos");
		}
	}

	@Test
	@DisplayName("CI에서 잰 연속 컴파일 기록도 커밋된 기준선 안에 있다")
	void recordedCiTracesStayWithinTheCommittedBaseline() {
		double baselineRatio = committedBaseline.at("/workloads/metro-grid-v1/compile/nanos").asDouble()
			/ committedBaseline.get("calibrationNanos").asDouble();
		double factor = JourneyEnginePerformanceGateTest.Tolerances.DEFAULT.normalizedCompileFactor();
		List<JsonNode> ci = list(replay.at("/coldTraces/ci"));
		assertThat(ci).isNotEmpty();
		for (JsonNode row : ci) {
			long[] nanos = nanos(row.get("millis"));
			assertThat(nanos).as("%s 기록 길이", row.get("source").asText()).hasSize(TRACE_LENGTH);
			double ratio = JourneyEnginePerformanceGateTest.steadyMedian(nanos)
				/ (row.get("calibrationMillis").asDouble() * MILLIS_TO_NANOS) / baselineRatio;
			assertThat(ratio).as(row.get("source").asText()).isLessThan(factor);
		}
	}

	/**
	 * district 컴파일은 백그라운드 JIT에서 JVM마다 상태가 두 가지로 나온다(낮은 상태 약 0.05~0.09, 높은 상태 0.11~0.17,
	 * 보정 시간으로 나눈 값). #518의 기준값은 관측된 가장 높은 상태가 한도의 1/1.5 이하가 되게 정했다(0.1022).
	 * 그래서 낮은 상태에서 3배 회귀는 놓칠 수 있고, 세 번들 컴파일이 모두 3배 느려지면 최종 방법(예열 30회) 측정 10개 중 8개에서
	 * 잡는다. 놓치는 둘은 빠른 러너의 metro 값(0.36·0.37)이다. 이 기록과 기준값은 동기 JIT로 바꾼 #3036 이전의 것이다.
	 */
	@Test
	@DisplayName("#518 컴파일 기준값은 백그라운드 JIT에서 관측된 JVM 상태 모두에서 한도까지 1.5배 이상 남기고 3배 회귀를 대부분 잡았다")
	void backgroundJitCompileBaselineKeptMarginOverObservedJvmStates() {
		double factor = JourneyEnginePerformanceGateTest.Tolerances.DEFAULT.normalizedCompileFactor();
		JsonNode legacyBaseline = replay.at("/perJvmCompileEstimates/baseline");
		List<JsonNode> rows = list(replay.at("/perJvmCompileEstimates/rows"));
		assertThat(rows).hasSize(19);
		assertThat(rows.stream().map(row -> row.get("run").asLong()).distinct().count()).isGreaterThanOrEqualTo(3);
		List<JsonNode> finalMethod = rows.stream().filter(row -> row.get("warmups").asInt()
			== JourneyEnginePerformanceGateTest.COMPILE_WARMUPS).toList();
		assertThat(finalMethod).hasSize(10);
		// district는 JVM별 JIT 상태라 예열 횟수와 무관하게 모든 기록을, metro·line4는 최종 방법의 기록만 쓴다.
		List<String> keys = List.of("metro", "district", "line4");
		for (String key : keys) {
			List<JsonNode> sample = key.equals("district") ? rows : finalMethod;
			double worst = sample.stream().mapToDouble(row -> legacyRatio(legacyBaseline, row, key)).max().orElseThrow();
			assertThat(factor / worst).as("%s 최악 상태의 한도 여유 배율", key).isGreaterThanOrEqualTo(REQUIRED_MARGIN);
		}
		long caught = finalMethod.stream().filter(row -> keys.stream().anyMatch(key ->
			REGRESSION_FACTOR * legacyRatio(legacyBaseline, row, key) > factor)).count();
		assertThat(caught).as("컴파일이 모두 %d배 느려졌을 때 잡히는 최종 방법 측정 수", REGRESSION_FACTOR).isEqualTo(8);
	}

	private static double legacyRatio(JsonNode legacyBaseline, JsonNode row, String key) {
		double baselineRatio = legacyBaseline.at("/compileNanos/" + key).asDouble() / legacyBaseline.get("calibrationNanos").asDouble();
		return row.get(key).asDouble() / row.get("calibrationMillis").asDouble() / baselineRatio;
	}

	/**
	 * #3036: 동기 JIT에서는 컴파일 시간도 JVM 상태가 하나라(보정 시간으로 나눈 값의 기준선 대비 0.78~1.40배) 기준값을 같은 CI
	 * 측정(기준선 원자료)에서 다시 만들었다. 최악 상태의 한도 여유가 1.5배 이상이고 컴파일이 3배 느려지면 모든 JVM에서 잡는다.
	 */
	@Test
	@DisplayName("동기 JIT 컴파일 기준값은 원자료 모든 JVM에서 한도까지 1.5배 이상 남기고 컴파일 3배 회귀를 모든 JVM에서 잡는다")
	void committedCompileBaselineKeepsMarginAndCatchesRegressionOverSyncJitMeasurements() throws IOException {
		double factor = JourneyEnginePerformanceGateTest.Tolerances.DEFAULT.normalizedCompileFactor();
		JsonNode raw = JSON.readTree(Files.readString(JourneyEngineBaselineDerivation.MEASUREMENTS, StandardCharsets.UTF_8));
		List<JsonNode> jvms = list(raw.get("jobs")).stream().flatMap(job -> list(job.get("jvms")).stream()).toList();
		List<String> workloads = new ArrayList<>();
		committedBaseline.get("workloads").fieldNames().forEachRemaining(workloads::add);
		for (String workload : workloads) {
			double worst = jvms.stream().mapToDouble(jvm -> syncRatio(jvm, workload)).max().orElseThrow();
			assertThat(factor / worst).as("%s 최악 상태의 한도 여유 배율", workload).isGreaterThanOrEqualTo(REQUIRED_MARGIN);
		}
		long caught = jvms.stream().filter(jvm -> workloads.stream().anyMatch(workload ->
			REGRESSION_FACTOR * syncRatio(jvm, workload) > factor)).count();
		assertThat(caught).as("컴파일이 모두 %d배 느려졌을 때 잡히는 JVM 수", REGRESSION_FACTOR).isEqualTo(jvms.size());
	}

	private static double syncRatio(JsonNode jvm, String workload) {
		double baselineRatio = committedBaseline.at("/workloads/" + workload + "/compile/nanos").asDouble()
			/ committedBaseline.get("calibrationNanos").asDouble();
		return jvm.at("/workloads/" + workload + "/compile/nanos").asDouble() / jvm.get("calibrationNanos").asDouble() / baselineRatio;
	}
}
