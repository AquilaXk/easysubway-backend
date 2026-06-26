package com.easysubway.transit.application.service;

import com.easysubway.transit.application.port.in.CreateAccessibilityFacilityCommand;
import com.easysubway.transit.application.port.in.NearbyStationSearchCommand;
import com.easysubway.transit.application.port.in.StationMasterDataCounts;
import com.easysubway.transit.application.port.in.StationSearchCommand;
import com.easysubway.transit.application.port.in.TransitMasterAdminUseCase;
import com.easysubway.transit.application.port.in.TransitMasterQueryUseCase;
import com.easysubway.transit.application.port.in.UpdateAccessibilityFacilityCommand;
import com.easysubway.transit.application.port.in.UpdateAccessibilityFacilityStatusCommand;
import com.easysubway.transit.application.port.in.UpdateRouteEdgeCommand;
import com.easysubway.transit.application.port.in.UpdateRouteNodeDisplayCommand;
import com.easysubway.transit.application.port.in.UpdateSimplifiedStationLayoutStatusCommand;
import com.easysubway.transit.application.port.in.UpdateStationLayoutSourceCommand;
import com.easysubway.notification.application.port.in.FacilityStatusAlertUseCase;
import com.easysubway.notification.application.port.in.FacilityStatusChangedAlertCommand;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.application.port.out.MasterDataCapability;
import com.easysubway.transit.application.port.out.MasterDataCapabilityPort;
import com.easysubway.transit.application.port.out.MasterDataCapabilityStatus;
import com.easysubway.transit.application.port.out.SaveAccessibilityFacilityStatusPort;
import com.easysubway.transit.application.port.out.SaveRouteEdgePort;
import com.easysubway.transit.application.port.out.SaveRouteNodePort;
import com.easysubway.transit.application.port.out.SaveStationLayoutSourcePort;
import com.easysubway.transit.application.port.out.SaveSimplifiedStationLayoutStatusPort;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityNotFoundException;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.InvalidAccessibilityFacilityException;
import com.easysubway.transit.domain.InvalidRouteEdgeException;
import com.easysubway.transit.domain.InvalidRouteNodeException;
import com.easysubway.transit.domain.InvalidSimplifiedStationLayoutException;
import com.easysubway.transit.domain.InvalidStationLayoutSourceException;
import com.easysubway.transit.domain.MasterDataWriteNotAllowedException;
import com.easysubway.transit.domain.NearbyStation;
import com.easysubway.transit.domain.RouteEdge;
import com.easysubway.transit.domain.RouteEdgeNotFoundException;
import com.easysubway.transit.domain.RouteNode;
import com.easysubway.transit.domain.RouteNodeNotFoundException;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLayoutSource;
import com.easysubway.transit.domain.StationLayoutSourceNotFoundException;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.StationLineSummary;
import com.easysubway.transit.domain.StationNotFoundException;
import com.easysubway.transit.domain.StationWithLines;
import com.easysubway.transit.domain.SimplifiedStationLayout;
import com.easysubway.transit.domain.SimplifiedStationLayoutNotFoundException;
import com.easysubway.transit.domain.SimplifiedStationLayoutStatus;
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
	private final SaveStationLayoutSourcePort saveStationLayoutSourcePort;
	private final SaveSimplifiedStationLayoutStatusPort saveSimplifiedStationLayoutStatusPort;
	private final SaveRouteNodePort saveRouteNodePort;
	private final SaveRouteEdgePort saveRouteEdgePort;
	private final FacilityStatusAlertUseCase facilityStatusAlertUseCase;
	private final Clock clock;

	@Autowired
	public TransitMasterService(
		LoadTransitMasterPort loadTransitMasterPort,
		SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort,
		SaveStationLayoutSourcePort saveStationLayoutSourcePort,
		SaveSimplifiedStationLayoutStatusPort saveSimplifiedStationLayoutStatusPort,
		SaveRouteNodePort saveRouteNodePort,
		SaveRouteEdgePort saveRouteEdgePort,
		FacilityStatusAlertUseCase facilityStatusAlertUseCase
	) {
		this(
			loadTransitMasterPort,
			saveAccessibilityFacilityStatusPort,
			saveStationLayoutSourcePort,
			saveSimplifiedStationLayoutStatusPort,
			saveRouteNodePort,
			saveRouteEdgePort,
			facilityStatusAlertUseCase,
			Clock.systemDefaultZone()
		);
	}

	public TransitMasterService(
		LoadTransitMasterPort loadTransitMasterPort,
		SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort
	) {
		this(
			loadTransitMasterPort,
			saveAccessibilityFacilityStatusPort,
			stationLayoutSourcePortOrNoop(loadTransitMasterPort),
			layoutStatusPortOrNoop(loadTransitMasterPort),
			routeNodePortOrNoop(loadTransitMasterPort),
			routeEdgePortOrNoop(loadTransitMasterPort),
			command -> {
			},
			Clock.systemDefaultZone()
		);
	}

	public TransitMasterService(
		LoadTransitMasterPort loadTransitMasterPort,
		SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort,
		Clock clock
	) {
		this(
			loadTransitMasterPort,
			saveAccessibilityFacilityStatusPort,
			stationLayoutSourcePortOrNoop(loadTransitMasterPort),
			layoutStatusPortOrNoop(loadTransitMasterPort),
			routeNodePortOrNoop(loadTransitMasterPort),
			routeEdgePortOrNoop(loadTransitMasterPort),
			command -> {
			},
			clock
		);
	}

	public TransitMasterService(
		LoadTransitMasterPort loadTransitMasterPort,
		SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort,
		FacilityStatusAlertUseCase facilityStatusAlertUseCase,
		Clock clock
	) {
		this(
			loadTransitMasterPort,
			saveAccessibilityFacilityStatusPort,
			stationLayoutSourcePortOrNoop(loadTransitMasterPort),
			layoutStatusPortOrNoop(loadTransitMasterPort),
			routeNodePortOrNoop(loadTransitMasterPort),
			routeEdgePortOrNoop(loadTransitMasterPort),
			facilityStatusAlertUseCase,
			clock
		);
	}

	public TransitMasterService(
		LoadTransitMasterPort loadTransitMasterPort,
		SaveAccessibilityFacilityStatusPort saveAccessibilityFacilityStatusPort,
		SaveStationLayoutSourcePort saveStationLayoutSourcePort,
		SaveSimplifiedStationLayoutStatusPort saveSimplifiedStationLayoutStatusPort,
		SaveRouteNodePort saveRouteNodePort,
		SaveRouteEdgePort saveRouteEdgePort,
		FacilityStatusAlertUseCase facilityStatusAlertUseCase,
		Clock clock
	) {
		this.loadTransitMasterPort = loadTransitMasterPort;
		this.saveAccessibilityFacilityStatusPort = saveAccessibilityFacilityStatusPort;
		this.saveStationLayoutSourcePort = saveStationLayoutSourcePort;
		this.saveSimplifiedStationLayoutStatusPort = saveSimplifiedStationLayoutStatusPort;
		this.saveRouteNodePort = saveRouteNodePort;
		this.saveRouteEdgePort = saveRouteEdgePort;
		this.facilityStatusAlertUseCase = facilityStatusAlertUseCase;
		this.clock = clock;
	}

	@Override
	public MasterDataCapability masterDataCapability() {
		if (loadTransitMasterPort instanceof MasterDataCapabilityPort capabilityPort) {
			return capabilityPort.masterDataCapability();
		}
		return new MasterDataCapability(MasterDataCapabilityStatus.UP, true, true, "unknown", "unknown", null);
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
	public Map<String, StationMasterDataCounts> countStationMasterDataByStationId() {
		Map<String, Long> exitCounts = countByStationId(loadTransitMasterPort.loadStationExits(), StationExit::stationId);
		Map<String, Long> facilityCounts = countByStationId(
			loadTransitMasterPort.loadAccessibilityFacilities(),
			AccessibilityFacility::stationId
		);
		Map<String, Long> layoutSourceCounts = countByStationId(
			loadTransitMasterPort.loadStationLayoutSources(),
			StationLayoutSource::stationId
		);
		Map<String, Long> simplifiedLayoutCounts = countByStationId(
			loadTransitMasterPort.loadSimplifiedStationLayouts(),
			SimplifiedStationLayout::stationId
		);
		Map<String, Long> routeNodeCounts = countByStationId(loadTransitMasterPort.loadRouteNodes(), RouteNode::stationId);
		Map<String, Long> routeEdgeCounts = countByStationId(loadTransitMasterPort.loadRouteEdges(), RouteEdge::stationId);

		return activeStations()
			.stream()
			.collect(Collectors.toMap(
				Station::id,
				station -> new StationMasterDataCounts(
					countFor(exitCounts, station.id()),
					countFor(facilityCounts, station.id()),
					countFor(layoutSourceCounts, station.id()),
					countFor(simplifiedLayoutCounts, station.id()),
					countFor(routeNodeCounts, station.id()),
					countFor(routeEdgeCounts, station.id())
				)
			));
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

		requireWritableMasterData();
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

		requireWritableMasterData();
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
		requireWritableMasterData();
		saveAccessibilityFacilityStatusPort.saveFacilityStatus(facility.id(), command.status(), updatedAt);
		if (facility.status() != command.status()) {
			facilityStatusAlertUseCase.alertFacilityStatusChanged(
				new FacilityStatusChangedAlertCommand(facility.id(), command.status())
			);
		}
		return withStatus(facility, command.status(), updatedAt);
	}

	@Override
	public StationLayoutSource updateStationLayoutSource(UpdateStationLayoutSourceCommand command) {
		requireStationLayoutSource(command);
		loadActiveStation(command.stationId());
		StationLayoutSource source = loadStationLayoutSource(command.sourceId());
		if (!source.stationId().equals(command.stationId())) {
			throw new StationLayoutSourceNotFoundException();
		}

		StationLayoutSource updated = withStationLayoutSource(source, command);
		requireWritableMasterData();
		saveStationLayoutSourcePort.saveStationLayoutSource(updated);
		return updated;
	}

	@Override
	public SimplifiedStationLayout updateSimplifiedStationLayoutStatus(UpdateSimplifiedStationLayoutStatusCommand command) {
		requireLayoutStatus(command);
		requireReviewer(command);

		SimplifiedStationLayout layout = loadSimplifiedStationLayout(command.layoutId());
		LocalDate updatedAt = LocalDate.now(clock);
		// 검수 상태는 앱 렌더링 데이터가 아니라 운영자가 배포 가능성을 판단하는 메타데이터만 갱신한다.
		requireWritableMasterData();
		saveSimplifiedStationLayoutStatusPort.saveSimplifiedStationLayoutStatus(
			layout.id(),
			command.status(),
			command.reviewedBy(),
			updatedAt
		);
		return withLayoutStatus(layout, command.status(), command.reviewedBy(), updatedAt);
	}

	@Override
	public RouteNode updateRouteNodeDisplay(UpdateRouteNodeDisplayCommand command) {
		requireRouteNodeDisplay(command);
		loadActiveStation(command.stationId());
		RouteNode routeNode = loadRouteNode(command.nodeId());
		if (!routeNode.stationId().equals(command.stationId())) {
			throw new RouteNodeNotFoundException();
		}

		RouteNode updated = withRouteNodeDisplay(routeNode, command);
		requireWritableMasterData();
		saveRouteNodePort.saveRouteNode(updated);
		return updated;
	}

	@Override
	public RouteEdge updateRouteEdge(UpdateRouteEdgeCommand command) {
		requireRouteEdge(command);
		loadActiveStation(command.stationId());
		RouteEdge routeEdge = loadRouteEdge(command.edgeId());
		if (!routeEdge.stationId().equals(command.stationId())) {
			throw new RouteEdgeNotFoundException();
		}

		RouteEdge updated = withRouteEdge(routeEdge, command);
		requireWritableMasterData();
		saveRouteEdgePort.saveRouteEdge(updated);
		return updated;
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

	private void requireWritableMasterData() {
		if (!masterDataCapability().writable()) {
			throw new MasterDataWriteNotAllowedException();
		}
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

	private static <T> Map<String, Long> countByStationId(List<T> values, Function<T, String> stationIdExtractor) {
		return values
			.stream()
			.collect(Collectors.groupingBy(stationIdExtractor, Collectors.counting()));
	}

	private static int countFor(Map<String, Long> counts, String stationId) {
		return Math.toIntExact(counts.getOrDefault(stationId, 0L));
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

	private void requireLayoutStatus(UpdateSimplifiedStationLayoutStatusCommand command) {
		if (command.status() == null) {
			throw new InvalidSimplifiedStationLayoutException("구조도 상태를 선택해야 합니다.");
		}
	}

	private void requireStationLayoutSource(UpdateStationLayoutSourceCommand command) {
		if (command.stationId() == null || command.stationId().isBlank()) {
			throw new InvalidStationLayoutSourceException("역 식별자가 필요합니다.");
		}
		if (command.sourceId() == null || command.sourceId().isBlank()) {
			throw new InvalidStationLayoutSourceException("기준 자료 식별자가 필요합니다.");
		}
		if (command.sourceType() == null) {
			throw new InvalidStationLayoutSourceException("기준 자료 유형을 선택해야 합니다.");
		}
		if (command.sourceName() == null || command.sourceName().isBlank()) {
			throw new InvalidStationLayoutSourceException("기준 자료 이름을 입력해야 합니다.");
		}
		if (command.sourceUrl() == null || command.sourceUrl().isBlank()) {
			throw new InvalidStationLayoutSourceException("기준 자료 URL을 입력해야 합니다.");
		}
		if (command.license() == null || command.license().isBlank()) {
			throw new InvalidStationLayoutSourceException("기준 자료 라이선스를 입력해야 합니다.");
		}
		if (command.capturedAt() == null) {
			throw new InvalidStationLayoutSourceException("기준 자료 수집일을 입력해야 합니다.");
		}
		if (command.reviewedAt() != null && command.reviewedAt().isBefore(command.capturedAt())) {
			throw new InvalidStationLayoutSourceException("기준 자료 검수일은 수집일보다 빠를 수 없습니다.");
		}
		if (command.updatedBy() == null || command.updatedBy().isBlank()) {
			throw new InvalidStationLayoutSourceException("수정자 식별자가 필요합니다.");
		}
	}

	private void requireReviewer(UpdateSimplifiedStationLayoutStatusCommand command) {
		if (command.reviewedBy() == null || command.reviewedBy().isBlank()) {
			throw new InvalidSimplifiedStationLayoutException("검수자 식별자가 필요합니다.");
		}
	}

	private void requireRouteNodeDisplay(UpdateRouteNodeDisplayCommand command) {
		if (command.stationId() == null || command.stationId().isBlank()) {
			throw new InvalidRouteNodeException("역 식별자가 필요합니다.");
		}
		if (command.nodeId() == null || command.nodeId().isBlank()) {
			throw new InvalidRouteNodeException("노드 식별자가 필요합니다.");
		}
		if (command.displayX() < 0 || command.displayY() < 0) {
			throw new InvalidRouteNodeException("노드 표시 좌표는 0 이상이어야 합니다.");
		}
		if (command.displayLabel() == null || command.displayLabel().isBlank()) {
			throw new InvalidRouteNodeException("노드 표시 라벨을 입력해야 합니다.");
		}
		if (command.updatedBy() == null || command.updatedBy().isBlank()) {
			throw new InvalidRouteNodeException("수정자 식별자가 필요합니다.");
		}
	}

	private void requireRouteEdge(UpdateRouteEdgeCommand command) {
		if (command.stationId() == null || command.stationId().isBlank()) {
			throw new InvalidRouteEdgeException("역 식별자가 필요합니다.");
		}
		if (command.edgeId() == null || command.edgeId().isBlank()) {
			throw new InvalidRouteEdgeException("간선 식별자가 필요합니다.");
		}
		if (command.distanceMeters() < 0 || command.estimatedSeconds() < 0) {
			throw new InvalidRouteEdgeException("간선 거리와 예상 시간은 0 이상이어야 합니다.");
		}
		if (command.slopeLevel() < 1 || command.slopeLevel() > 5
			|| command.widthLevel() < 1 || command.widthLevel() > 5) {
			throw new InvalidRouteEdgeException("간선 경사와 폭 레벨은 1부터 5까지 입력해야 합니다.");
		}
		if (command.reliabilityScore() < 0 || command.reliabilityScore() > 100) {
			throw new InvalidRouteEdgeException("간선 신뢰도는 0부터 100까지 입력해야 합니다.");
		}
		if (command.updatedBy() == null || command.updatedBy().isBlank()) {
			throw new InvalidRouteEdgeException("수정자 식별자가 필요합니다.");
		}
	}

	private AccessibilityFacility loadAccessibilityFacility(String facilityId) {
		return loadTransitMasterPort.loadAccessibilityFacilities()
			.stream()
			.filter(facility -> facility.id().equals(facilityId))
			.findFirst()
			.orElseThrow(AccessibilityFacilityNotFoundException::new);
	}

	private SimplifiedStationLayout loadSimplifiedStationLayout(String layoutId) {
		return loadTransitMasterPort.loadSimplifiedStationLayouts()
			.stream()
			.filter(layout -> layout.id().equals(layoutId))
			.findFirst()
			.orElseThrow(SimplifiedStationLayoutNotFoundException::new);
	}

	private StationLayoutSource loadStationLayoutSource(String sourceId) {
		return loadTransitMasterPort.loadStationLayoutSources()
			.stream()
			.filter(source -> source.id().equals(sourceId))
			.findFirst()
			.orElseThrow(StationLayoutSourceNotFoundException::new);
	}

	private RouteNode loadRouteNode(String nodeId) {
		return loadTransitMasterPort.loadRouteNodes()
			.stream()
			.filter(node -> node.id().equals(nodeId))
			.findFirst()
			.orElseThrow(RouteNodeNotFoundException::new);
	}

	private RouteEdge loadRouteEdge(String edgeId) {
		return loadTransitMasterPort.loadRouteEdges()
			.stream()
			.filter(edge -> edge.id().equals(edgeId))
			.findFirst()
			.orElseThrow(RouteEdgeNotFoundException::new);
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

	private SimplifiedStationLayout withLayoutStatus(
		SimplifiedStationLayout layout,
		SimplifiedStationLayoutStatus status,
		String reviewedBy,
		LocalDate updatedAt
	) {
		return new SimplifiedStationLayout(
			layout.id(),
			layout.stationId(),
			layout.version(),
			status,
			layout.sourceIds(),
			layout.confidenceLevel(),
			layout.baseFloor(),
			layout.layoutJson(),
			layout.renderedPreviewUrl(),
			layout.createdBy(),
			reviewedBy,
			status == SimplifiedStationLayoutStatus.PUBLISHED ? updatedAt : layout.publishedAt(),
			updatedAt
		);
	}

	private StationLayoutSource withStationLayoutSource(
		StationLayoutSource source,
		UpdateStationLayoutSourceCommand command
	) {
		return new StationLayoutSource(
			source.id(),
			source.stationId(),
			command.sourceType(),
			command.sourceName(),
			command.sourceUrl(),
			command.license(),
			command.commercialUseAllowed(),
			command.attributionRequired(),
			command.capturedAt(),
			command.reviewedAt()
		);
	}

	private RouteNode withRouteNodeDisplay(RouteNode routeNode, UpdateRouteNodeDisplayCommand command) {
		return new RouteNode(
			routeNode.id(),
			routeNode.stationId(),
			routeNode.type(),
			routeNode.name(),
			routeNode.floor(),
			routeNode.latitude(),
			routeNode.longitude(),
			routeNode.facilityId(),
			routeNode.layoutId(),
			command.displayX(),
			command.displayY(),
			command.displayLabel(),
			command.accessibilityNote()
		);
	}

	private RouteEdge withRouteEdge(RouteEdge routeEdge, UpdateRouteEdgeCommand command) {
		return new RouteEdge(
			routeEdge.id(),
			routeEdge.stationId(),
			routeEdge.fromNodeId(),
			routeEdge.toNodeId(),
			routeEdge.type(),
			command.distanceMeters(),
			command.estimatedSeconds(),
			command.hasStairs(),
			command.requiresElevator(),
			command.requiresEscalator(),
			command.slopeLevel(),
			command.widthLevel(),
			command.reliabilityScore(),
			command.active()
		);
	}

	private static SaveSimplifiedStationLayoutStatusPort layoutStatusPortOrNoop(
		LoadTransitMasterPort loadTransitMasterPort
	) {
		if (loadTransitMasterPort instanceof SaveSimplifiedStationLayoutStatusPort port) {
			return port;
		}
		return (layoutId, status, reviewedBy, updatedAt) -> {
		};
	}

	private static SaveStationLayoutSourcePort stationLayoutSourcePortOrNoop(LoadTransitMasterPort loadTransitMasterPort) {
		if (loadTransitMasterPort instanceof SaveStationLayoutSourcePort port) {
			return port;
		}
		return source -> {
		};
	}

	private static SaveRouteNodePort routeNodePortOrNoop(LoadTransitMasterPort loadTransitMasterPort) {
		if (loadTransitMasterPort instanceof SaveRouteNodePort port) {
			return port;
		}
		return routeNode -> {
		};
	}

	private static SaveRouteEdgePort routeEdgePortOrNoop(LoadTransitMasterPort loadTransitMasterPort) {
		if (loadTransitMasterPort instanceof SaveRouteEdgePort port) {
			return port;
		}
		return routeEdge -> {
		};
	}
}
