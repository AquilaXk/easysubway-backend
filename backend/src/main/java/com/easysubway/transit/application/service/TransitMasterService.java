package com.easysubway.transit.application.service;

import com.easysubway.transit.application.port.in.StationSearchCommand;
import com.easysubway.transit.application.port.in.TransitMasterQueryUseCase;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.StationLineSummary;
import com.easysubway.transit.domain.StationNotFoundException;
import com.easysubway.transit.domain.StationWithLines;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class TransitMasterService implements TransitMasterQueryUseCase {

	private final LoadTransitMasterPort loadTransitMasterPort;

	public TransitMasterService(LoadTransitMasterPort loadTransitMasterPort) {
		this.loadTransitMasterPort = loadTransitMasterPort;
	}

	@Override
	public List<TransitOperator> listOperators() {
		return loadTransitMasterPort.loadOperators()
			.stream()
			.filter(TransitOperator::active)
			.toList();
	}

	@Override
	public List<SubwayLine> listLines(String operatorId) {
		return loadTransitMasterPort.loadLines()
			.stream()
			.filter(SubwayLine::active)
			.filter(line -> operatorId == null || operatorId.isBlank() || line.operatorId().equals(operatorId))
			.toList();
	}

	@Override
	public List<StationWithLines> searchStations(StationSearchCommand command) {
		return loadTransitMasterPort.loadStations()
			.stream()
			.filter(Station::active)
			.filter(station -> station.matches(command.query()))
			.map(this::withLines)
			.filter(station -> hasLine(station, command.lineId()))
			.toList();
	}

	@Override
	public StationWithLines getStation(String stationId) {
		return withLines(loadActiveStation(stationId));
	}

	@Override
	public List<StationExit> listStationExits(String stationId) {
		loadActiveStation(stationId);
		return loadTransitMasterPort.loadStationExits()
			.stream()
			.filter(exit -> exit.stationId().equals(stationId))
			.toList();
	}

	@Override
	public List<AccessibilityFacility> listStationFacilities(String stationId) {
		loadActiveStation(stationId);
		return loadTransitMasterPort.loadAccessibilityFacilities()
			.stream()
			.filter(facility -> facility.stationId().equals(stationId))
			.toList();
	}

	private Station loadActiveStation(String stationId) {
		return loadTransitMasterPort.loadStations()
			.stream()
			.filter(Station::active)
			.filter(station -> station.id().equals(stationId))
			.findFirst()
			.orElseThrow(StationNotFoundException::new);
	}

	private StationWithLines withLines(Station station) {
		Map<String, SubwayLine> linesById = loadTransitMasterPort.loadLines()
			.stream()
			.filter(SubwayLine::active)
			.collect(Collectors.toMap(SubwayLine::id, Function.identity()));

		List<StationLineSummary> lines = loadTransitMasterPort.loadStationLines()
			.stream()
			.filter(stationLine -> stationLine.stationId().equals(station.id()))
			.filter(stationLine -> linesById.containsKey(stationLine.lineId()))
			.map(stationLine -> toSummary(stationLine, linesById))
			.toList();

		return new StationWithLines(station, lines);
	}

	private StationLineSummary toSummary(StationLine stationLine, Map<String, SubwayLine> linesById) {
		SubwayLine line = linesById.get(stationLine.lineId());
		if (line == null) {
			throw new IllegalStateException("Station line references missing line: " + stationLine.lineId());
		}
		return StationLineSummary.of(line, stationLine);
	}

	private boolean hasLine(StationWithLines station, String lineId) {
		if (lineId == null || lineId.isBlank()) {
			return true;
		}
		return station.lines()
			.stream()
			.anyMatch(line -> line.id().equals(lineId));
	}
}
