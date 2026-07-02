package com.easysubway.route.application.service;

import com.easysubway.route.domain.RouteProfileWeight;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.SubwayLine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

class TransferRouteGraph {

	private final Map<String, SubwayLine> activeLinesById;
	private final Map<String, Station> activeStationsById;
	private final List<StationLine> activeStationLines;
	private final Map<String, List<StationLine>> stationLinesByStationId;

	TransferRouteGraph(
		List<SubwayLine> lines,
		List<Station> stations,
		List<StationLine> stationLines
	) {
		this.activeLinesById = lines.stream()
			.filter(SubwayLine::active)
			.collect(Collectors.toMap(SubwayLine::id, Function.identity()));
		this.activeStationsById = stations.stream()
			.filter(Station::active)
			.collect(Collectors.toMap(Station::id, Function.identity()));
		this.activeStationLines = stationLines.stream()
			.filter(stationLine -> activeLinesById.containsKey(stationLine.lineId()))
			.filter(stationLine -> activeStationsById.containsKey(stationLine.stationId()))
			.toList();
		this.stationLinesByStationId = activeStationLines.stream()
			.collect(Collectors.groupingBy(StationLine::stationId));
	}

	Optional<TransferRoute> findBestOneTransferRoute(
		String originStationId,
		String destinationStationId,
		RouteProfileWeight profileWeight,
		Predicate<String> stairOnlyAccess,
		Predicate<String> lowAccessibilityData
	) {
		return findOneTransferRoutes(
			originStationId,
			destinationStationId,
			profileWeight,
			stairOnlyAccess,
			lowAccessibilityData
		).stream().findFirst();
	}

	List<TransferRoute> findOneTransferRoutes(
		String originStationId,
		String destinationStationId,
		RouteProfileWeight profileWeight,
		Predicate<String> stairOnlyAccess,
		Predicate<String> lowAccessibilityData
	) {
		List<StationLine> originLines = stationLines(originStationId);
		List<StationLine> destinationLines = stationLines(destinationStationId);

		List<TransferRoute> candidates = new ArrayList<>();
		for (StationLine originLine : originLines) {
			for (StationLine destinationLine : destinationLines) {
				if (!originLine.lineId().equals(destinationLine.lineId())) {
					addTransferCandidates(candidates, originStationId, destinationStationId, originLine, destinationLine);
				}
			}
		}

		return candidates.stream()
			.sorted(Comparator.comparingInt((TransferRoute route) -> transferAccessibilityRank(route, profileWeight, stairOnlyAccess))
				.thenComparingInt(route -> transferCandidateCost(route, profileWeight, stairOnlyAccess, lowAccessibilityData))
				.thenComparing(route -> route.transferStation().nameKo()))
			.toList();
	}

	private void addTransferCandidates(
		List<TransferRoute> candidates,
		String originStationId,
		String destinationStationId,
		StationLine originLine,
		StationLine destinationLine
	) {
		// 그래프의 한 간선은 출발 노선과 도착 노선이 같은 활성 역에서 만나는 환승 연결을 뜻한다.
		stationLinesByStationId.forEach((stationId, linesAtStation) -> addTransferCandidate(
			candidates,
			originStationId,
			destinationStationId,
			originLine,
			destinationLine,
			stationId,
			linesAtStation
		));
	}

	private void addTransferCandidate(
		List<TransferRoute> candidates,
		String originStationId,
		String destinationStationId,
		StationLine originLine,
		StationLine destinationLine,
		String stationId,
		List<StationLine> linesAtStation
	) {
		if (stationId.equals(originStationId) || stationId.equals(destinationStationId)) {
			return;
		}
		Optional<StationLine> transferOriginLine = stationLineFor(linesAtStation, originLine.lineId());
		Optional<StationLine> transferDestinationLine = stationLineFor(linesAtStation, destinationLine.lineId());
		if (transferOriginLine.isEmpty() || transferDestinationLine.isEmpty()) {
			return;
		}
		candidates.add(new TransferRoute(
			activeLinesById.get(originLine.lineId()),
			originLine,
			transferOriginLine.get(),
			activeLinesById.get(destinationLine.lineId()),
			transferDestinationLine.get(),
			destinationLine,
			activeStationsById.get(stationId)
		));
	}

	private int transferAccessibilityRank(
		TransferRoute route,
		RouteProfileWeight profileWeight,
		Predicate<String> stairOnlyAccess
	) {
		if (profileWeight.blocksStairOnlyAccess() && stairOnlyAccess.test(route.transferStation().id())) {
			return 1;
		}
		return 0;
	}

	private int transferCandidateCost(
		TransferRoute route,
		RouteProfileWeight profileWeight,
		Predicate<String> stairOnlyAccess,
		Predicate<String> lowAccessibilityData
	) {
		String transferStationId = route.transferStation().id();
		int stairOnlyCost = stairOnlyAccess.test(transferStationId)
			? profileWeight.stairOnlyAccessPenalty()
			: 0;
		int lowDataCost = lowAccessibilityData.test(transferStationId)
			? profileWeight.lowDataConfidencePenalty()
			: 0;
		return route.stopCount() * 3 + profileWeight.transferPenalty() + stairOnlyCost + lowDataCost;
	}

	private List<StationLine> stationLines(String stationId) {
		return activeStationLines.stream()
			.filter(stationLine -> stationLine.stationId().equals(stationId))
			.toList();
	}

	private Optional<StationLine> stationLineFor(List<StationLine> stationLines, String lineId) {
		return stationLines.stream()
			.filter(stationLine -> stationLine.lineId().equals(lineId))
			.findFirst();
	}
}
