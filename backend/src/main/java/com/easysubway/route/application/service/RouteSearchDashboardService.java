package com.easysubway.route.application.service;

import com.easysubway.route.application.port.in.RouteSearchDashboardUseCase;
import com.easysubway.route.application.port.out.SummarizeRouteSearchPort;
import com.easysubway.route.application.port.out.SummarizeRouteSearchPort.RouteSearchBlockedReasons;
import com.easysubway.route.application.port.out.SummarizeRouteSearchPort.RouteSearchStationPair;
import com.easysubway.route.domain.RouteSearchDashboardSummary;
import com.easysubway.route.domain.RouteSearchDashboardSummary.BlockedReasonCount;
import com.easysubway.route.domain.RouteSearchDashboardSummary.RegionUsageCount;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.domain.Station;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RouteSearchDashboardService implements RouteSearchDashboardUseCase {

	private final SummarizeRouteSearchPort summarizeRouteSearchPort;
	private final LoadTransitMasterPort loadTransitMasterPort;

	@Autowired
	public RouteSearchDashboardService(
		SummarizeRouteSearchPort summarizeRouteSearchPort,
		LoadTransitMasterPort loadTransitMasterPort
	) {
		this.summarizeRouteSearchPort = summarizeRouteSearchPort;
		this.loadTransitMasterPort = loadTransitMasterPort;
	}

	@Override
	public RouteSearchDashboardSummary summarizeRouteSearches() {
		RouteSearchDashboardSummary summary = summarizeRouteSearchPort.summarizeRouteSearches();
		return new RouteSearchDashboardSummary(
			summary.totalCount(),
			summary.foundCount(),
			summary.blockedCount(),
			summary.mobilityTypeCounts(),
			regionUsageCounts(summarizeRouteSearchPort.loadRouteSearchStationPairsForDashboard()),
			blockedReasonCounts(summarizeRouteSearchPort.loadRouteSearchBlockedReasonsForDashboard())
		);
	}

	private List<BlockedReasonCount> blockedReasonCounts(List<RouteSearchBlockedReasons> blockedReasonsRows) {
		Map<String, Long> countsByReason = new HashMap<>();
		for (RouteSearchBlockedReasons blockedReasonsRow : blockedReasonsRows) {
			for (String reason : blockedReasonsRow.blockedReasons()) {
				if (reason == null || reason.isBlank()) {
					continue;
				}
				String normalizedReason = reason.trim();
				countsByReason.merge(normalizedReason, 1L, Long::sum);
			}
		}
		return countsByReason.entrySet()
			.stream()
			.map(entry -> new BlockedReasonCount(entry.getKey(), entry.getValue()))
			.sorted(Comparator
				.comparingLong(BlockedReasonCount::count)
				.reversed()
				.thenComparing(BlockedReasonCount::reason))
			.toList();
	}

	private List<RegionUsageCount> regionUsageCounts(List<RouteSearchStationPair> stationPairs) {
		Map<String, String> stationRegionsById = loadTransitMasterPort.loadStations()
			.stream()
			.filter(station -> station.region() != null && !station.region().isBlank())
			.collect(Collectors.toMap(Station::id, Station::region));
		Map<String, MutableRegionUsageCount> countsByRegion = new HashMap<>();
		for (RouteSearchStationPair stationPair : stationPairs) {
			countOriginRegion(stationRegionsById.get(stationPair.originStationId()), countsByRegion);
			countDestinationRegion(stationRegionsById.get(stationPair.destinationStationId()), countsByRegion);
		}
		return countsByRegion.values()
			.stream()
			.map(MutableRegionUsageCount::toRegionUsageCount)
			.sorted(Comparator
				.comparingLong((RegionUsageCount row) -> row.originCount() + row.destinationCount())
				.reversed()
				.thenComparing(RegionUsageCount::region))
			.toList();
	}

	private void countOriginRegion(String region, Map<String, MutableRegionUsageCount> countsByRegion) {
		if (region == null || region.isBlank()) {
			return;
		}
		countsByRegion.computeIfAbsent(region, MutableRegionUsageCount::new)
			.incrementOriginCount();
	}

	private void countDestinationRegion(String region, Map<String, MutableRegionUsageCount> countsByRegion) {
		if (region == null || region.isBlank()) {
			return;
		}
		countsByRegion.computeIfAbsent(region, MutableRegionUsageCount::new)
			.incrementDestinationCount();
	}

	private static final class MutableRegionUsageCount {

		private final String region;
		private long originCount;
		private long destinationCount;

		private MutableRegionUsageCount(String region) {
			this.region = region;
		}

		private void incrementOriginCount() {
			originCount++;
		}

		private void incrementDestinationCount() {
			destinationCount++;
		}

		private RegionUsageCount toRegionUsageCount() {
			return new RegionUsageCount(region, originCount, destinationCount);
		}
	}
}
