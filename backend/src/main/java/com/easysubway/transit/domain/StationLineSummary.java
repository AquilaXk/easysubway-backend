package com.easysubway.transit.domain;

public record StationLineSummary(
	String id,
	String operatorId,
	String name,
	String color,
	String stationCode,
	int sequence,
	String platformInfo
) {

	public static StationLineSummary of(SubwayLine line, StationLine stationLine) {
		return new StationLineSummary(
			line.id(),
			line.operatorId(),
			line.name(),
			line.color(),
			stationLine.stationCode(),
			stationLine.sequence(),
			stationLine.platformInfo()
		);
	}
}
