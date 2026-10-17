package com.easysubway.route.application.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 질의 하나를 보행 프로필 프리셋 하나로 돌린 결과의 요약. 사람이 읽고 git diff로 검토할 수 있는 텍스트로 쓰고 읽는다.
 *
 * <p>응답 시각이나 요청 id처럼 실행마다 바뀌는 값은 담지 않는다. 시각은 서비스일 0시부터 흐른 계획 시각이며 자정을 넘으면
 * 24시 이후로 쓴다(GTFS 관례). 날짜를 담지 않으므로 서비스일을 바꿔 돌려도 시간표 차이만 드러난다.</p>
 *
 * <pre>
 * capital-example STANDARD FOUND stairFree=NOT_FOUND facility=UNOBSERVED
 *   #1 08:03:00&gt;08:41:30 2310s transfers=1 walk=87m lines=line-2&gt;line-1 access=UNDETERMINED
 * </pre>
 */
record JourneyGoldenRow(
	String queryId,
	JourneyGoldenPreset preset,
	Status status,
	String detail,
	List<Alternative> alternatives
) {

	/** 경로 존재 여부와 안전 상태. 판정 규칙은 {@link JourneyGoldenRunner}가 갖는다. */
	enum Status { FOUND, BLOCKED, UNKNOWN, UNSUPPORTED, ERROR }

	record Alternative(
		String departure,
		String arrival,
		long durationSeconds,
		int transfers,
		long walkingMeters,
		List<String> lines,
		String access
	) {
		Alternative {
			lines = List.copyOf(lines);
		}

		String render() {
			return "%s>%s %ds transfers=%d walk=%dm lines=%s access=%s".formatted(
				departure, arrival, durationSeconds, transfers, walkingMeters, String.join(">", lines), access);
		}
	}

	private static final String ALTERNATIVE_INDENT = "  #";
	private static final Pattern HEADER = Pattern.compile("^(\\S+) (\\S+) (\\S+)(?: (.*))?$");
	private static final Pattern ALTERNATIVE = Pattern.compile(
		"^ {2}#\\d+ (\\S+)>(\\S+) (\\d+)s transfers=(\\d+) walk=(\\d+)m lines=(\\S+) access=(\\S+)$");

	JourneyGoldenRow {
		Objects.requireNonNull(queryId, "queryId");
		Objects.requireNonNull(preset, "preset");
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(detail, "detail");
		alternatives = List.copyOf(alternatives);
		if ((status == Status.FOUND) == alternatives.isEmpty()) {
			throw new IllegalArgumentException("alternatives must be present exactly when the status is FOUND");
		}
	}

	/** 비교와 diff의 키. */
	String key() {
		return queryId + " " + preset;
	}

	String render() {
		StringBuilder text = new StringBuilder(key()).append(' ').append(status);
		if (!detail.isEmpty()) text.append(' ').append(detail);
		for (int index = 0; index < alternatives.size(); index += 1) {
			text.append('\n').append(ALTERNATIVE_INDENT).append(index + 1).append(' ')
				.append(alternatives.get(index).render());
		}
		return text.toString();
	}

	/** {@link #render()}가 쓴 한 행(머리 줄과 이어지는 대안 줄)을 읽는다. */
	static JourneyGoldenRow parse(List<String> lines) {
		Matcher header = HEADER.matcher(lines.getFirst());
		if (!header.matches()) throw new IllegalArgumentException("golden row header is malformed: " + lines.getFirst());
		List<Alternative> alternatives = new ArrayList<>();
		for (String line : lines.subList(1, lines.size())) {
			Matcher alternative = ALTERNATIVE.matcher(line);
			if (!alternative.matches()) throw new IllegalArgumentException("golden alternative is malformed: " + line);
			alternatives.add(new Alternative(alternative.group(1), alternative.group(2),
				Long.parseLong(alternative.group(3)), Integer.parseInt(alternative.group(4)),
				Long.parseLong(alternative.group(5)), List.of(alternative.group(6).split(">")), alternative.group(7)));
		}
		return new JourneyGoldenRow(header.group(1), JourneyGoldenPreset.valueOf(header.group(2)),
			Status.valueOf(header.group(3)), header.group(4) == null ? "" : header.group(4), alternatives);
	}
}
