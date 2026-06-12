package com.easysubway.transit.application.port.in;

import com.easysubway.transit.domain.StationWithLines;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import java.util.List;

public interface TransitMasterQueryUseCase {

	List<TransitOperator> listOperators();

	List<SubwayLine> listLines(String operatorId);

	List<StationWithLines> searchStations(StationSearchCommand command);

	StationWithLines getStation(String stationId);
}
