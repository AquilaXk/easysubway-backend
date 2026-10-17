package com.easysubway.route.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

/**
 * #3036: 성능 기준선의 지연·할당·컴파일 값을 CI 원자료에서 다시 계산한다(설계 문서 6.4절, 7절).
 *
 * <p>원자료({@code engine-baseline-measurements.json})는 Backend CI job마다 새 JVM 측정 셋이다. 한 job의 JVM은 같은 러너에서 재서
 * 러너 CPU 유형이 같다. 벽시계 값(p50, p99, 컴파일 시간)은 측정마다 그 JVM의 보정 시간으로 나눈 뒤 job별 중앙값을 내고, job들의
 * 기하평균에 기준선 보정 시간을 곱해 환산한다. 중앙값 하나는 러너 유형 하나를 고르지만 기하평균은 유형 사이에 놓는다. 질의당 할당·컴파일
 * 할당·보유 힙은 JVM이 달라도 거의 같아 모든 JVM의 중앙값이다. 결정적 값(작업량 카운터, 식별자, 운영 정책 재현, 허용폭)은 템플릿(현재
 * 기준선)의 것을 그대로 둔다.</p>
 *
 * <p>실행: 테스트 런타임 classpath로 {@code java -cp <classpath> ...JourneyEngineBaselineDerivation <출력 파일>}. 템플릿은 커밋된
 * 기준선이고 원자료는 위 리소스다.</p>
 */
final class JourneyEngineBaselineDerivation {

	static final Path MEASUREMENTS = Path.of("src/test/resources/journey-benchmark/engine-baseline-measurements.json");
	private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
	private static final double NANOS_PER_MILLI = 1_000_000.0;

	private JourneyEngineBaselineDerivation() {
	}

	/** 템플릿의 파생 값만 원자료로 다시 계산한 기준선. 파생 값이 아닌 항목은 템플릿 그대로다. */
	static JsonNode derive(JsonNode template, JsonNode raw) throws IOException {
		long baselineCalibration = raw.path("baselineCalibrationNanos").asLong();
		if (baselineCalibration != template.path("calibrationNanos").asLong()) {
			throw new IllegalArgumentException("raw baselineCalibrationNanos must equal the baseline calibrationNanos");
		}
		List<List<JsonNode>> jobs = StreamSupport.stream(raw.path("jobs").spliterator(), false)
			.map(job -> StreamSupport.stream(job.path("jvms").spliterator(), false).toList()).toList();
		for (List<JsonNode> job : jobs) {
			if (job.size() % 2 == 0) throw new IllegalArgumentException("each job needs an odd number of JVM measurements");
		}
		List<JsonNode> all = jobs.stream().flatMap(List::stream).toList();
		ObjectNode derived = template.deepCopy();
		for (String id : names(derived.path("workloads"))) {
			ObjectNode workload = (ObjectNode) derived.path("workloads").path(id);
			ObjectNode compile = (ObjectNode) workload.path("compile");
			compile.put("nanos", wallClock(jobs, "/workloads/" + id + "/compile/nanos", baselineCalibration));
			for (String field : List.of("allocatedBytes", "retainedBytes")) {
				compile.put(field, medianOf(all, "/workloads/" + id + "/compile/" + field));
			}
			for (String mode : names(workload.path("modes"))) {
				ObjectNode value = (ObjectNode) workload.path("modes").path(mode);
				String path = "/workloads/" + id + "/modes/" + mode + "/";
				value.put("p50Nanos", wallClock(jobs, path + "p50Nanos", baselineCalibration));
				value.put("p99Nanos", wallClock(jobs, path + "p99Nanos", baselineCalibration));
				value.put("allocatedBytesPerQuery", medianOf(all, path + "allocatedBytesPerQuery"));
			}
		}
		ObjectNode method = (ObjectNode) derived.path("method");
		method.put("warmupRounds", raw.path("method").path("warmupRounds").asInt());
		method.put("aggregation", raw.path("method").path("aggregation").asText());
		method.put("baselineSource", "geometric mean of " + jobs.size() + " Backend CI job medians (run "
			+ raw.path("jobs").path(0).path("run").asLong() + " attempts 1-" + jobs.size() + ")");
		long[] calibrations = all.stream().mapToLong(jvm -> jvm.path("calibrationNanos").asLong()).sorted().toArray();
		method.set("calibrationRangeNanos", JSON.createObjectNode()
			.put("min", (long) (Math.floor(calibrations[0] / NANOS_PER_MILLI) * NANOS_PER_MILLI))
			.put("max", (long) (Math.ceil(calibrations[calibrations.length - 1] / NANOS_PER_MILLI) * NANOS_PER_MILLI)));
		// 직렬화해 다시 읽어 파일에서 읽은 기준선과 같은 노드 타입으로 맞춘다.
		return JSON.readTree(JSON.writeValueAsString(derived));
	}

	/** job별로 (값 / 그 JVM의 보정 시간) 중앙값을 내고 job들의 기하평균에 기준선 보정 시간을 곱한다. */
	private static long wallClock(List<List<JsonNode>> jobs, String path, long baselineCalibration) {
		double logSum = 0;
		for (List<JsonNode> job : jobs) {
			double[] normalized = job.stream().mapToDouble(jvm -> jvm.at(path).asDouble() / jvm.path("calibrationNanos").asDouble())
				.sorted().toArray();
			logSum += StrictMath.log(normalized[(normalized.length - 1) / 2]);
		}
		return Math.round(StrictMath.exp(logSum / jobs.size()) * baselineCalibration);
	}

	private static long medianOf(List<JsonNode> jvms, String path) {
		long[] values = jvms.stream().mapToLong(jvm -> jvm.at(path).asLong()).toArray();
		return JourneyEnginePerformanceGateTest.median(values);
	}

	private static List<String> names(JsonNode object) {
		List<String> names = new ArrayList<>();
		object.fieldNames().forEachRemaining(names::add);
		return names;
	}

	/** 인자 하나: 파생 기준선을 쓸 파일. 템플릿은 커밋된 기준선이다. */
	public static void main(String[] args) throws IOException {
		if (args.length != 1) throw new IllegalArgumentException("usage: <output baseline json path>");
		JsonNode template = JSON.readTree(Files.readString(JourneyEnginePerformanceGateTest.BASELINE, StandardCharsets.UTF_8));
		JsonNode raw = JSON.readTree(Files.readString(MEASUREMENTS, StandardCharsets.UTF_8));
		Files.writeString(Path.of(args[0]), JSON.writeValueAsString(derive(template, raw)) + "\n", StandardCharsets.UTF_8);
	}
}
