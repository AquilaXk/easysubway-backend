package com.easysubway.transit.application.port.out;

import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import java.util.List;

public interface LoadTransitMasterPort {

	List<TransitOperator> loadOperators();

	List<SubwayLine> loadLines();

	List<Station> loadStations();

	List<StationLine> loadStationLines();
}
