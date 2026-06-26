package com.easysubway.realtime.domain;

public record RealtimeTrainPosition(
	String lineId,
	String stationName,
	String trainNo,
	String trainStatus,
	String direction,
	String destination,
	String providerReceivedAt
) {
}
