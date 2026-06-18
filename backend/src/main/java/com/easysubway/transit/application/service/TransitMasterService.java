package com.easysubway.transit.application.service;

import com.easysubway.transit.application.port.in.CreateAccessibilityFacilityCommand;
import com.easysubway.transit.application.port.in.NearbyStationSearchCommand;
import com.easysubway.transit.application.port.in.StationSearchCommand;
import com.easysubway.transit.application.port.in.TransitMasterAdminUseCase;
import com.easysubway.transit.application.port.in.TransitMasterQueryUseCase;
import com.easysubway.transit.application.port.in.UpdateAccessibilityFacilityCommand;
import com.easysubway.transit.application.port.in.UpdateAccessibilityFacilityStatusCommand;
import com.easysubway.notification.application.port.in.FacilityStatusAlertUseCase;
import com.easysubway.notification.application.port.in.FacilityStatusChangedAlertCommand;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.application.port.out.SaveAccessibilityFacilityStatusPort;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityNotFoundException;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.InvalidAccessibilityFacilityException;
import com.easysubway.transit.domain.NearbyStation;
import com.easysubway.transit.domain.RouteEdge;
import com.easysubway.transit.domain.RouteNode;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLayoutSource;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.StationLineSummary;
import com.easysubway.transit.domain.StationNotFoundException;
import com.easysubway.transit.domain.StationWithLines;
import com.easysubway.transit.domain.SimplifiedStationLayout;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import com.easysubway.transit.domain.TransitRegionSummary;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TransitMasterService implements TransitMasterQueryUseCase, TransitMasterAdminUseCase {

	private static final double EARTH_RADIUS_METERS = 6_371_000.0;

	private final LoadTransitMasterPort loadTransitMasterPort;
	private final SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort;
	private final FacilityStatusAlertUseCase facilityStatusAlertUseCase;
	private final Clock clock;

	@Autowired
	public TransitMasterService(
		LoadTransitMasterPort loadTransitMasterPort,
		SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort,
		FacilityStatusAlertUseCase facilityStatusAlertUseCase
	) {
		this(loadTransitMasterPort, saveAccessibilityFacilityStatusPort, facilityStatusAlertUseCase, Clock.systemDefaultZone());
	}

	public TransitMasterService(
		LoadTransitMasterPort loadTransitMasterPort,
		SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort
	) {
		this(loadTransitMasterPort, saveAccessibilityFacilityStatusPort, command -> {
		}, Clock.systemDefaultZone());
	}

	public TransitMasterService(
		LoadTransitMasterPort loadTransitMasterPort,
		SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort,
		Clock clock
	) {
		this(loadTransitMasterPort, saveAccessibilityFacilityStatusPort, command -> {
		}, clock);
	}

	public TransitMasterService(
		LoadTransitMasterPort loadTransitMasterPort,
		SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort,
		FacilityStatusAlertUseCase facilityStatusAlertUseCase,
		Clock clock
	) {
		this.loadTransitMasterPort = loadTransitMasterPort;
		this.saveAccessibilityFacilityStatusPort = saveAccessibilityFacilityStatusPort;
		this.facilityStatusAlertUseCase = facilityStatusAlertUseCase;
		this.clock = clock;
	}

	@Override
	public List<TransitRegionSummary> listRegions() {
		List<TransitOperator> operators = activeOperators();
		List<SubwayLine> lines = activeLines();
		List<Station> stations = activeStations();

		return Stream.concat(
				Stream.concat(operators.stream().map(TransitOperator::region), lines.stream().map(SubwayLine::region)),
				stations.stream().map(Station::region)
			)
			.filter(region -> region != null && !region.isBlank())
			.distinct()
			.sorted()
			.map(region -> summarizeRegion(region, operators, lines, stations))
			.toList();
	}

	@Override
	public List<TransitOperator> listOperators() {
		return activeOperators();
	}

	@Override
	public List<SubwayLine> listLines(String operatorId) {
		return activeLines()
			.stream()
			.filter(line -> operatorId == null || operatorId.isBlank() || line.operatorId().equals(operatorId))
			.toList();
	}

	@Override
	public List<StationWithLines> searchStations(StationSearchCommand command) {
		// 역 검색 결과에는 운영 중인 역과 노선만 포함해 사용자에게 닫힌 노선 선택지를 노출하지 않는다.
		Map<String, SubwayLine> linesById = activeLinesById();
		Map<String, List<StationLine>> stationLinesByStationId = activeStationLinesByStationId(linesById);
		return loadTransitMasterPort.loadStations()
			.stream()
			.filter(Station::active)
			.filter(station -> station.matches(command.query()))
			.map(station -> withLines(station, linesById, stationLinesByStationId))
			.filter(station -> hasLine(station, command.lineId()))
			.sorted(stationSearchResultComparator())
			.toList();
	}

	@Override
	public List<NearbyStation> searchNearbyStations(NearbyStationSearchCommand command) {
		Map<String, SubwayLine> linesById = activeLinesById();
		Map<String, List<StationLine>> stationLinesByStationId = activeStationLinesByStationId(linesById);
		return loadTransitMasterPort.loadStations()
			.stream()
			.filter(Station::active)
			.map(station -> new NearbyStation(
				withLines(station, linesById, stationLinesByStationId),
				distanceMeters(command, station)
			))
			.filter(nearbyStation -> nearbyStation.distanceMeters() <= command.radiusMeters())
			.sorted(Comparator.comparingInt(NearbyStation::distanceMeters))
			.limit(command.limit())
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
	public List<StationLayoutSource> listStationLayoutSources(String stationId) {
		loadActiveStation(stationId);
		return loadTransitMasterPort.loadStationLayoutSources()
			.stream()
			.filter(source -> source.stationId().equals(stationId))
			.toList();
	}

	@Override
	public List<SimplifiedStationLayout> listSimplifiedStationLayouts(String stationId) {
		loadActiveStation(stationId);
		return loadTransitMasterPort.loadSimplifiedStationLayouts()
			.stream()
			.filter(layout -> layout.stationId().equals(stationId))
			.toList();
	}

	@Override
	public List<RouteNode> listRouteNodes(String stationId) {
		loadActiveStation(stationId);
		return loadTransitMasterPort.loadRouteNodes()
			.stream()
			.filter(node -> node.stationId().equals(stationId))
			.toList();
	}

	@Override
	public List<RouteEdge> listRouteEdges(String stationId) {
		loadActiveStation(stationId);
		return loadTransitMasterPort.loadRouteEdges()
			.stream()
			.filter(edge -> edge.stationId().equals(stationId))
			.toList();
	}

	@Override
	public AccessibilityFacility createAccessibilityFacility(CreateAccessibilityFacilityCommand command) {
		requireFacilityId(command.id());
		requireFacilityDetails(
			command.stationId(),
			command.exitId(),
			command.type(),
			command.name(),
			command.status(),
			command.dataConfidence(),
			command.dataSourceType(),
			command.updatedBy()
		);
		loadActiveStation(command.stationId());
		requireExitInStation(command.stationId(), command.exitId());
		if (loadTransitMasterPort.loadAccessibilityFacility(command.id()).isPresent()) {
			throw new InvalidAccessibilityFacilityException("이미 등록된 시설입니다.");
		}

		AccessibilityFacility facility = new AccessibilityFacility(
			command.id(),
			command.stationId(),
			blankToNull(command.exitId()),
			command.type(),
			command.name(),
			command.floorFrom(),
			command.floorTo(),
			command.latitude(),
			command.longitude(),
			command.description(),
			command.status(),
			command.dataConfidence(),
			command.dataSourceType(),
			LocalDate.now(clock)
		);
		saveAccessibilityFacilityStatusPort.saveAccessibilityFacility(facility);
		return facility;
	}

	@Override
	public AccessibilityFacility updateAccessibilityFacility(UpdateAccessibilityFacilityCommand command) {
		requireFacilityId(command.id());
		AccessibilityFacility existing = loadAccessibilityFacility(command.id());
		requireFacilityDetails(
			command.stationId(),
			command.exitId(),
			command.type(),
			command.name(),
			command.status(),
			command.dataConfidence(),
			command.dataSourceType(),
			command.updatedBy()
		);
		loadActiveStation(command.stationId());
		requireExitInStation(command.stationId(), command.exitId());

		AccessibilityFacility facility = new AccessibilityFacility(
			existing.id(),
			command.stationId(),
			blankToNull(command.exitId()),
			command.type(),
			command.name(),
			command.floorFrom(),
			command.floorTo(),
			command.latitude(),
			command.longitude(),
			command.description(),
			command.status(),
			command.dataConfidence(),
			command.dataSourceType(),
			LocalDate.now(clock)
		);
		saveAccessibilityFacilityStatusPort.saveAccessibilityFacility(facility);
		if (existing.status() != command.status()) {
			facilityStatusAlertUseCase.alertFacilityStatusChanged(
				new FacilityStatusChangedAlertCommand(facility.id(), facility.status())
			);
		}
		return facility;
	}

	@Override
	public AccessibilityFacility updateFacilityStatus(UpdateAccessibilityFacilityStatusCommand command) {
		requireFacilityStatus(command);
		requireUpdater(command);

		AccessibilityFacility facility = loadAccessibilityFacility(command.facilityId());
		LocalDate updatedAt = LocalDate.now(clock);
		// 관리자 직접 수정은 역 상세와 경로 추천이 함께 사용하는 운영 상태의 기준값을 바꾼다.
		saveAccessibilityFacilityStatusPort.saveFacilityStatus(facility.id(), command.status(), updatedAt);
		if (facility.status() != command.status()) {
			facilityStatusAlertUseCase.alertFacilityStatusChanged(
				new FacilityStatusChangedAlertCommand(facility.id(), command.status())
			);
		}
		return withStatus(facility, command.status(), updatedAt);
	}

	private TransitRegionSummary summarizeRegion(
		String region,
		List<TransitOperator> operators,
		List<SubwayLine> lines,
		List<Station> stations
	) {
		List<Station> stationsInRegion = stations.stream()
			.filter(station -> region.equals(station.region()))
			.toList();
		return new TransitRegionSummary(
			region,
			(int) operators.stream().filter(operator -> region.equals(operator.region())).count(),
			(int) lines.stream().filter(line -> region.equals(line.region())).count(),
			stationsInRegion.size(),
			dataQualityCounts(stationsInRegion)
		);
	}

	private Map<DataQualityLevel, Long> dataQualityCounts(List<Station> stations) {
		// 응답 키 순서를 데이터 품질 단계 순서와 맞춰 화면에서 안정적으로 표시할 수 있게 한다.
		Map<DataQualityLevel, Long> counts = new EnumMap<>(DataQualityLevel.class);
		for (Station station : stations) {
			counts.merge(station.dataQualityLevel(), 1L, Long::sum);
		}
		return counts;
	}

	private static Comparator<StationWithLines> stationSearchResultComparator() {
		return Comparator
			.comparingInt((StationWithLines station) -> dataQualityPriority(station.station().dataQualityLevel()))
			.thenComparing(station -> station.station().nameKo())
			.thenComparing(station -> station.station().id());
	}

	private static int dataQualityPriority(DataQualityLevel dataQualityLevel) {
		return -dataQualityLevel.ordinal();
	}

	private List<TransitOperator> activeOperators() {
		return loadTransitMasterPort.loadOperators()
			.stream()
			.filter(TransitOperator::active)
			.toList();
	}

	private List<SubwayLine> activeLines() {
		return loadTransitMasterPort.loadLines()
			.stream()
			.filter(SubwayLine::active)
			.toList();
	}

	private List<Station> activeStations() {
		return loadTransitMasterPort.loadStations()
			.stream()
			.filter(Station::active)
			.toList();
	}

	private Station loadActiveStation(String stationId) {
		return activeStations()
			.stream()
			.filter(station -> station.id().equals(stationId))
			.findFirst()
			.orElseThrow(StationNotFoundException::new);
	}

	private StationWithLines withLines(Station station) {
		// 역-노선 연결 데이터가 있어도 노선 자체가 비활성이면 응답에서 제외한다.
		return withLines(station, activeLinesById());
	}

	private Map<String, SubwayLine> activeLinesById() {
		return activeLines()
			.stream()
			.collect(Collectors.toMap(SubwayLine::id, Function.identity()));
	}

	private StationWithLines withLines(Station station, Map<String, SubwayLine> linesById) {
		return withLines(station, linesById, activeStationLinesByStationId(linesById));
	}

	private Map<String, List<StationLine>> activeStationLinesByStationId(Map<String, SubwayLine> linesById) {
		return loadTransitMasterPort.loadStationLines()
			.stream()
			.filter(stationLine -> linesById.containsKey(stationLine.lineId()))
			.collect(Collectors.groupingBy(StationLine::stationId));
	}

	private StationWithLines withLines(
		Station station,
		Map<String, SubwayLine> linesById,
		Map<String, List<StationLine>> stationLinesByStationId
	) {
		List<StationLineSummary> lines = stationLinesByStationId.getOrDefault(station.id(), List.of())
			.stream()
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

	private int distanceMeters(NearbyStationSearchCommand command, Station station) {
		double latitude1 = Math.toRadians(command.latitude().doubleValue());
		double latitude2 = Math.toRadians(station.latitude().doubleValue());
		double deltaLatitude = Math.toRadians(station.latitude().doubleValue() - command.latitude().doubleValue());
		double deltaLongitude = Math.toRadians(station.longitude().doubleValue() - command.longitude().doubleValue());

		double haversine = Math.sin(deltaLatitude / 2) * Math.sin(deltaLatitude / 2)
			+ Math.cos(latitude1) * Math.cos(latitude2)
			* Math.sin(deltaLongitude / 2) * Math.sin(deltaLongitude / 2);
		double boundedHaversine = Math.min(1.0, Math.max(0.0, haversine));
		double centralAngle = 2 * Math.atan2(Math.sqrt(boundedHaversine), Math.sqrt(1 - boundedHaversine));
		return (int) Math.round(EARTH_RADIUS_METERS * centralAngle);
	}

	private void requireFacilityStatus(UpdateAccessibilityFacilityStatusCommand command) {
		if (command.status() == null) {
			throw new InvalidAccessibilityFacilityException("시설 상태를 선택해야 합니다.");
		}
	}

	private void requireFacilityId(String facilityId) {
		if (facilityId == null || facilityId.isBlank()) {
			throw new InvalidAccessibilityFacilityException("시설 식별자가 필요합니다.");
		}
	}

	private void requireFacilityDetails(
		String stationId,
		String exitId,
		AccessibilityFacilityType type,
		String name,
		AccessibilityFacilityStatus status,
		DataConfidenceLevel dataConfidence,
		DataSourceType dataSourceType,
		String updatedBy
	) {
		if (stationId == null || stationId.isBlank()) {
			throw new InvalidAccessibilityFacilityException("역 식별자가 필요합니다.");
		}
		if (type == null) {
			throw new InvalidAccessibilityFacilityException("시설 유형을 선택해야 합니다.");
		}
		if (name == null || name.isBlank()) {
			throw new InvalidAccessibilityFacilityException("시설 이름을 입력해야 합니다.");
		}
		if (status == null) {
			throw new InvalidAccessibilityFacilityException("시설 상태를 선택해야 합니다.");
		}
		if (dataConfidence == null) {
			throw new InvalidAccessibilityFacilityException("시설 정보 신뢰도를 선택해야 합니다.");
		}
		if (dataSourceType == null) {
			throw new InvalidAccessibilityFacilityException("시설 데이터 출처를 선택해야 합니다.");
		}
		if (updatedBy == null || updatedBy.isBlank()) {
			throw new InvalidAccessibilityFacilityException("수정자 식별자가 필요합니다.");
		}
	}

	private void requireExitInStation(String stationId, String exitId) {
		if (exitId == null || exitId.isBlank()) {
			return;
		}
		boolean matched = loadTransitMasterPort.loadStationExits()
			.stream()
			.anyMatch(exit -> exit.id().equals(exitId) && exit.stationId().equals(stationId));
		if (!matched) {
			throw new InvalidAccessibilityFacilityException("시설 출구가 역에 포함되어 있지 않습니다.");
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

	private static String blankToNull(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value;
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
			facility.dataSourceType(),
			updatedAt
		);
	}
}
