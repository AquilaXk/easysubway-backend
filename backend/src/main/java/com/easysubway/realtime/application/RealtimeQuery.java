package com.easysubway.realtime.application;

public record RealtimeQuery(
	String stationId,
	String lineId,
	String providerLineId,
	String stationQueryName,
	String lineName
) {
}
