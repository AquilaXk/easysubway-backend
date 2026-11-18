package com.easysubway.route.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * #3036: 게이트 실패 메시지에 붙이는 러너 정보와 보정 시간 범위 경고.
 *
 * <p>정규화 지연은 호스티드 러너의 CPU 유형이 섞이면 유형 사이에서 1.6~1.7배 다르다(설계 문서 6.4절). 기준선은 산출에 쓴
 * 러너들의 보정 시간 범위({@code method.calibrationRangeNanos})를 기억하고, 이 범위를 벗어난 러너에서 실패하면 원인이 코드가 아니라
 * 새 러너 유형일 수 있음을 메시지로 알린다. 같은 경고가 {@link #RECALIBRATION_REPEATS}번 반복되면 기준선을 다시 산출한다.</p>
 */
final class JourneyEngineRunnerNotice {

	/** 같은 범위 밖 경고가 이만큼 반복되면 6.4절 절차로 기준선을 다시 산출한다. */
	static final int RECALIBRATION_REPEATS = 3;
	private static final Path CPU_INFO = Path.of("/proc/cpuinfo");
	private static final String CPU_MODEL_KEY = "model name";
	private static final double NANOS_PER_MILLI = 1_000_000.0;

	private JourneyEngineRunnerNotice() {
	}

	/** 실패 메시지용 한 줄 요약과, 범위 밖이면 경고 한 줄. */
	static String describe(JsonNode baseline, List<JsonNode> measurements, String cpuModel) {
		JsonNode range = baseline.at("/method/calibrationRangeNanos");
		List<Long> calibrations = measurements.stream().map(measurement -> measurement.path("calibrationNanos").asLong()).toList();
		String calibrationMillis = calibrations.stream().map(nanos -> String.format(Locale.ROOT, "%.1f", nanos / NANOS_PER_MILLI)).toList().toString();
		if (!range.path("min").isNumber() || !range.path("max").isNumber()) {
			// 범위가 없다고 판정 실패의 원인 메시지를 가리지 않는다. 대신 범위가 없다는 사실을 경고로 남긴다.
			return String.format(Locale.ROOT, "러너: cpu=%s, 측정별 보정 시간(ms)=%s%n경고: 기준선에 method.calibrationRangeNanos가 없어 범위를 확인할 수 없다.",
				cpuModel, calibrationMillis);
		}
		long min = range.path("min").asLong();
		long max = range.path("max").asLong();
		StringBuilder notice = new StringBuilder(String.format(Locale.ROOT,
			"러너: cpu=%s, 측정별 보정 시간(ms)=%s, 기준선 산출 범위(ms)=%.0f~%.0f", cpuModel, calibrationMillis,
			min / NANOS_PER_MILLI, max / NANOS_PER_MILLI));
		if (calibrations.stream().anyMatch(nanos -> nanos < min || nanos > max)) {
			notice.append(String.format(Locale.ROOT,
				"%n경고: 보정 시간이 기준선 산출 범위 밖이다. 새 러너 CPU 유형일 수 있다. 같은 경고가 %d번 반복되면 기준선을 다시 산출한다(설계 문서 6.4절).",
				RECALIBRATION_REPEATS));
		}
		return notice.toString();
	}

	/** 러너의 CPU 모델. 읽을 수 없으면 사유를 그대로 돌려줘 메시지에서 드러나게 한다. */
	static String cpuModel() {
		try {
			return Files.readAllLines(CPU_INFO, StandardCharsets.UTF_8).stream()
				.filter(line -> line.startsWith(CPU_MODEL_KEY))
				.map(line -> line.substring(line.indexOf(':') + 1).trim()).findFirst()
				.orElse("unobservable(no '" + CPU_MODEL_KEY + "' in " + CPU_INFO + ")");
		} catch (IOException exception) {
			return "unobservable(" + exception.getClass().getSimpleName() + ")";
		}
	}
}
