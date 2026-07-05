package com.easysubway.route.application.service;

import com.easysubway.route.application.port.in.RouteSearchDashboardUseCase;
import com.easysubway.route.application.port.out.SummarizeRouteSearchPort;
import com.easysubway.route.application.port.out.SummarizeRouteSearchPort.RouteSearchBlockedReasons;
import com.easysubway.route.application.port.out.SummarizeRouteSearchPort.RouteSearchQualitySignals;
import com.easysubway.route.application.port.out.SummarizeRouteSearchPort.RouteSearchStationPair;
import com.easysubway.route.domain.BlockedStationRanking;
import com.easysubway.route.domain.EtaSource;
import com.easysubway.route.domain.RouteSearchDashboardSummary;
import com.easysubway.route.domain.RouteSearchDashboardSummary.BlockedReasonCount;
import com.easysubway.route.domain.RouteSearchDashboardSummary.EtaSourceCount;
import com.easysubway.route.domain.RouteSearchDashboardSummary.FallbackReasonCount;
import com.easysubway.route.domain.RouteSearchDashboardSummary.RegionUsageCount;
import com.easysubway.route.domain.RouteSearchDashboardSummary.RouteQualitySignalCount;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteWarningCode;
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
		List<RouteSearchQualitySignals> qualitySignals = summarizeRouteSearchPort.loadRouteSearchQualitySignalsForDashboard();
		return new RouteSearchDashboardSummary(
			summary.totalCount(),
			summary.foundCount(),
			summary.blockedCount(),
			summary.mobilityTypeCounts(),
			regionUsageCounts(summarizeRouteSearchPort.loadRouteSearchStationPairsForDashboard()),
			blockedReasonCounts(summarizeRouteSearchPort.loadRouteSearchBlockedReasonsForDashboard()),
			etaSourceCounts(qualitySignals),
			fallbackReasonCounts(qualitySignals),
			routeQualitySignalCounts(qualitySignals)
		);
	}

	@Override
	public List<BlockedStationRanking> topBlockedStations(int limit) {
		if (limit <= 0) {
			return List.of();
		}
		List<RouteSearchStationPair> blockedPairs = summarizeRouteSearchPort.loadBlockedRouteSearchStationPairsForDashboard();
		Map<String, Long> blockedCountByStationId = new HashMap<>();
		for (RouteSearchStationPair pair : blockedPairs) {
			mergeStation(blockedCountByStationId, pair.originStationId());
			mergeStation(blockedCountByStationId, pair.destinationStationId());
		}
		if (blockedCountByStationId.isEmpty()) {
			return List.of();
		}
		// 이름 해석은 전체 역을 1회 벌크 로드해 map으로(역별 반복 조회 금지).
		Map<String, String> stationNamesById = loadTransitMasterPort.loadStations()
			.stream()
			.collect(Collectors.toMap(Station::id, Station::nameKo, (first, second) -> first));
		return blockedCountByStationId.entrySet()
			.stream()
			.sorted(Comparator.comparingLong(Map.Entry<String, Long>::getValue).reversed()
				.thenComparing(Map.Entry::getKey))
			.limit(limit)
			.map(entry -> new BlockedStationRanking(
				entry.getKey(), stationNamesById.get(entry.getKey()), entry.getValue()))
			.toList();
	}

	private static void mergeStation(Map<String, Long> counts, String stationId) {
		if (stationId != null && !stationId.isBlank()) {
			counts.merge(stationId, 1L, Long::sum);
		}
	}

	private List<EtaSourceCount> etaSourceCounts(List<RouteSearchQualitySignals> qualitySignals) {
		Map<EtaSource, Long> countsByEtaSource = new HashMap<>();
		for (RouteSearchQualitySignals row : qualitySignals) {
			if (row.status() != RouteSearchStatus.FOUND) {
				continue;
			}
			countsByEtaSource.merge(row.etaSource(), 1L, Long::sum);
		}
		return countsByEtaSource.entrySet()
			.stream()
			.map(entry -> new EtaSourceCount(entry.getKey(), entry.getValue()))
			.sorted(Comparator
				.comparingLong(EtaSourceCount::count)
				.reversed()
				.thenComparing(row -> row.etaSource().name()))
			.toList();
	}

	private List<FallbackReasonCount> fallbackReasonCounts(List<RouteSearchQualitySignals> qualitySignals) {
		Map<String, Long> countsByReason = new HashMap<>();
		for (RouteSearchQualitySignals row : qualitySignals) {
			if (row.status() == RouteSearchStatus.BLOCKED) {
				countsByReason.merge("ROUTE_GRAPH_OR_STRICT_ACCESSIBILITY_BLOCK", 1L, Long::sum);
			}
			if (row.etaSource() == EtaSource.FALLBACK) {
				countsByReason.merge("PROVIDER_OUTAGE_OR_STALE_REALTIME", 1L, Long::sum);
			}
			for (RouteWarningCode warningCode : row.warningCodes()) {
				switch (warningCode) {
					case LOW_DATA_CONFIDENCE -> countsByReason.merge("LOW_DATA_CONFIDENCE", 1L, Long::sum);
					case STALE_ACCESSIBILITY_DATA -> countsByReason.merge("STALE_ACCESSIBILITY_DATA", 1L, Long::sum);
					case STAIR_ONLY_ACCESS -> countsByReason.merge("STRICT_STAIR_ONLY_ACCESS", 1L, Long::sum);
				}
			}
		}
		return countsByReason.entrySet()
			.stream()
			.map(entry -> new FallbackReasonCount(entry.getKey(), entry.getValue()))
			.sorted(Comparator
				.comparingLong(FallbackReasonCount::count)
				.reversed()
				.thenComparing(FallbackReasonCount::reason))
			.toList();
	}

	private List<RouteQualitySignalCount> routeQualitySignalCounts(List<RouteSearchQualitySignals> qualitySignals) {
		Map<String, Long> countsBySignal = new HashMap<>();
		for (RouteSearchQualitySignals row : qualitySignals) {
			if (row.etaSource() == EtaSource.FALLBACK) {
				countsBySignal.merge("PROVIDER_OUTAGE", 1L, Long::sum);
			}
			if (row.status() == RouteSearchStatus.BLOCKED
				|| row.warningCodes().contains(RouteWarningCode.LOW_DATA_CONFIDENCE)
				|| row.warningCodes().contains(RouteWarningCode.STALE_ACCESSIBILITY_DATA)) {
				countsBySignal.merge("ROUTE_GRAPH_DATA_QUALITY", 1L, Long::sum);
			}
			if (row.status() == RouteSearchStatus.BLOCKED
				|| row.warningCodes().contains(RouteWarningCode.STAIR_ONLY_ACCESS)) {
				countsBySignal.merge("STRICT_ACCESSIBILITY_BLOCK", 1L, Long::sum);
			}
		}
		return countsBySignal.entrySet()
			.stream()
			.map(entry -> new RouteQualitySignalCount(entry.getKey(), entry.getValue()))
			.sorted(Comparator
				.comparingLong(RouteQualitySignalCount::count)
				.reversed()
				.thenComparing(RouteQualitySignalCount::signal))
			.toList();
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
