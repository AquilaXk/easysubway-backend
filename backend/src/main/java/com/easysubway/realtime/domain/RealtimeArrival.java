package com.easysubway.realtime.domain;

public record RealtimeArrival(
	String lineId,
	String stationName,
	String destination,
	String direction,
	String trainNo,
	Integer etaSeconds,
	String message,
	String positionMessage,
	String providerReceivedAt
) {
}
