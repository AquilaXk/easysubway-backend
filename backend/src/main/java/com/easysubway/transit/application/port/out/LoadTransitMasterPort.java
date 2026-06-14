package com.easysubway.transit.application.port.out;

import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import java.util.List;
import java.util.Optional;

public interface LoadTransitMasterPort {

	List<TransitOperator> loadOperators();

	List<SubwayLine> loadLines();

	List<Station> loadStations();

	List<StationLine> loadStationLines();

	List<StationExit> loadStationExits();

	List<AccessibilityFacility> loadAccessibilityFacilities();

	default Optional<Station> loadStation(String stationId) {
		return loadStations()
			.stream()
			.filter(station -> station.id().equals(stationId))
			.findFirst();
	}

	default Optional<AccessibilityFacility> loadAccessibilityFacility(String facilityId) {
		return loadAccessibilityFacilities()
			.stream()
			.filter(facility -> facility.id().equals(facilityId))
			.findFirst();
	}
}
