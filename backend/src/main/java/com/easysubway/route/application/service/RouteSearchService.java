package com.easysubway.route.application.service;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteSearchUseCase;
import com.easysubway.route.application.port.in.SearchRouteCommand;
import com.easysubway.route.application.port.out.LoadRouteSearchPort;
import com.easysubway.route.application.port.out.SaveRouteSearchPort;
import com.easysubway.route.domain.InvalidRouteSearchException;
import com.easysubway.route.domain.RouteNotFoundException;
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
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.StationNotFoundException;
import com.easysubway.transit.domain.SubwayLine;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RouteSearchService implements RouteSearchUseCase {

	private final LoadRouteSearchPort loadRouteSearchPort;
	private final SaveRouteSearchPort saveRouteSearchPort;
	private final LoadTransitMasterPort loadTransitMasterPort;
	private final Clock clock;

	@Autowired
	public RouteSearchService(
		LoadRouteSearchPort loadRouteSearchPort,
		SaveRouteSearchPort saveRouteSearchPort,
		LoadTransitMasterPort loadTransitMasterPort
	) {
		this(loadRouteSearchPort, saveRouteSearchPort, loadTransitMasterPort, Clock.systemDefaultZone());
	}

	public RouteSearchService(
		LoadRouteSearchPort loadRouteSearchPort,
		SaveRouteSearchPort saveRouteSearchPort,
		LoadTransitMasterPort loadTransitMasterPort,
		Clock clock
	) {
		this.loadRouteSearchPort = loadRouteSearchPort;
		this.saveRouteSearchPort = saveRouteSearchPort;
		this.loadTransitMasterPort = loadTransitMasterPort;
		this.clock = clock;
	}

	@Override
	public RouteSearchResult searchRoute(SearchRouteCommand command) {
		requireCommand(command);
		Station origin = loadActiveStation(command.originStationId());
		Station destination = loadActiveStation(command.destinationStationId());
		if (origin.id().equals(destination.id())) {
			throw new InvalidRouteSearchException("출발역과 도착역이 달라야 합니다.");
		}

		DirectLine directLine = findDirectLine(origin.id(), destination.id());
		boolean stairOnlyAccess = hasStairOnlyAccess(origin.id(), destination.id());
		List<RouteWarning> warnings = routeWarnings(origin.id(), destination.id(), stairOnlyAccess);

		if (command.mobilityType() == MobilityType.WHEELCHAIR && stairOnlyAccess) {
			return saveRouteSearchPort.saveRouteSearch(new RouteSearchResult(
				newRouteSearchId(),
				origin.id(),
				origin.nameKo(),
				destination.id(),
				destination.nameKo(),
				command.mobilityType(),
				RouteSearchStatus.BLOCKED,
				directLine.line().id(),
				directLine.line().name(),
				0,
				List.of(),
				warnings,
				List.of("계단 없는 역 접근 경로를 확인할 수 없습니다."),
				LocalDateTime.now(clock)
			));
		}

		List<RouteStep> steps = directLineSteps(origin, destination, directLine);
		return saveRouteSearchPort.saveRouteSearch(new RouteSearchResult(
			newRouteSearchId(),
			origin.id(),
			origin.nameKo(),
			destination.id(),
			destination.nameKo(),
			command.mobilityType(),
			RouteSearchStatus.FOUND,
			directLine.line().id(),
			directLine.line().name(),
			routeScore(command.mobilityType(), directLine, warnings),
			steps,
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

	private Station loadActiveStation(String stationId) {
		return loadTransitMasterPort.loadStations()
			.stream()
			.filter(Station::active)
			.filter(station -> station.id().equals(stationId))
			.findFirst()
			.orElseThrow(StationNotFoundException::new);
	}

	private DirectLine findDirectLine(String originStationId, String destinationStationId) {
		// 현재 기준선은 환승 없이 같은 활성 노선에 속한 두 역만 직접 경로로 계산한다.
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
			.min(Comparator.comparingInt(DirectLine::stopCount))
			.orElseThrow(RouteNotFoundException::new);
	}

	private List<RouteWarning> routeWarnings(String originStationId, String destinationStationId, boolean stairOnlyAccess) {
		// 출구 데이터가 없거나 신뢰도가 낮으면 사용자가 이동 전 확인할 수 있게 경고를 남긴다.
		List<RouteWarning> warnings = new ArrayList<>();
		if (hasLowAccessibilityData(originStationId) || hasLowAccessibilityData(destinationStationId)) {
			warnings.add(new RouteWarning(
				RouteWarningCode.LOW_DATA_CONFIDENCE,
				"출발역 또는 도착역 접근성 정보가 부족합니다. 이동 전 역 상세 정보를 확인하세요."
			));
		}
		if (stairOnlyAccess) {
			warnings.add(new RouteWarning(
				RouteWarningCode.STAIR_ONLY_ACCESS,
				"출발역 또는 도착역에 계단 없는 접근 경로가 확인되지 않았습니다."
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

	private boolean hasStairOnlyAccess(String originStationId, String destinationStationId) {
		return hasStairOnlyAccess(originStationId) || hasStairOnlyAccess(destinationStationId);
	}

	private boolean hasStairOnlyAccess(String stationId) {
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

	private boolean isStepFreeFacility(AccessibilityFacility facility) {
		return switch (facility.type()) {
			case ELEVATOR, WHEELCHAIR_LIFT, RAMP -> true;
			default -> false;
		};
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

	private int routeScore(MobilityType mobilityType, DirectLine directLine, List<RouteWarning> warnings) {
		// 점수는 시간이 아니라 상대 비용이다. 낮을수록 쉬운 경로에 가깝다.
		int trainTime = directLine.stopCount() * 3;
		int walkingTime = 8;
		int profilePenalty = switch (mobilityType) {
			case SENIOR -> 12;
			case STROLLER, PREGNANT, LUGGAGE -> 10;
			case TEMPORARY_INJURY -> 14;
			case WHEELCHAIR -> 18;
		};
		int lowDataPenalty = warnings.stream()
			.anyMatch(warning -> warning.code() == RouteWarningCode.LOW_DATA_CONFIDENCE) ? 20 : 0;
		int stairOnlyPenalty = warnings.stream()
			.anyMatch(warning -> warning.code() == RouteWarningCode.STAIR_ONLY_ACCESS) ? 30 : 0;
		return trainTime + walkingTime + profilePenalty + lowDataPenalty + stairOnlyPenalty;
	}

	private List<RouteStep> directLineSteps(Station origin, Station destination, DirectLine directLine) {
		String displayLine = displayLineName(directLine.line());
		return List.of(
			new RouteStep(
				1,
				origin.nameKo() + "역에서 " + displayLine + " 승강장으로 이동",
				"엘리베이터와 넓은 통로가 있는 출구를 먼저 확인한 뒤 승강장으로 이동합니다.",
				directLine.line().id(),
				directLine.line().name(),
				origin.id(),
				origin.id()
			),
			new RouteStep(
				2,
				directLine.line().name() + "으로 " + destination.nameKo() + "역까지 이동",
				directLine.stopCount() + "개 역을 이동합니다. 환승은 없습니다.",
				directLine.line().id(),
				directLine.line().name(),
				origin.id(),
				destination.id()
			),
			new RouteStep(
				3,
				destination.nameKo() + "역에서 출구 접근성 정보를 확인",
				"도착역 출구와 엘리베이터 상태를 확인한 뒤 이동합니다.",
				directLine.line().id(),
				directLine.line().name(),
				destination.id(),
				destination.id()
			)
		);
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

	private record DirectLine(
		SubwayLine line,
		StationLine origin,
		StationLine destination
	) {

		int stopCount() {
			return Math.abs(origin.sequence() - destination.sequence());
		}
	}
}
