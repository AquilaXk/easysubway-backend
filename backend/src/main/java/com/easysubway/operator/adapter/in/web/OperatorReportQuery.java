package com.easysubway.operator.adapter.in.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

record OperatorReportQuery(
	String q,
	LocalDate from,
	LocalDate to,
	String sort,
	String direction
) {

	static OperatorReportQuery of(String q, LocalDate from, LocalDate to, String sort, String direction) {
		return new OperatorReportQuery(
			q == null ? "" : q.trim(),
			from,
			to,
			sort == null || sort.isBlank() ? "" : sort,
			"desc".equalsIgnoreCase(direction) ? "desc" : "asc"
		);
	}

	static OperatorReportQuery empty() {
		return of(null, null, null, null, null);
	}

	boolean matches(String... values) {
		if (q == null || q.isBlank()) {
			return true;
		}
		String needle = q.toLowerCase(Locale.ROOT);
		for (String value : values) {
			if (value != null && value.toLowerCase(Locale.ROOT).contains(needle)) {
				return true;
			}
		}
		return false;
	}

	boolean includesDateLabel(String label) {
		if (from == null && to == null) {
			return true;
		}
		LocalDate date = parseDate(label);
		if (date == null) {
			return false;
		}
		if (from != null && date.isBefore(from)) {
			return false;
		}
		return to == null || !date.isAfter(to);
	}

	public String ariaSort(String column) {
		if (!column.equals(sort)) {
			return "none";
		}
		return "desc".equals(direction) ? "descending" : "ascending";
	}

	public String nextDirection(String column) {
		if (column.equals(sort) && "asc".equals(direction)) {
			return "desc";
		}
		return "asc";
	}

	private static LocalDate parseDate(String label) {
		if (label == null || label.length() < 10) {
			return null;
		}
		try {
			return LocalDate.parse(label.substring(0, 10));
		} catch (DateTimeParseException exception) {
			return null;
		}
	}
}
