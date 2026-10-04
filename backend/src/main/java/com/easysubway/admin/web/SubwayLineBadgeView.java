package com.easysubway.admin.web;

import com.easysubway.transit.domain.SubwayLine;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 지하철 노선 시각화 뱃지 뷰 모델.
 * 카카오맵·네이버지도·공식 교통 기준의 노선 고유 색상과 엠블럼을 제공한다.
 */
public record SubwayLineBadgeView(
	String id,
	String name,
	String emblemText,
	String color
) {

	private static final Pattern NUMBERED_LINE = Pattern.compile("(\\d+)호선");

	private static final Map<String, String> NUMBERED_COLORS = Map.of(
		"1", "#0052A4", "2", "#00A84D", "3", "#EF7C1C", "4", "#00A5DE", "5", "#996CAC",
		"6", "#CD7C2F", "7", "#747F00", "8", "#E6186C", "9", "#BB8336"
	);

	private static final List<LineMeta> NAMED_LINES = List.of(
		new LineMeta("수인분당", "수인", "#F5A200"),
		new LineMeta("신분당", "신분당", "#D4003B"),
		new LineMeta("경의중앙", "경의", "#77C4A3"),
		new LineMeta("공항", "공항", "#0090D2"),
		new LineMeta("경춘", "경춘", "#0C8E72"),
		new LineMeta("우이", "우이", "#B7C452"),
		new LineMeta("서해", "서해", "#81A914"),
		new LineMeta("신림", "신림", "#6789CA"),
		new LineMeta("itx", "ITX", "#0C8E72"),
		new LineMeta("gtx", "GTX", "#9A6292")
	);

	private record LineMeta(String keyword, String emblem, String color) {}

	public static SubwayLineBadgeView from(SubwayLine line) {
		if (line == null) {
			return new SubwayLineBadgeView("", "—", "—", "#697089");
		}
		String cleanName = cleanLineName(line.name());
		String emblem = extractEmblem(cleanName, line.lineCode());
		String color = (line.color() != null && !line.color().isBlank())
			? line.color().trim()
			: defaultColorFor(cleanName);
		return new SubwayLineBadgeView(line.id(), cleanName, emblem, color);
	}

	public static SubwayLineBadgeView fromName(String rawName) {
		if (rawName == null || rawName.isBlank() || "—".equals(rawName.trim())) {
			return new SubwayLineBadgeView("", "—", "—", "#697089");
		}
		String cleanName = cleanLineName(rawName);
		String emblem = extractEmblem(cleanName, null);
		String color = defaultColorFor(cleanName);
		return new SubwayLineBadgeView("", cleanName, emblem, color);
	}

	public static List<SubwayLineBadgeView> fromCommaSeparated(String commaSeparatedNames) {
		if (commaSeparatedNames == null || commaSeparatedNames.isBlank() || "—".equals(commaSeparatedNames.trim())) {
			return List.of();
		}
		return Arrays.stream(commaSeparatedNames.split(","))
			.map(String::trim)
			.filter(s -> !s.isEmpty() && !"—".equals(s))
			.map(SubwayLineBadgeView::fromName)
			.toList();
	}

	public static String cleanLineName(String raw) {
		if (raw == null) {
			return "";
		}
		String cleaned = raw.trim();
		if (cleaned.startsWith("수도권 ")) {
			cleaned = cleaned.substring("수도권 ".length()).trim();
		}
		return cleaned;
	}

	public static String extractEmblem(String cleanName, String lineCode) {
		if (lineCode != null && !lineCode.isBlank() && lineCode.length() <= 3) {
			return lineCode.trim();
		}
		Matcher m = NUMBERED_LINE.matcher(cleanName);
		if (m.find()) {
			return m.group(1);
		}
		String lower = cleanName.toLowerCase(Locale.ROOT);
		for (LineMeta meta : NAMED_LINES) {
			if (lower.contains(meta.keyword)) {
				return meta.emblem;
			}
		}
		return cleanName.length() <= 2 ? cleanName : cleanName.substring(0, 2);
	}

	public static String defaultColorFor(String cleanName) {
		Matcher m = NUMBERED_LINE.matcher(cleanName);
		if (m.find() && NUMBERED_COLORS.containsKey(m.group(1))) {
			return NUMBERED_COLORS.get(m.group(1));
		}
		String lower = cleanName.toLowerCase(Locale.ROOT);
		for (LineMeta meta : NAMED_LINES) {
			if (lower.contains(meta.keyword)) {
				return meta.color;
			}
		}
		return "#5C6BC0";
	}
}
