package com.easysubway.transit.adapter.in.web;

import com.easysubway.transit.domain.SubwayLine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
		if (cleanName.contains("수인분당")) {
			return "수인";
		}
		if (cleanName.contains("신분당")) {
			return "신분당";
		}
		if (cleanName.contains("경의중앙")) {
			return "경의";
		}
		if (cleanName.contains("공항철도") || cleanName.contains("공항")) {
			return "공항";
		}
		if (cleanName.contains("경춘")) {
			return "경춘";
		}
		if (cleanName.contains("우이신설") || cleanName.contains("우이")) {
			return "우이";
		}
		if (cleanName.contains("서해")) {
			return "서해";
		}
		if (cleanName.contains("신림")) {
			return "신림";
		}
		if (cleanName.contains("ITX-청춘") || cleanName.contains("ITX")) {
			return "ITX";
		}
		if (cleanName.contains("GTX-A") || cleanName.contains("GTX")) {
			return "GTX";
		}
		if (cleanName.length() <= 2) {
			return cleanName;
		}
		return cleanName.substring(0, 2);
	}

	public static String defaultColorFor(String cleanName) {
		String lower = cleanName.toLowerCase(Locale.ROOT);
		if (lower.contains("1호선")) {
			return "#0052A4";
		}
		if (lower.contains("2호선")) {
			return "#00A84D";
		}
		if (lower.contains("3호선")) {
			return "#EF7C1C";
		}
		if (lower.contains("4호선")) {
			return "#00A5DE";
		}
		if (lower.contains("5호선")) {
			return "#996CAC";
		}
		if (lower.contains("6호선")) {
			return "#CD7C2F";
		}
		if (lower.contains("7호선")) {
			return "#747F00";
		}
		if (lower.contains("8호선")) {
			return "#E6186C";
		}
		if (lower.contains("9호선")) {
			return "#BB8336";
		}
		if (lower.contains("수인분당")) {
			return "#F5A200";
		}
		if (lower.contains("신분당")) {
			return "#D4003B";
		}
		if (lower.contains("경의중앙")) {
			return "#77C4A3";
		}
		if (lower.contains("공항")) {
			return "#0090D2";
		}
		if (lower.contains("경춘")) {
			return "#0C8E72";
		}
		if (lower.contains("우이")) {
			return "#B7C452";
		}
		if (lower.contains("서해")) {
			return "#81A914";
		}
		if (lower.contains("신림")) {
			return "#6789CA";
		}
		if (lower.contains("itx")) {
			return "#0C8E72";
		}
		if (lower.contains("gtx")) {
			return "#9A6292";
		}
		return "#5C6BC0";
	}
}
