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

	/**
	 * provider 수신 시각만 교체한 복사본. 나머지 필드(raw 원본 포함)는 보존한다.
	 */
	public RealtimeArrival withProviderReceivedAt(String providerReceivedAt) {
		return new RealtimeArrival(
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
			rawDestination,
			rawDirection,
			rawServicePattern
		);
	}

	/**
	 * ETA(초)와 안내 문구만 교체한 복사본. 나머지 필드는 보존한다.
	 */
	public RealtimeArrival withEtaAndMessage(Integer etaSeconds, String message) {
		return new RealtimeArrival(
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
			rawDestination,
			rawDirection,
			rawServicePattern
		);
	}

	/**
	 * 정규화된 목적지·방향·운행패턴으로 교체한 복사본. raw 원본은 그대로 보존한다.
	 */
	public RealtimeArrival withCanonical(String destination, String direction, String servicePattern) {
		return new RealtimeArrival(
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
			rawDestination,
			rawDirection,
			rawServicePattern
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
