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
	String providerReceivedAt,
	String servicePattern,
	String rawDestination,
	String rawDirection,
	String rawServicePattern
) {
	public RealtimeArrival {
		rawDestination = rawDestination == null ? destination : rawDestination;
		rawDirection = rawDirection == null ? direction : rawDirection;
		rawServicePattern = rawServicePattern == null ? servicePattern : rawServicePattern;
	}

	public RealtimeArrival(
		String lineId,
		String stationName,
		String destination,
		String direction,
		String trainNo,
		Integer etaSeconds,
		String message,
		String positionMessage,
		String providerReceivedAt,
		String servicePattern
	) {
		this(
			lineId,
			stationName,
			destination,
			direction,
			trainNo,
			etaSeconds,
			message,
			positionMessage,
			providerReceivedAt,
			servicePattern,
			destination,
			direction,
			servicePattern
		);
	}

	public RealtimeArrival(
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
		this(
			lineId,
			stationName,
			destination,
			direction,
			trainNo,
			etaSeconds,
			message,
			positionMessage,
			providerReceivedAt,
			null,
			destination,
			direction,
			null
		);
	}
}
