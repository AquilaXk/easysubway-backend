package com.easysubway.route.application.service;

import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.SubwayLine;

record TransferRoute(
	SubwayLine firstLine,
	StationLine origin,
	StationLine transferOriginLine,
	SubwayLine secondLine,
	StationLine transferDestinationLine,
	StationLine destination,
	Station transferStation
) {

	int firstSegmentStopCount() {
		return Math.abs(origin.sequence() - transferOriginLine.sequence());
	}

	int secondSegmentStopCount() {
		return Math.abs(transferDestinationLine.sequence() - destination.sequence());
	}

	int stopCount() {
		return firstSegmentStopCount() + secondSegmentStopCount();
	}
}
