package com.easysubway.operator.adapter.in.web;

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

		public String reasonText() {
			return String.join(", ", reasons);
		}
	}
}
