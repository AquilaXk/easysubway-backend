package com.easysubway.route.application.service;

import com.easysubway.route.application.port.in.RouteSearchUseCase;
import com.easysubway.route.application.port.in.SearchRouteCommand;
import com.easysubway.route.application.port.in.SubmitRouteFeedbackCommand;
import com.easysubway.route.application.port.out.LoadRouteSearchPort;
import com.easysubway.route.application.port.out.SaveRouteFeedbackPort;
import com.easysubway.route.application.port.out.SaveRouteSearchPort;
import com.easysubway.route.domain.InvalidRouteFeedbackException;
import com.easysubway.route.domain.InvalidRouteSearchException;
import com.easysubway.route.domain.RouteFeedback;
import com.easysubway.route.domain.RouteNotFoundException;
import com.easysubway.route.domain.RouteProfileWeight;
import com.easysubway.route.domain.RouteSearchNotFoundException;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteStep;
import com.easysubway.route.domain.RouteWarning;
import com.easysubway.route.domain.RouteWarningCode;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.RouteEdge;
import com.easysubway.transit.domain.RouteEdgeType;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.StationNotFoundException;
import com.easysubway.transit.domain.SubwayLine;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RouteSearchService implements RouteSearchUseCase {

	private static final int ENTRY_ESTIMATED_MINUTES = 4;
	private static final int ENTRY_DISTANCE_METERS = 180;
	private static final int EXIT_ESTIMATED_MINUTES = 3;
	private static final int EXIT_DISTANCE_METERS = 120;
	private static final int TRANSFER_ESTIMATED_MINUTES = 6;
	private static final int TRANSFER_DISTANCE_METERS = 260;
	private static final int MINUTES_PER_STATION = 2;
	private static final int METERS_PER_STATION = 900;
	private static final int ACCESSIBILITY_DATA_FRESH_DAYS = 30;

	private final LoadRouteSearchPort loadRouteSearchPort;
	private final SaveRouteSearchPort saveRouteSearchPort;
	private final SaveRouteFeedbackPort saveRouteFeedbackPort;
	private final LoadTransitMasterPort loadTransitMasterPort;
	private final Clock clock;

	@Autowired
	public RouteSearchService(
		LoadRouteSearchPort loadRouteSearchPort,
		SaveRouteSearchPort saveRouteSearchPort,
		SaveRouteFeedbackPort saveRouteFeedbackPort,
		LoadTransitMasterPort loadTransitMasterPort
	) {
		this(loadRouteSearchPort, saveRouteSearchPort, saveRouteFeedbackPort, loadTransitMasterPort, Clock.systemDefaultZone());
	}

	public RouteSearchService(
		LoadRouteSearchPort loadRouteSearchPort,
		SaveRouteSearchPort saveRouteSearchPort,
		LoadTransitMasterPort loadTransitMasterPort
	) {
		this(
			loadRouteSearchPort,
			saveRouteSearchPort,
			requireFeedbackPort(saveRouteSearchPort),
			loadTransitMasterPort,
			Clock.systemDefaultZone()
		);
	}

	public RouteSearchService(
		LoadRouteSearchPort loadRouteSearchPort,
		SaveRouteSearchPort saveRouteSearchPort,
		SaveRouteFeedbackPort saveRouteFeedbackPort,
		LoadTransitMasterPort loadTransitMasterPort,
		Clock clock
	) {
		this.loadRouteSearchPort = loadRouteSearchPort;
		this.saveRouteSearchPort = saveRouteSearchPort;
		this.saveRouteFeedbackPort = saveRouteFeedbackPort;
		this.loadTransitMasterPort = loadTransitMasterPort;
		this.clock = clock;
	}

	public RouteSearchService(
		LoadRouteSearchPort loadRouteSearchPort,
		SaveRouteSearchPort saveRouteSearchPort,
		LoadTransitMasterPort loadTransitMasterPort,
		Clock clock
	) {
		this(loadRouteSearchPort, saveRouteSearchPort, requireFeedbackPort(saveRouteSearchPort), loadTransitMasterPort, clock);
	}

	@Override
	public RouteSearchResult searchRoute(SearchRouteCommand command) {
		requireCommand(command);
		Station origin = loadActiveStation(command.originStationId());
		Station destination = loadActiveStation(command.destinationStationId());
		if (origin.id().equals(destination.id())) {
			throw new InvalidRouteSearchException("출발역과 도착역이 달라야 합니다.");
		}

		RouteProfileWeight profileWeight = RouteProfileWeight.from(command.mobilityType());
		RoutePlan routePlan = findRoutePlan(origin.id(), destination.id(), profileWeight);
		List<String> accessibilityStationIds = routePlan.accessibilityStationIds(origin.id(), destination.id());
		boolean stairOnlyAccess = hasStairOnlyAccess(accessibilityStationIds);
		List<RouteWarning> warnings = routeWarnings(accessibilityStationIds, stairOnlyAccess);

		if (profileWeight.blocksStairOnlyAccess() && stairOnlyAccess) {
			return saveRouteSearchPort.saveRouteSearch(new RouteSearchResult(
				newRouteSearchId(),
				origin.id(),
				origin.nameKo(),
				destination.id(),
				destination.nameKo(),
				command.mobilityType(),
				RouteSearchStatus.BLOCKED,
				routePlan.lineId(),
				routePlan.lineName(),
				0,
				List.of(),
				warnings,
				List.of("계단 없는 역 접근 경로를 확인할 수 없습니다."),
				LocalDateTime.now(clock)
			));
		}

		return saveRouteSearchPort.saveRouteSearch(new RouteSearchResult(
			newRouteSearchId(),
			origin.id(),
			origin.nameKo(),
			destination.id(),
			destination.nameKo(),
			command.mobilityType(),
			RouteSearchStatus.FOUND,
			routePlan.lineId(),
			routePlan.lineName(),
			routeScore(profileWeight, routePlan, warnings),
			routeSteps(origin, destination, routePlan, profileWeight),
			warnings,
			List.of(),
			LocalDateTime.now(clock)
		));
	}

	@Override
	public RouteSearchResult getRouteSearch(String routeSearchId) {
		if (routeSearchId == null || routeSearchId.isBlank()) {
			throw new RouteSearchNotFoundException();
		}
		return loadRouteSearchPort.loadRouteSearch(routeSearchId)
			.orElseThrow(RouteSearchNotFoundException::new);
	}

	@Override
	public RouteFeedback submitRouteFeedback(SubmitRouteFeedbackCommand command) {
		requireFeedbackCommand(command);
		String routeSearchId = command.routeSearchId().trim();
		if (loadRouteSearchPort.loadRouteSearch(routeSearchId).isEmpty()) {
			throw new RouteSearchNotFoundException();
		}
		return saveRouteFeedbackPort.saveRouteFeedback(new RouteFeedback(
			newRouteFeedbackId(),
			routeSearchId,
			command.userId().trim(),
			command.rating(),
			normalizeFeedbackComment(command.comment()),
			LocalDateTime.now(clock)
		));
	}

	private void requireCommand(SearchRouteCommand command) {
		if (command.originStationId() == null || command.originStationId().isBlank()) {
			throw new InvalidRouteSearchException("출발역을 선택해야 합니다.");
		}
		if (command.destinationStationId() == null || command.destinationStationId().isBlank()) {
			throw new InvalidRouteSearchException("도착역을 선택해야 합니다.");
		}
		if (command.mobilityType() == null) {
			throw new InvalidRouteSearchException("이동 유형을 선택해야 합니다.");
		}
	}

	private void requireFeedbackCommand(SubmitRouteFeedbackCommand command) {
		if (command == null || command.routeSearchId() == null || command.routeSearchId().isBlank()) {
			throw new RouteSearchNotFoundException();
		}
		if (command.userId() == null || command.userId().isBlank()) {
			throw new InvalidRouteFeedbackException("피드백 작성자를 확인해야 합니다.");
		}
		if (command.rating() == null) {
			throw new InvalidRouteFeedbackException("피드백 평가를 선택해야 합니다.");
		}
	}

	private String normalizeFeedbackComment(String comment) {
		if (comment == null) {
			return "";
		}
		return comment.trim();
	}

	private static SaveRouteFeedbackPort requireFeedbackPort(SaveRouteSearchPort saveRouteSearchPort) {
		if (saveRouteSearchPort instanceof SaveRouteFeedbackPort saveRouteFeedbackPort) {
			return saveRouteFeedbackPort;
		}
		throw new IllegalArgumentException("경로 피드백 저장 포트가 필요합니다.");
	}

	private Station loadActiveStation(String stationId) {
		return loadTransitMasterPort.loadStations()
			.stream()
			.filter(Station::active)
			.filter(station -> station.id().equals(stationId))
			.findFirst()
			.orElseThrow(StationNotFoundException::new);
	}

	private RoutePlan findRoutePlan(
		String originStationId,
		String destinationStationId,
		RouteProfileWeight profileWeight
	) {
		return findDirectLine(originStationId, destinationStationId)
			.map(RoutePlan::direct)
			.or(() -> findOneTransferRoute(originStationId, destinationStationId, profileWeight).map(RoutePlan::transfer))
			.orElseThrow(RouteNotFoundException::new);
	}

	private Optional<DirectLine> findDirectLine(String originStationId, String destinationStationId) {
		Map<String, SubwayLine> activeLinesById = loadTransitMasterPort.loadLines()
			.stream()
			.filter(SubwayLine::active)
			.collect(Collectors.toMap(SubwayLine::id, Function.identity()));

		Map<String, StationLine> originLinesByLineId = loadTransitMasterPort.loadStationLines()
			.stream()
			.filter(stationLine -> stationLine.stationId().equals(originStationId))
			.filter(stationLine -> activeLinesById.containsKey(stationLine.lineId()))
			.collect(Collectors.toMap(StationLine::lineId, Function.identity()));

		return loadTransitMasterPort.loadStationLines()
			.stream()
			.filter(stationLine -> stationLine.stationId().equals(destinationStationId))
			.filter(stationLine -> originLinesByLineId.containsKey(stationLine.lineId()))
			.filter(stationLine -> activeLinesById.containsKey(stationLine.lineId()))
			.map(destinationLine -> new DirectLine(
				activeLinesById.get(destinationLine.lineId()),
				originLinesByLineId.get(destinationLine.lineId()),
				destinationLine
			))
			.min(Comparator.comparingInt(DirectLine::stopCount));
	}

	private Optional<TransferRoute> findOneTransferRoute(
		String originStationId,
		String destinationStationId,
		RouteProfileWeight profileWeight
	) {
		Map<String, Boolean> stairOnlyAccessCache = new HashMap<>();
		Map<String, Boolean> lowAccessibilityDataCache = new HashMap<>();
		Predicate<String> stairOnlyAccess = stationId ->
			stairOnlyAccessCache.computeIfAbsent(stationId, this::hasStairOnlyAccess);
		Predicate<String> lowAccessibilityData = stationId ->
			lowAccessibilityDataCache.computeIfAbsent(stationId, this::hasLowAccessibilityData);

		return new TransferRouteGraph(
			loadTransitMasterPort.loadLines(),
			loadTransitMasterPort.loadStations(),
			loadTransitMasterPort.loadStationLines()
		).findBestOneTransferRoute(
			originStationId,
			destinationStationId,
			profileWeight,
			stairOnlyAccess,
			lowAccessibilityData
		);
	}

	private List<RouteWarning> routeWarnings(List<String> stationIds, boolean stairOnlyAccess) {
		// 출구 데이터가 없거나 신뢰도가 낮으면 사용자가 이동 전 확인할 수 있게 경고를 남긴다.
		List<RouteWarning> warnings = new ArrayList<>();
		if (stationIds.stream().anyMatch(this::hasLowAccessibilityData)) {
			warnings.add(new RouteWarning(
				RouteWarningCode.LOW_DATA_CONFIDENCE,
				"이동 경로 중 일부 역의 접근성 정보가 부족합니다. 이동 전 역 상세 정보를 확인하세요."
			));
		}
		if (stairOnlyAccess) {
			warnings.add(new RouteWarning(
				RouteWarningCode.STAIR_ONLY_ACCESS,
				"이동 경로 중 일부 역에 계단 없는 접근 경로가 확인되지 않았습니다."
			));
		}
		if (stationIds.stream().anyMatch(this::hasStaleAccessibilityData)) {
			warnings.add(new RouteWarning(
				RouteWarningCode.STALE_ACCESSIBILITY_DATA,
				"접근성 시설 정보가 최근 30일 이내 확인되지 않았습니다. 이동 전 역 상세 정보를 확인하세요."
			));
		}
		return List.copyOf(warnings);
	}

	private boolean hasLowAccessibilityData(String stationId) {
		List<StationExit> exits = stationExits(stationId);
		if (exits.isEmpty()) {
			return true;
		}
		boolean hasLowConfidenceExit = exits.stream()
			.anyMatch(exit -> exit.dataConfidence() != DataConfidenceLevel.HIGH);
		boolean hasLowConfidenceStepFreeFacility = stationFacilities(stationId).stream()
			.filter(this::isStepFreeFacility)
			.anyMatch(facility -> facility.dataConfidence() != DataConfidenceLevel.HIGH);
		return hasLowConfidenceExit || hasLowConfidenceStepFreeFacility;
	}

	private boolean hasStaleAccessibilityData(String stationId) {
		LocalDate staleBefore = LocalDate.now(clock).minusDays(ACCESSIBILITY_DATA_FRESH_DAYS);
		return stationFacilities(stationId).stream()
			.anyMatch(facility -> facility.lastUpdatedAt().isBefore(staleBefore));
	}

	private boolean hasStairOnlyAccess(List<String> stationIds) {
		return stationIds.stream().anyMatch(this::hasStairOnlyAccess);
	}

	private boolean hasStairOnlyAccess(String stationId) {
		Optional<Boolean> stairOnlyInternalEdges = hasStairOnlyInternalEdges(stationId);
		if (stairOnlyInternalEdges.isPresent()) {
			return stairOnlyInternalEdges.get();
		}

		List<StationExit> exits = stationExits(stationId);
		if (exits.isEmpty()) {
			return false;
		}
		List<StationExit> highConfidenceExits = exits.stream()
			.filter(exit -> exit.dataConfidence() == DataConfidenceLevel.HIGH)
			.toList();
		if (highConfidenceExits.isEmpty()) {
			return false;
		}
		// 차단 판단은 신뢰도 높은 실제 무단차 시설만 사용하고, 낮은 신뢰도 데이터는 경고로만 노출한다.
		List<AccessibilityFacility> highConfidenceStepFreeFacilities = stationFacilities(stationId).stream()
			.filter(facility -> facility.dataConfidence() == DataConfidenceLevel.HIGH)
			.filter(this::isStepFreeFacility)
			.toList();
		boolean hasUsableStepFreeFacility = highConfidenceStepFreeFacilities.stream()
			.anyMatch(this::hasUsableStatus);
		boolean hasUsableStepFreeExit = highConfidenceExits.stream()
			.anyMatch(exit -> isUsableStepFreeExit(exit, highConfidenceStepFreeFacilities));
		return !hasUsableStepFreeFacility && !hasUsableStepFreeExit;
	}

	private Optional<Boolean> hasStairOnlyInternalEdges(String stationId) {
		List<RouteEdge> activeStationEdges = loadTransitMasterPort.loadRouteEdges()
			.stream()
			.filter(RouteEdge::active)
			.filter(edge -> edge.stationId().equals(stationId))
			.filter(this::isInternalMovementEdge)
			.toList();
		if (activeStationEdges.isEmpty()) {
			return Optional.empty();
		}
		// 내부 이동 간선 데이터가 있으면 출구 요약보다 실제 승강장 연결 동선을 우선한다.
		boolean hasUsableStepFreeInternalEdge = activeStationEdges.stream()
			.anyMatch(edge -> isUsableStepFreeInternalEdge(stationId, edge));
		return Optional.of(!hasUsableStepFreeInternalEdge);
	}

	private boolean isUsableStepFreeInternalEdge(String stationId, RouteEdge edge) {
		if (edge.hasStairs()) {
			return false;
		}
		if (edge.requiresEscalator()) {
			return false;
		}
		if (edge.requiresElevator()) {
			return hasUsableStepFreeFacility(stationId);
		}
		return true;
	}

	private boolean isInternalMovementEdge(RouteEdge edge) {
		return switch (edge.type()) {
			case WALK, STAIR, ELEVATOR, ESCALATOR, RAMP -> true;
			case TRAIN, TRANSFER -> false;
		};
	}

	private String exitGuidance(String stationId, String fallbackGuidance) {
		return recommendedStepFreeExit(stationId)
			.map(exit -> exit.name() + "의 엘리베이터를 먼저 확인하세요.")
			.orElse(fallbackGuidance);
	}

	private Optional<StationExit> recommendedStepFreeExit(String stationId) {
		List<AccessibilityFacility> highConfidenceStepFreeFacilities = stationFacilities(stationId).stream()
			.filter(facility -> facility.dataConfidence() == DataConfidenceLevel.HIGH)
			.filter(this::isStepFreeFacility)
			.toList();
		return stationExits(stationId).stream()
			.filter(exit -> exit.dataConfidence() == DataConfidenceLevel.HIGH)
			.filter(exit -> isUsableStepFreeExit(exit, highConfidenceStepFreeFacilities))
			.findFirst();
	}

	private boolean isStepFreeFacility(AccessibilityFacility facility) {
		return switch (facility.type()) {
			case ELEVATOR, WHEELCHAIR_LIFT, RAMP -> true;
			default -> false;
		};
	}

	private boolean hasUsableStepFreeFacility(String stationId) {
		return stationFacilities(stationId).stream()
			.filter(facility -> facility.dataConfidence() == DataConfidenceLevel.HIGH)
			.filter(this::isStepFreeFacility)
			.anyMatch(this::hasUsableStatus);
	}

	private boolean hasUsableStatus(AccessibilityFacility facility) {
		return facility.status() == AccessibilityFacilityStatus.NORMAL
			|| facility.status() == AccessibilityFacilityStatus.ADMIN_VERIFIED;
	}

	private boolean isUsableStepFreeExit(
		StationExit exit,
		List<AccessibilityFacility> highConfidenceStepFreeFacilities
	) {
		if (!exit.hasElevatorConnection() || exit.hasStairOnlyPath()) {
			return false;
		}
		return highConfidenceStepFreeFacilities.stream()
			.noneMatch(facility -> exit.id().equals(facility.exitId()) && !hasUsableStatus(facility));
	}

	private List<StationExit> stationExits(String stationId) {
		return loadTransitMasterPort.loadStationExits()
			.stream()
			.filter(exit -> exit.stationId().equals(stationId))
			.toList();
	}

	private List<AccessibilityFacility> stationFacilities(String stationId) {
		return loadTransitMasterPort.loadAccessibilityFacilities()
			.stream()
			.filter(facility -> facility.stationId().equals(stationId))
			.toList();
	}

	private int routeScore(RouteProfileWeight profileWeight, RoutePlan routePlan, List<RouteWarning> warnings) {
		// 점수는 시간이 아니라 상대 비용이다. 낮을수록 쉬운 경로에 가깝다.
		int trainTime = routePlan.stopCount() * 3;
		int transferPenalty = routePlan.transferCount() * profileWeight.transferPenalty();
		int lowDataPenalty = warnings.stream()
			.anyMatch(warning -> warning.code() == RouteWarningCode.LOW_DATA_CONFIDENCE)
			? profileWeight.lowDataConfidencePenalty()
			: 0;
		int stairOnlyPenalty = warnings.stream()
			.anyMatch(warning -> warning.code() == RouteWarningCode.STAIR_ONLY_ACCESS)
			? profileWeight.stairOnlyAccessPenalty()
			: 0;
		return trainTime + transferPenalty + profileWeight.baseAccessCost() + lowDataPenalty + stairOnlyPenalty;
	}

	private List<RouteStep> routeSteps(
		Station origin,
		Station destination,
		RoutePlan routePlan,
		RouteProfileWeight profileWeight
	) {
		if (routePlan.transferRoute().isPresent()) {
			return transferSteps(origin, destination, routePlan.transferRoute().get(), profileWeight);
		}
		return directLineSteps(origin, destination, routePlan.directLine().orElseThrow(), profileWeight);
	}

	private List<RouteStep> directLineSteps(
		Station origin,
		Station destination,
		DirectLine directLine,
		RouteProfileWeight profileWeight
	) {
		String displayLine = displayLineName(directLine.line());
		boolean originIncludesStairs = hasStairOnlyAccess(origin.id());
		boolean destinationIncludesStairs = hasStairOnlyAccess(destination.id());
		return List.of(
			new RouteStep(
				1,
				origin.nameKo() + "역에서 " + displayLine + " 승강장으로 이동",
				profileWeight.entryGuidance(),
				directLine.line().id(),
				directLine.line().name(),
				origin.id(),
				origin.id(),
				ENTRY_ESTIMATED_MINUTES,
				ENTRY_DISTANCE_METERS,
				originIncludesStairs,
				true
			),
			new RouteStep(
				2,
				directLine.line().name() + "으로 " + destination.nameKo() + "역까지 이동",
				directLine.stopCount() + "개 역을 이동합니다. 환승은 없습니다.",
				directLine.line().id(),
				directLine.line().name(),
				origin.id(),
				destination.id(),
				trainEstimatedMinutes(directLine.stopCount()),
				trainDistanceMeters(directLine.stopCount()),
				false,
				false
			),
			new RouteStep(
				3,
				destination.nameKo() + "역에서 출구 접근성 정보를 확인",
				exitGuidance(destination.id(), profileWeight.exitGuidance()),
				directLine.line().id(),
				directLine.line().name(),
				destination.id(),
				destination.id(),
				EXIT_ESTIMATED_MINUTES,
				EXIT_DISTANCE_METERS,
				destinationIncludesStairs,
				true
			)
		);
	}

	private List<RouteStep> transferSteps(
		Station origin,
		Station destination,
		TransferRoute route,
		RouteProfileWeight profileWeight
	) {
		String firstDisplayLine = displayLineName(route.firstLine());
		String secondDisplayLine = displayLineName(route.secondLine());
		boolean originIncludesStairs = hasStairOnlyAccess(origin.id());
		boolean transferIncludesStairs = hasStairOnlyAccess(route.transferStation().id());
		boolean destinationIncludesStairs = hasStairOnlyAccess(destination.id());
		return List.of(
			new RouteStep(
				1,
				origin.nameKo() + "역에서 " + firstDisplayLine + " 승강장으로 이동",
				profileWeight.entryGuidance(),
				route.firstLine().id(),
				route.firstLine().name(),
				origin.id(),
				origin.id(),
				ENTRY_ESTIMATED_MINUTES,
				ENTRY_DISTANCE_METERS,
				originIncludesStairs,
				true
			),
			new RouteStep(
				2,
				route.firstLine().name() + "으로 " + route.transferStation().nameKo() + "역까지 이동",
				route.firstSegmentStopCount() + "개 역을 이동한 뒤 환승합니다.",
				route.firstLine().id(),
				route.firstLine().name(),
				origin.id(),
				route.transferStation().id(),
				trainEstimatedMinutes(route.firstSegmentStopCount()),
				trainDistanceMeters(route.firstSegmentStopCount()),
				false,
				false
			),
			new RouteStep(
				3,
				route.transferStation().nameKo() + "역에서 " + secondDisplayLine + " 승강장으로 환승",
				route.transferStation().nameKo() + "의 엘리베이터와 계단 없는 연결 동선을 먼저 확인합니다.",
				route.secondLine().id(),
				route.secondLine().name(),
				route.transferStation().id(),
				route.transferStation().id(),
				TRANSFER_ESTIMATED_MINUTES,
				TRANSFER_DISTANCE_METERS,
				transferIncludesStairs,
				true
			),
			new RouteStep(
				4,
				route.secondLine().name() + "으로 " + destination.nameKo() + "역까지 이동",
				route.secondSegmentStopCount() + "개 역을 이동합니다.",
				route.secondLine().id(),
				route.secondLine().name(),
				route.transferStation().id(),
				destination.id(),
				trainEstimatedMinutes(route.secondSegmentStopCount()),
				trainDistanceMeters(route.secondSegmentStopCount()),
				false,
				false
			),
			new RouteStep(
				5,
				destination.nameKo() + "역에서 출구 접근성 정보를 확인",
				exitGuidance(destination.id(), profileWeight.exitGuidance()),
				route.secondLine().id(),
				route.secondLine().name(),
				destination.id(),
				destination.id(),
				EXIT_ESTIMATED_MINUTES,
				EXIT_DISTANCE_METERS,
				destinationIncludesStairs,
				true
			)
		);
	}

	private int trainEstimatedMinutes(int stopCount) {
		return Math.max(1, stopCount) * MINUTES_PER_STATION;
	}

	private int trainDistanceMeters(int stopCount) {
		return Math.max(1, stopCount) * METERS_PER_STATION;
	}

	private String displayLineName(SubwayLine line) {
		String lineCode = line.lineCode();
		if (lineCode != null && !lineCode.isBlank() && lineCode.chars().allMatch(Character::isDigit)) {
			return lineCode + "호선";
		}
		return line.name();
	}

	private String newRouteSearchId() {
		return "route-" + UUID.randomUUID();
	}

	private String newRouteFeedbackId() {
		return "route-feedback-" + UUID.randomUUID();
	}

	private record DirectLine(
		SubwayLine line,
		StationLine origin,
		StationLine destination
	) {

		int stopCount() {
			return Math.abs(origin.sequence() - destination.sequence());
		}
	}

	private record RoutePlan(
		Optional<DirectLine> directLine,
		Optional<TransferRoute> transferRoute
	) {

		static RoutePlan direct(DirectLine directLine) {
			return new RoutePlan(Optional.of(directLine), Optional.empty());
		}

		static RoutePlan transfer(TransferRoute transferRoute) {
			return new RoutePlan(Optional.empty(), Optional.of(transferRoute));
		}

		String lineId() {
			return directLine
				.map(direct -> direct.line().id())
				.orElseGet(() -> transferRoute
					.map(route -> route.firstLine().id() + "/" + route.secondLine().id())
					.orElseThrow());
		}

		String lineName() {
			return directLine
				.map(direct -> direct.line().name())
				.orElseGet(() -> transferRoute
					.map(route -> route.firstLine().name() + " / " + route.secondLine().name())
					.orElseThrow());
		}

		int stopCount() {
			return directLine
				.map(DirectLine::stopCount)
				.orElseGet(() -> transferRoute.map(TransferRoute::stopCount).orElseThrow());
		}

		int transferCount() {
			return transferRoute.isPresent() ? 1 : 0;
		}

		List<String> accessibilityStationIds(String originStationId, String destinationStationId) {
			return transferRoute
				.map(route -> List.of(originStationId, route.transferStation().id(), destinationStationId))
				.orElseGet(() -> List.of(originStationId, destinationStationId));
		}
	}
}
