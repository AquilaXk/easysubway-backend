package com.easysubway.datapack.adapter.in.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record DatapackAdminListQuery(
	String query,
	String status,
	String candidateId,
	String sourceSnapshotId,
	String sort
) {

	static DatapackAdminListQuery of(
		String query,
		String status,
		String candidateId,
		String sourceSnapshotId,
		String sort
	) {
		return new DatapackAdminListQuery(
			clean(query),
			cleanStatus(status),
			clean(candidateId),
			clean(sourceSnapshotId),
			clean(sort)
		);
	}

	public String queryValue() {
		return valueOrEmpty(query);
	}

	public String statusValue() {
		return hasText(status) ? status : "ALL";
	}

	public String candidateValue() {
		return valueOrEmpty(candidateId);
	}

	public String sourceSnapshotValue() {
		return valueOrEmpty(sourceSnapshotId);
	}

	public String sortValue() {
		return valueOrEmpty(sort);
	}

	public boolean hasQuery() {
		return hasText(query);
	}

	public boolean hasStatus() {
		return hasText(status) && !"ALL".equals(status);
	}

	public boolean hasCandidate() {
		return hasText(candidateId);
	}

	public boolean hasSourceSnapshot() {
		return hasText(sourceSnapshotId);
	}

	public boolean hasSort() {
		return hasText(sort);
	}

	public boolean active() {
		return hasQuery() || hasStatus() || hasCandidate() || hasSourceSnapshot() || hasSort();
	}

	public boolean selectedStatus(String value) {
		if ("ALL".equals(value)) {
			return !hasStatus();
		}
		return value != null && value.equals(status);
	}

	public boolean selectedSort(String value) {
		if (value == null || value.isBlank()) {
			return !hasSort();
		}
		return value.equals(sort);
	}

	public boolean matchesCandidate(String value) {
		return !hasCandidate() || same(candidateId, value);
	}

	public boolean matchesSourceSnapshot(String value) {
		if (!hasSourceSnapshot()) {
			return true;
		}
		for (String snapshotId : sourceSnapshotIds()) {
			if (same(snapshotId, value)) {
				return true;
			}
		}
		return false;
	}

	public List<String> sourceSnapshotIds() {
		if (!hasSourceSnapshot()) {
			return List.of();
		}
		List<String> snapshotIds = new ArrayList<>();
		for (String snapshotId : sourceSnapshotId.split(",")) {
			String cleaned = clean(snapshotId);
			if (cleaned != null) {
				snapshotIds.add(cleaned);
			}
		}
		return snapshotIds;
	}

	public boolean matchesText(String... values) {
		if (!hasQuery()) {
			return true;
		}
		String needle = query.toLowerCase(Locale.ROOT);
		for (String value : values) {
			if (value != null && value.toLowerCase(Locale.ROOT).contains(needle)) {
				return true;
			}
		}
		return false;
	}

	public Map<String, String> params() {
		Map<String, String> params = new LinkedHashMap<>();
		put(params, "query", query);
		if (hasStatus()) {
			put(params, "status", status);
		}
		put(params, "candidateId", candidateId);
		put(params, "sourceSnapshotId", sourceSnapshotId);
		put(params, "sort", sort);
		return params;
	}

	public String contextLabel() {
		if (hasCandidate() && hasSourceSnapshot()) {
			return "후보 " + candidateId + " · 스냅샷 " + sourceSnapshotId;
		}
		if (hasCandidate()) {
			return "후보 " + candidateId;
		}
		if (hasSourceSnapshot()) {
			return "스냅샷 " + sourceSnapshotId;
		}
		return "";
	}

	static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	static boolean same(String left, String right) {
		return hasText(left) && hasText(right) && left.equals(right);
	}

	private static void put(Map<String, String> params, String key, String value) {
		if (hasText(value)) {
			params.put(key, value);
		}
	}

	private static String clean(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}

	private static String cleanStatus(String value) {
		String cleaned = clean(value);
		return cleaned == null ? null : cleaned.toUpperCase(Locale.ROOT);
	}

	private static String valueOrEmpty(String value) {
		return value == null ? "" : value;
	}
}
