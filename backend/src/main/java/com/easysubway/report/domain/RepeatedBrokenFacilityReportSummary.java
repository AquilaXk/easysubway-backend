package com.easysubway.report.domain;

public record RepeatedBrokenFacilityReportSummary(
	String stationId,
	String facilityId,
	long reportCount
) {
}
