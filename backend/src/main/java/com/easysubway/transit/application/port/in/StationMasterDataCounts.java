package com.easysubway.transit.application.port.in;

public record StationMasterDataCounts(
	int exitCount,
	int facilityCount,
	int layoutSourceCount,
	int simplifiedLayoutCount,
	int routeNodeCount,
	int routeEdgeCount
) {

	public static StationMasterDataCounts empty() {
		return new StationMasterDataCounts(0, 0, 0, 0, 0, 0);
	}
}
