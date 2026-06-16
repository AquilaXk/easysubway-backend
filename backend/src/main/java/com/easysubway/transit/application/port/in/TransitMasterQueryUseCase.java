package com.easysubway.transit.application.port.in;

import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.NearbyStation;
import com.easysubway.transit.domain.StationWithLines;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLayoutSource;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import com.easysubway.transit.domain.TransitRegionSummary;
import java.util.List;

public interface TransitMasterQueryUseCase {

	List<TransitRegionSummary> listRegions();

	List<TransitOperator> listOperators();

	List<SubwayLine> listLines(String operatorId);

	List<StationWithLines> searchStations(StationSearchCommand command);

	List<NearbyStation> searchNearbyStations(NearbyStationSearchCommand command);

	StationWithLines getStation(String stationId);

	List<StationExit> listStationExits(String stationId);

	List<AccessibilityFacility> listStationFacilities(String stationId);

	List<StationLayoutSource> listStationLayoutSources(String stationId);
}
