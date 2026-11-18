package com.easysubway.transit.application.port.in;

import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.NearbyStation;
import com.easysubway.transit.domain.RouteEdge;
import com.easysubway.transit.domain.RouteNode;
import com.easysubway.transit.domain.StationWithLines;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLayoutSource;
import com.easysubway.transit.domain.SimplifiedStationLayout;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import com.easysubway.transit.domain.TransitRegionSummary;
import java.util.List;
import java.util.Map;

public interface TransitMasterQueryUseCase {

	List<TransitRegionSummary> listRegions();

	List<TransitOperator> listOperators();

	List<SubwayLine> listLines(String operatorId);

	List<StationWithLines> searchStations(StationSearchCommand command);

	List<NearbyStation> searchNearbyStations(NearbyStationSearchCommand command);

	Map<String, StationMasterDataCounts> countStationMasterDataByStationId();

	/** 확인이 필요한(needsAttention) 접근성 시설 수를 역 단위로 집계한다(역 목록 "확인 필요 시설" 뱃지). */
	Map<String, Long> countAttentionFacilitiesByStation();

	StationWithLines getStation(String stationId);

	List<StationExit> listStationExits(String stationId);

	List<AccessibilityFacility> listStationFacilities(String stationId);

	List<StationLayoutSource> listStationLayoutSources(String stationId);

	List<SimplifiedStationLayout> listSimplifiedStationLayouts(String stationId);

	List<RouteNode> listRouteNodes(String stationId);

	List<RouteEdge> listRouteEdges(String stationId);
}
