package com.easysubway.operator.adapter.in.web;

import java.util.List;

record OperatorRepeatedBrokenFacilitiesView(
	int totalRepeatedFacilityCount,
	List<RepeatedBrokenFacilityRow> rows
) {

	record RepeatedBrokenFacilityRow(
		String stationName,
		String facilityName,
		String statusLabel,
		long reportCount
	) {
	}
}
