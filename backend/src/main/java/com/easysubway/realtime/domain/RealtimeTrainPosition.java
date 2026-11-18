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

	/** provider 수신 시각만 교체한 복사본. */
	public RealtimeTrainPosition withProviderReceivedAt(String providerReceivedAt) {
		return new RealtimeTrainPosition(
			lineId,
			stationName,
			trainNo,
			trainStatus,
			direction,
			destination,
			providerReceivedAt
		);
	}
}
