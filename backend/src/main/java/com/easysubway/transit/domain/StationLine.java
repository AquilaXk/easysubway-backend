package com.easysubway.transit.domain;

public record StationLine(
	String stationId,
	String lineId,
	String stationCode,
	int sequence,
	String platformInfo
) {
}
