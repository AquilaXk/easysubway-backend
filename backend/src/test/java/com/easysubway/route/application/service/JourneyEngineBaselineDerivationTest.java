package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#3036 성능 기준선을 CI 원자료에서 다시 계산한 값과 비교")
class JourneyEngineBaselineDerivationTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	/** 러너 CPU 유형이 최소 둘 이상 섞이도록 필요한 job 수(설계 문서 6.4절). */
	private static final int MIN_JOBS = 3;
	private static final double HAND_EDIT_FACTOR = 1.2;
	private static final String FIRST_WORKLOAD = "/workloads/line4-corridor-slice/modes/DEPART_AT/p50Nanos";

	private static JsonNode baseline;
	private static JsonNode raw;

	@BeforeAll
	static void load() throws IOException {
		baseline = JSON.readTree(Files.readString(JourneyEnginePerformanceGateTest.BASELINE, StandardCharsets.UTF_8));
		raw = JSON.readTree(Files.readString(JourneyEngineBaselineDerivation.MEASUREMENTS, StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("커밋된 기준선의 지연·할당·컴파일 값은 원자료에서 다시 계산한 값과 같다")
	void committedBaselineEqualsTheDerivationFromRawMeasurements() throws IOException {
		assertThat(JourneyEngineBaselineDerivation.derive(baseline, raw)).isEqualTo(baseline);
	}

	@Test
	@DisplayName("손으로 고친 기준선 값(전체 및 CPU별 기준선)은 원자료로 다시 계산한 값과 달라 잡힌다")
	void aHandEditedBaselineValueDiffersFromTheDerivation() throws IOException {
		ObjectNode edited = baseline.deepCopy();
		long original = edited.at(FIRST_WORKLOAD).asLong();
		((ObjectNode) edited.at(FIRST_WORKLOAD.substring(0, FIRST_WORKLOAD.lastIndexOf('/')))).put("p50Nanos", Math.round(original * HAND_EDIT_FACTOR));
		assertThat(JourneyEngineBaselineDerivation.derive(edited, raw)).isNotEqualTo(edited);

		ObjectNode cpuEdited = baseline.deepCopy();
		String cpuWorkloadPath = "/cpuBaselines/AMD EPYC 9V45 96-Core Processor" + FIRST_WORKLOAD;
		long cpuOriginal = cpuEdited.at(cpuWorkloadPath).asLong();
		((ObjectNode) cpuEdited.at(cpuWorkloadPath.substring(0, cpuWorkloadPath.lastIndexOf('/'))))
			.put("p50Nanos", Math.round(cpuOriginal * HAND_EDIT_FACTOR));
		assertThat(JourneyEngineBaselineDerivation.derive(cpuEdited, raw)).isNotEqualTo(cpuEdited);
	}

	@Test
	@DisplayName("원자료는 같은 측정 방법·보정 시간 기준이고 호스티드 6개 러너 CPU 유형별 기준선을 모두 포함한다")
	void rawMeasurementsMatchTheBaselineMethod() {
		assertThat(raw.path("method").path("aggregation")).isEqualTo(baseline.path("method").path("aggregation"));
		assertThat(raw.path("method").path("warmupRounds").asInt()).isEqualTo(JourneyEnginePerformanceGateTest.WARMUP_ROUNDS);
		assertThat(raw.path("baselineCalibrationNanos")).isEqualTo(baseline.path("calibrationNanos"));
		List<JsonNode> jobs = StreamSupport.stream(raw.path("jobs").spliterator(), false).toList();
		assertThat(jobs).hasSizeGreaterThanOrEqualTo(MIN_JOBS);
		assertThat(jobs).allSatisfy(job -> assertThat(job.path("jvms").size() % 2).as("job당 JVM 수는 홀수").isEqualTo(1));
		List<String> cpuModels = jobs.stream()
			.map(job -> job.path("cpuModel").asText(""))
			.filter(model -> !model.isBlank())
			.distinct()
			.sorted()
			.toList();
		assertThat(cpuModels).containsExactly(
			"AMD EPYC 7763 64-Core Processor",
			"AMD EPYC 9V45 96-Core Processor",
			"AMD EPYC 9V74 80-Core Processor",
			"INTEL(R) XEON(R) PLATINUM 8573C",
			"Intel(R) Xeon(R) 6973P-C",
			"Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz");
		List<String> baselineCpuModels = new java.util.ArrayList<>();
		baseline.path("cpuBaselines").fieldNames().forEachRemaining(baselineCpuModels::add);
		assertThat(baselineCpuModels).containsExactlyElementsOf(cpuModels);
	}

	@Test
	@DisplayName("기준선의 보정 시간 범위(70~140ms)는 원자료의 모든 보정 시간을 덮는다")
	void calibrationRangeCoversEveryRawMeasurement() {
		long min = baseline.at("/method/calibrationRangeNanos/min").asLong();
		long max = baseline.at("/method/calibrationRangeNanos/max").asLong();
		assertThat(min).isEqualTo(70_000_000L);
		assertThat(max).isEqualTo(140_000_000L);
		List<Long> calibrations = StreamSupport.stream(raw.path("jobs").spliterator(), false)
			.flatMap(job -> StreamSupport.stream(job.path("jvms").spliterator(), false)).map(jvm -> jvm.path("calibrationNanos").asLong()).toList();
		assertThat(calibrations).allSatisfy(nanos -> assertThat(nanos).isBetween(min, max));
	}
}
