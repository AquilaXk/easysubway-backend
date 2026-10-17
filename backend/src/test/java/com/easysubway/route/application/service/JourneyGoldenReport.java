package com.easysubway.route.application.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * golden 파일의 쓰기·읽기와, 두 golden 사이의 사람이 읽는 차이 보고.
 *
 * <p>차이는 행(질의 x 프리셋) 단위로 무엇이 어떻게 달라졌는지 필드별로 적는다. 소요시간은 증감 초를 함께 보인다.</p>
 */
record JourneyGoldenReport(List<FixtureHeader> fixtures, List<JourneyGoldenRow> rows) {

	/** 행이 묶인 입력. {@code pin}이 달라지면 같은 질의의 결과가 달라지는 것이 자연스럽다. */
	record FixtureHeader(String id, String serviceDate, String pin) {
		private static final String PREFIX = "FIXTURE ";

		String render() {
			return PREFIX + id + " " + content();
		}

		/** id를 뺀 내용. 입력 변경 보고에서 앞뒤를 나란히 보인다. */
		String content() {
			return "serviceDate=" + serviceDate + " pin=" + pin;
		}

		static FixtureHeader parse(String line) {
			String[] parts = line.substring(PREFIX.length()).split(" ");
			if (parts.length != 3 || !parts[1].startsWith("serviceDate=") || !parts[2].startsWith("pin=")) {
				throw new IllegalArgumentException("golden fixture header is malformed: " + line);
			}
			return new FixtureHeader(parts[0], parts[1].substring("serviceDate=".length()), parts[2].substring("pin=".length()));
		}
	}

	/** golden 파일의 classpath 경로. 쓰는 곳은 {@code src/test/resources} 아래의 같은 경로다. */
	static final String RESOURCE = "journey-golden/v1/summary.golden";
	private static final String TITLE = "# Journey V3 golden summary v1";
	private static final String ROW_CONTINUATION = "  ";

	JourneyGoldenReport {
		fixtures = List.copyOf(fixtures);
		rows = List.copyOf(rows);
	}

	String render() {
		StringBuilder text = new StringBuilder(TITLE).append('\n');
		fixtures.forEach(fixture -> text.append(fixture.render()).append('\n'));
		rows.forEach(row -> text.append(row.render()).append('\n'));
		return text.toString();
	}

	static JourneyGoldenReport readResource() {
		try (InputStream stream = Objects.requireNonNull(
			JourneyGoldenReport.class.getClassLoader().getResourceAsStream(RESOURCE), RESOURCE)) {
			return parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}

	static JourneyGoldenReport parse(String text) {
		List<FixtureHeader> fixtures = new ArrayList<>();
		List<JourneyGoldenRow> rows = new ArrayList<>();
		List<String> block = new ArrayList<>();
		for (String line : text.split("\n")) {
			if (line.startsWith(ROW_CONTINUATION)) {
				block.add(line);
				continue;
			}
			flush(block, rows);
			if (line.startsWith("#") || line.isBlank()) continue;
			if (line.startsWith("FIXTURE ")) fixtures.add(FixtureHeader.parse(line));
			else block.add(line);
		}
		flush(block, rows);
		return new JourneyGoldenReport(fixtures, rows);
	}

	private static void flush(List<String> block, List<JourneyGoldenRow> rows) {
		if (block.isEmpty()) return;
		rows.add(JourneyGoldenRow.parse(block));
		block.clear();
	}

	/** {@code expected} 대비 {@code actual}의 차이 보고. 같으면 빈 문자열이다. */
	static String diff(JourneyGoldenReport expected, JourneyGoldenReport actual) {
		Map<String, JourneyGoldenRow> expectedRows = byKey(expected.rows());
		Map<String, JourneyGoldenRow> actualRows = byKey(actual.rows());
		List<String> sections = new ArrayList<>();
		sections.addAll(fixtureChanges(expected.fixtures(), actual.fixtures()));
		int changed = 0;
		int added = 0;
		int removed = 0;
		for (JourneyGoldenRow row : actual.rows()) {
			JourneyGoldenRow before = expectedRows.get(row.key());
			if (before == null) {
				added += 1;
				sections.add("[추가] " + firstLine(row));
			} else if (!before.equals(row)) {
				changed += 1;
				sections.add("[변경] " + row.key() + "\n" + rowChanges(before, row));
			}
		}
		for (JourneyGoldenRow row : expected.rows()) {
			if (!actualRows.containsKey(row.key())) {
				removed += 1;
				sections.add("[삭제] " + firstLine(row));
			}
		}
		if (sections.isEmpty()) return "";
		String summary = "golden 대비 차이: 변경 %d행, 추가 %d행, 삭제 %d행 (실제 %d행, golden %d행)".formatted(
			changed, added, removed, actual.rows().size(), expected.rows().size());
		return summary + "\n" + String.join("\n", sections) + "\n";
	}

	private static List<String> fixtureChanges(List<FixtureHeader> expected, List<FixtureHeader> actual) {
		Map<String, FixtureHeader> before = byId(expected);
		Map<String, FixtureHeader> after = byId(actual);
		List<String> changes = new ArrayList<>();
		after.forEach((id, header) -> {
			FixtureHeader previous = before.get(id);
			if (previous == null) changes.add("[입력 추가] " + header.render());
			else if (!previous.equals(header)) {
				changes.add("[입력 변경] " + id + ": " + previous.content() + " -> " + header.content());
			}
		});
		before.keySet().stream().filter(id -> !after.containsKey(id)).forEach(id -> changes.add("[입력 삭제] " + id));
		return changes;
	}

	private static String rowChanges(JourneyGoldenRow before, JourneyGoldenRow after) {
		List<String> lines = new ArrayList<>();
		if (before.status() != after.status()) lines.add("    상태: " + before.status() + " -> " + after.status());
		if (!before.detail().equals(after.detail())) {
			lines.add("    상세: " + displayed(before.detail()) + " -> " + displayed(after.detail()));
		}
		int common = Math.min(before.alternatives().size(), after.alternatives().size());
		for (int index = 0; index < common; index += 1) {
			String label = "    대안 #" + (index + 1) + " ";
			alternativeChanges(before.alternatives().get(index), after.alternatives().get(index))
				.forEach(change -> lines.add(label + change));
		}
		for (int index = common; index < after.alternatives().size(); index += 1) {
			lines.add("    대안 #" + (index + 1) + " 추가: " + after.alternatives().get(index).render());
		}
		for (int index = common; index < before.alternatives().size(); index += 1) {
			lines.add("    대안 #" + (index + 1) + " 삭제: " + before.alternatives().get(index).render());
		}
		return String.join("\n", lines);
	}

	private static String displayed(String detail) {
		return detail.isEmpty() ? "(없음)" : detail;
	}

	private static List<String> alternativeChanges(JourneyGoldenRow.Alternative before, JourneyGoldenRow.Alternative after) {
		List<String> changes = new ArrayList<>();
		compare(changes, "출발", before, after, JourneyGoldenRow.Alternative::departure);
		compare(changes, "도착", before, after, JourneyGoldenRow.Alternative::arrival);
		if (before.durationSeconds() != after.durationSeconds()) {
			changes.add("소요시간: %ds -> %ds (%+ds)".formatted(before.durationSeconds(), after.durationSeconds(),
				after.durationSeconds() - before.durationSeconds()));
		}
		compare(changes, "환승 수", before, after, JourneyGoldenRow.Alternative::transfers);
		compare(changes, "보행 거리(m)", before, after, JourneyGoldenRow.Alternative::walkingMeters);
		compare(changes, "탑승 노선", before, after, alternative -> String.join(">", alternative.lines()));
		compare(changes, "접근성", before, after, JourneyGoldenRow.Alternative::access);
		return changes;
	}

	private static <T> void compare(
		List<String> changes, String label, JourneyGoldenRow.Alternative before, JourneyGoldenRow.Alternative after,
		Function<JourneyGoldenRow.Alternative, T> field
	) {
		if (!Objects.equals(field.apply(before), field.apply(after))) {
			changes.add(label + ": " + field.apply(before) + " -> " + field.apply(after));
		}
	}

	private static String firstLine(JourneyGoldenRow row) {
		return row.render().lines().findFirst().orElseThrow();
	}

	private static Map<String, JourneyGoldenRow> byKey(List<JourneyGoldenRow> rows) {
		Map<String, JourneyGoldenRow> keyed = new LinkedHashMap<>();
		for (JourneyGoldenRow row : rows) {
			if (keyed.put(row.key(), row) != null) throw new IllegalArgumentException("duplicate golden row: " + row.key());
		}
		return keyed;
	}

	private static Map<String, FixtureHeader> byId(List<FixtureHeader> headers) {
		Map<String, FixtureHeader> keyed = new LinkedHashMap<>();
		headers.forEach(header -> keyed.put(header.id(), header));
		return keyed;
	}
}
