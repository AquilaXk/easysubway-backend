package com.easysubway.transit.application.port.out;

import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.RouteEdge;
import com.easysubway.transit.domain.RouteNode;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLayoutSource;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.SimplifiedStationLayout;
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

	default List<StationLayoutSource> loadStationLayoutSources() {
		throw new UnsupportedOperationException("Station layout source loading is not implemented.");
	}

	default List<SimplifiedStationLayout> loadSimplifiedStationLayouts() {
		throw new UnsupportedOperationException("Simplified station layout loading is not implemented.");
	}

	default List<RouteNode> loadRouteNodes() {
		throw new UnsupportedOperationException("Route node loading is not implemented.");
	}

	default List<RouteEdge> loadRouteEdges() {
		throw new UnsupportedOperationException("Route edge loading is not implemented.");
	}

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
