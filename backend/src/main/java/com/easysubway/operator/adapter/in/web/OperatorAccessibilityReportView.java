package com.easysubway.operator.adapter.in.web;

import java.util.ArrayList;
import java.util.List;

public record OperatorAccessibilityReportView(
	int totalStations,
	int totalFacilities,
	long needsVerificationFacilityCount,
	long delayedFacilityStatusCount,
	long missingStationVerificationDateCount,
	List<QualityCountRow> stationQualityRows,
	List<RegionQualityRow> regionQualityRows,
	List<StationAccessibilityScoreRow> stationAccessibilityScoreRows,
	List<AccessibilityImprovementPriorityRow> accessibilityImprovementPriorityRows
) {

	public OperatorAccessibilityReportView {
		stationQualityRows = stationQualityRows == null ? null : new ArrayList<>(stationQualityRows);
		regionQualityRows = regionQualityRows == null ? null : new ArrayList<>(regionQualityRows);
		stationAccessibilityScoreRows = stationAccessibilityScoreRows == null
			? null
			: new ArrayList<>(stationAccessibilityScoreRows);
		accessibilityImprovementPriorityRows = accessibilityImprovementPriorityRows == null
			? null
			: new ArrayList<>(accessibilityImprovementPriorityRows);
	}

	@Override
	public List<QualityCountRow> stationQualityRows() {
		return stationQualityRows == null ? null : new ArrayList<>(stationQualityRows);
	}

	@Override
	public List<RegionQualityRow> regionQualityRows() {
		return regionQualityRows == null ? null : new ArrayList<>(regionQualityRows);
	}

	@Override
	public List<StationAccessibilityScoreRow> stationAccessibilityScoreRows() {
		return stationAccessibilityScoreRows == null ? null : new ArrayList<>(stationAccessibilityScoreRows);
	}

	@Override
	public List<AccessibilityImprovementPriorityRow> accessibilityImprovementPriorityRows() {
		return accessibilityImprovementPriorityRows == null
			? null
			: new ArrayList<>(accessibilityImprovementPriorityRows);
	}

	public record QualityCountRow(String label, String description, long count) {
	}

	public record RegionQualityRow(
		String name,
		int operatorCount,
		int lineCount,
		int stationCount,
		long level1Count,
		long level2Count,
		long level3Count,
		long level4Count
	) {
	}

	public record StationAccessibilityScoreRow(
		String stationName,
		String region,
		int score,
		List<String> reasons
	) {

		public StationAccessibilityScoreRow {
			reasons = reasons == null ? null : new ArrayList<>(reasons);
		}

		@Override
		public List<String> reasons() {
			return reasons == null ? null : new ArrayList<>(reasons);
		}

		public String reasonText() {
			return String.join(", ", reasons);
		}
	}

	public record AccessibilityImprovementPriorityRow(
		String stationName,
		String facilityName,
		int priorityScore,
		List<String> reasons
	) {

		public AccessibilityImprovementPriorityRow {
			reasons = reasons == null ? null : new ArrayList<>(reasons);
		}

		@Override
		public List<String> reasons() {
			return reasons == null ? null : new ArrayList<>(reasons);
		}

		public String reasonText() {
			return String.join(", ", reasons);
		}
	}
}
