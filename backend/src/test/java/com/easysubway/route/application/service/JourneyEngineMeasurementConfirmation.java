package com.easysubway.route.application.service;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

/**
 * #518: 경로 엔진 성능 게이트의 확인 재측정.
 *
 * <p>호스티드 러너는 한 JVM의 측정 도중 몇 초 동안 느려지는 일이 있다(예: 마지막 번들 구간의 p50이 1.5배, p99가 2~6배).
 * 그 한 번의 JVM 때문에 무관한 PR이 막히지 않도록, 첫 측정이 허용폭을 넘을 때만 새 JVM에서 두 번 더 재고 세 측정의
 * 값별 중앙값으로 다시 판정한다. 기준선도 같은 방식(CI 3회의 값별 중앙값)으로 만들어 두 쪽의 잡음 크기가 같다.
 * 최종 판정은 여전히 허용폭이다. 세 측정 가운데 둘 이상이 한도를 넘는 진짜 회귀는 그대로 실패하고, 실패 메시지에는
 * 판정에 쓴 값과 세 측정이 모두 남는다. 결정적 값(작업량 카운터, 번들·질의 식별자, 거절 수)은 중앙값을 내지 않고 첫 측정을
 * 쓰므로 한 번이라도 기준선과 다르면 그대로 실패한다.</p>
 */
final class JourneyEngineMeasurementConfirmation {

	/** 첫 측정을 포함한 확인 측정 수. 홀수여야 값별 중앙값이 하나로 정해진다. */
	static final int MEASUREMENTS = 3;

	/** 새 JVM마다 달라지는 값. 이 이름의 숫자만 값별 중앙값으로 바꾼다. */
	private static final Set<String> VARYING_FIELDS = Set.of("calibrationNanos", "nanos", "p50Nanos", "p99Nanos",
		"allocatedBytes", "allocatedBytesPerQuery", "retainedBytes");

	private JourneyEngineMeasurementConfirmation() {
	}

	@FunctionalInterface
	interface Measurement {
		JsonNode measure() throws IOException, InterruptedException;
	}

	/** {@code judged}는 판정에 쓴 측정값이고 {@code measurements}는 실제로 잰 모든 측정이다. */
	record Verdict(List<String> regressions, JsonNode judged, List<JsonNode> measurements) {
	}

	static Verdict judge(JsonNode baseline, JsonNode first, Measurement remeasure) throws IOException, InterruptedException {
		List<JsonNode> measurements = new ArrayList<>(List.of(first));
		List<String> regressions = JourneyEnginePerformanceGateTest.regressions(baseline, first);
		JsonNode judged = first;
		if (!regressions.isEmpty()) {
			while (measurements.size() < MEASUREMENTS) measurements.add(remeasure.measure());
			judged = medianOf(measurements);
			regressions = JourneyEnginePerformanceGateTest.regressions(baseline, judged);
		}
		return new Verdict(regressions, judged, List.copyOf(measurements));
	}

	/** 첫 측정을 복사해 {@link #VARYING_FIELDS} 숫자를 모든 측정의 같은 위치 값의 중앙값으로 바꾼다. */
	static JsonNode medianOf(List<JsonNode> measurements) {
		JsonNode merged = measurements.getFirst().deepCopy();
		replaceWithMedians(merged, measurements, "");
		return merged;
	}

	private static void replaceWithMedians(JsonNode node, List<JsonNode> measurements, String path) {
		ObjectNode object = (ObjectNode) node;
		for (String name : StreamSupport.stream(((Iterable<String>) object::fieldNames).spliterator(), false).toList()) {
			String childPath = path + "/" + name;
			JsonNode child = object.get(name);
			if (child.isObject()) {
				replaceWithMedians(child, measurements, childPath);
			} else if (VARYING_FIELDS.contains(name) && child.isNumber()) {
				object.put(name, JourneyEnginePerformanceGateTest.median(valuesAt(measurements, childPath)));
			}
		}
	}

	private static long[] valuesAt(List<JsonNode> measurements, String path) {
		JsonPointer pointer = JsonPointer.compile(path);
		return measurements.stream().mapToLong(measurement -> {
			JsonNode value = measurement.at(pointer);
			if (!value.isNumber()) throw new IllegalStateException("measurements disagree about the shape at " + path);
			return value.asLong();
		}).toArray();
	}
}
