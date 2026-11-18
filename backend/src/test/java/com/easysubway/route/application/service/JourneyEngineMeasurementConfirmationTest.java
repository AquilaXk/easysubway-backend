package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#518 경로 엔진 성능 게이트 확인 재측정")
class JourneyEngineMeasurementConfirmationTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String DEPART_AT = "/workloads/sample-grid/modes/DEPART_AT";
	private static final long CALIBRATION = 100_000_000L;
	/** sample()의 출발 시각 고정 p50(ns). p99는 p50의 2배다. */
	private static final long P50 = 2_000_000L;
	/** p99 허용폭(2.5배)을 크게 넘는 일시 정지가 낀 p99. 실패 job에서 본 마지막 번들 구간 p99는 기준선의 2~6배였다. */
	private static final long PAUSED_P99 = 24_000_000L;

	private static ObjectNode clean() {
		return JSON.valueToTree(JourneyEnginePerformanceGateTest.sample(1_000, P50, 1_000_000, CALIBRATION, "bundle"));
	}

	private static ObjectNode departAt(ObjectNode measurement) {
		return (ObjectNode) measurement.at(DEPART_AT);
	}

	private static ObjectNode withDepartAt(String field, long value) {
		ObjectNode measurement = clean();
		departAt(measurement).put(field, value);
		return measurement;
	}

	/** 호출 횟수를 세고, 미리 정한 측정을 차례로 돌려주는 재측정. */
	private static final class ScriptedMeasurement implements JourneyEngineMeasurementConfirmation.Measurement {
		private final Deque<JsonNode> remaining = new ArrayDeque<>();
		private int calls;

		ScriptedMeasurement(JsonNode... measurements) {
			remaining.addAll(List.of(measurements));
		}

		@Override
		public JsonNode measure() {
			calls += 1;
			return remaining.removeFirst();
		}
	}

	@Test
	@DisplayName("첫 측정이 허용폭 안이면 다시 재지 않는다")
	void doesNotRemeasureWhenTheFirstMeasurementPasses() throws Exception {
		ScriptedMeasurement remeasure = new ScriptedMeasurement();
		var verdict = JourneyEngineMeasurementConfirmation.judge(clean(), clean(), remeasure);
		assertThat(verdict.regressions()).isEmpty();
		assertThat(remeasure.calls).isZero();
		assertThat(verdict.measurements()).hasSize(1);
	}

	@Test
	@DisplayName("한 JVM의 일시 정지로 첫 측정만 한도를 넘으면 두 번 더 재서 통과한다")
	void aTransientPauseInTheFirstMeasurementIsConfirmedAway() throws Exception {
		ObjectNode paused = withDepartAt("p99Nanos", PAUSED_P99);
		// 일시 정지가 낀 측정을 그대로 판정하면 기존 게이트처럼 실패한다.
		assertThat(JourneyEnginePerformanceGateTest.regressions(clean(), paused)).singleElement().asString()
			.contains("DEPART_AT.p99Nanos");
		ScriptedMeasurement remeasure = new ScriptedMeasurement(clean(), clean());
		var verdict = JourneyEngineMeasurementConfirmation.judge(clean(), paused, remeasure);
		assertThat(verdict.regressions()).isEmpty();
		assertThat(remeasure.calls).isEqualTo(JourneyEngineMeasurementConfirmation.MEASUREMENTS - 1);
		assertThat(verdict.measurements()).hasSize(JourneyEngineMeasurementConfirmation.MEASUREMENTS);
		assertThat(verdict.judged().at(DEPART_AT + "/p99Nanos").asLong()).isEqualTo(2 * P50);
	}

	@Test
	@DisplayName("세 측정 중 둘 이상이 한도를 넘는 진짜 회귀는 재측정 뒤에도 실패한다")
	void aRealRegressionFailsAfterConfirmation() throws Exception {
		// p50 한도는 2.0배, p99 한도는 2.5배다. 모든 측정이 한도를 넘으면 중앙값도 넘는다.
		ObjectNode slowP50 = withDepartAt("p50Nanos", 3 * P50);
		var p50Verdict = JourneyEngineMeasurementConfirmation.judge(clean(), slowP50,
			new ScriptedMeasurement(slowP50.deepCopy(), slowP50.deepCopy()));
		assertThat(p50Verdict.regressions()).singleElement().asString().contains("DEPART_AT.p50Nanos", "x2.0");
		ObjectNode pausedA = withDepartAt("p99Nanos", PAUSED_P99);
		var majority = JourneyEngineMeasurementConfirmation.judge(clean(), pausedA,
			new ScriptedMeasurement(pausedA.deepCopy(), clean()));
		assertThat(majority.regressions()).singleElement().asString().contains("DEPART_AT.p99Nanos");
		assertThat(majority.measurements()).hasSize(JourneyEngineMeasurementConfirmation.MEASUREMENTS);
	}

	@Test
	@DisplayName("결정적 작업량 카운터는 중앙값으로 덮지 않고 첫 측정이 그대로 실패한다")
	void deterministicCountersAreNotVotedAway() throws Exception {
		ObjectNode moreWork = clean();
		((ObjectNode) departAt(moreWork).get("work")).put("expandedTrips", 1_100);
		ScriptedMeasurement remeasure = new ScriptedMeasurement(clean(), clean());
		var verdict = JourneyEngineMeasurementConfirmation.judge(clean(), moreWork, remeasure);
		assertThat(verdict.regressions()).singleElement().asString().contains("work", "DEPART_AT.expandedTrips", "regressed");
	}

	@Test
	@DisplayName("값별 중앙값은 새 JVM마다 달라지는 값에만 적용하고 모양이 다르면 거절한다")
	void medianOfAppliesToVaryingValuesOnlyAndRejectsMismatchedShapes() {
		List<JsonNode> measurements = new ArrayList<>();
		for (long scale : new long[] {1, 5, 3}) {
			ObjectNode measurement = clean();
			measurement.put("calibrationNanos", CALIBRATION * scale);
			departAt(measurement).put("p50Nanos", P50 * scale);
			((ObjectNode) measurement.at("/workloads/sample-grid/compile")).put("nanos", 50_000_000L * scale);
			((ObjectNode) departAt(measurement).get("work")).put("expandedTrips", 1_000 * scale);
			measurements.add(measurement);
		}
		JsonNode merged = JourneyEngineMeasurementConfirmation.medianOf(measurements);
		assertThat(merged.get("calibrationNanos").asLong()).isEqualTo(CALIBRATION * 3);
		assertThat(merged.at(DEPART_AT + "/p50Nanos").asLong()).isEqualTo(P50 * 3);
		assertThat(merged.at("/workloads/sample-grid/compile/nanos").asLong()).isEqualTo(50_000_000L * 3);
		// 작업량 카운터는 첫 측정 값이다.
		assertThat(merged.at(DEPART_AT + "/work/expandedTrips").asLong()).isEqualTo(1_000);
		ObjectNode missing = clean();
		departAt(missing).remove("p50Nanos");
		assertThatThrownBy(() -> JourneyEngineMeasurementConfirmation.medianOf(List.of(clean(), missing, clean())))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("p50Nanos");
	}
}
