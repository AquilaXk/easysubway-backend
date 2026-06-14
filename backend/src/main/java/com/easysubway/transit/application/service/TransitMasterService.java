package com.easysubway.transit.application.service;

import com.easysubway.transit.application.port.in.StationSearchCommand;
import com.easysubway.transit.application.port.in.TransitMasterAdminUseCase;
import com.easysubway.transit.application.port.in.TransitMasterQueryUseCase;
import com.easysubway.transit.application.port.in.UpdateAccessibilityFacilityStatusCommand;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.application.port.out.SaveAccessibilityFacilityStatusPort;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityNotFoundException;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.InvalidAccessibilityFacilityException;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.StationLineSummary;
import com.easysubway.transit.domain.StationNotFoundException;
import com.easysubway.transit.domain.StationWithLines;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TransitMasterService implements TransitMasterQueryUseCase, TransitMasterAdminUseCase {

	private final LoadTransitMasterPort loadTransitMasterPort;
	private final SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort;
	private final Clock clock;

	@Autowired
	public TransitMasterService(
		LoadTransitMasterPort loadTransitMasterPort,
		SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort
	) {
		this(loadTransitMasterPort, saveAccessibilityFacilityStatusPort, Clock.systemDefaultZone());
	}

	public TransitMasterService(
		LoadTransitMasterPort loadTransitMasterPort,
		SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort,
		Clock clock
	) {
		this.loadTransitMasterPort = loadTransitMasterPort;
		this.saveAccessibilityFacilityStatusPort = saveAccessibilityFacilityStatusPort;
		this.clock = clock;
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
		// 역 검색 결과에는 운영 중인 역과 노선만 포함해 사용자에게 닫힌 노선 선택지를 노출하지 않는다.
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

	@Override
	public AccessibilityFacility updateFacilityStatus(UpdateAccessibilityFacilityStatusCommand command) {
		requireFacilityStatus(command);
		requireUpdater(command);

		AccessibilityFacility facility = loadAccessibilityFacility(command.facilityId());
		LocalDate updatedAt = LocalDate.now(clock);
		// 관리자 직접 수정은 역 상세와 경로 추천이 함께 사용하는 운영 상태의 기준값을 바꾼다.
		saveAccessibilityFacilityStatusPort.saveFacilityStatus(facility.id(), command.status(), updatedAt);
		return withStatus(facility, command.status(), updatedAt);
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
		// 역-노선 연결 데이터가 있어도 노선 자체가 비활성이면 응답에서 제외한다.
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

	private void requireFacilityStatus(UpdateAccessibilityFacilityStatusCommand command) {
		if (command.status() == null) {
			throw new InvalidAccessibilityFacilityException("시설 상태를 선택해야 합니다.");
		}
	}

	private void requireUpdater(UpdateAccessibilityFacilityStatusCommand command) {
		if (command.updatedBy() == null || command.updatedBy().isBlank()) {
			throw new InvalidAccessibilityFacilityException("수정자 식별자가 필요합니다.");
		}
	}

	private AccessibilityFacility loadAccessibilityFacility(String facilityId) {
		return loadTransitMasterPort.loadAccessibilityFacilities()
			.stream()
			.filter(facility -> facility.id().equals(facilityId))
			.findFirst()
			.orElseThrow(AccessibilityFacilityNotFoundException::new);
	}

	private AccessibilityFacility withStatus(
		AccessibilityFacility facility,
		AccessibilityFacilityStatus status,
		LocalDate updatedAt
	) {
		return new AccessibilityFacility(
			facility.id(),
			facility.stationId(),
			facility.exitId(),
			facility.type(),
			facility.name(),
			facility.floorFrom(),
			facility.floorTo(),
			facility.latitude(),
			facility.longitude(),
			facility.description(),
			status,
			facility.dataConfidence(),
			updatedAt
		);
	}
}
