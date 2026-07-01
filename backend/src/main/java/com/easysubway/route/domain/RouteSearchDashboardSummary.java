package com.easysubway.route.domain;

import com.easysubway.profile.domain.MobilityType;
import java.util.List;

public record RouteSearchDashboardSummary(
	long totalCount,
	long foundCount,
	long blockedCount,
	List<MobilityTypeCount> mobilityTypeCounts,
	List<RegionUsageCount> regionUsageCounts,
	List<BlockedReasonCount> blockedReasonCounts,
	List<EtaSourceCount> etaSourceCounts,
	List<FallbackReasonCount> fallbackReasonCounts,
	List<RouteQualitySignalCount> routeQualitySignalCounts
) {

	public RouteSearchDashboardSummary(
		long totalCount,
		long foundCount,
		long blockedCount,
		List<MobilityTypeCount> mobilityTypeCounts
	) {
		this(
			totalCount,
			foundCount,
			blockedCount,
			mobilityTypeCounts,
			List.of(),
			List.of(),
			List.of(),
			List.of(),
			List.of()
		);
	}

	public RouteSearchDashboardSummary(
		long totalCount,
		long foundCount,
		long blockedCount,
		List<MobilityTypeCount> mobilityTypeCounts,
		List<RegionUsageCount> regionUsageCounts
	) {
		this(
			totalCount,
			foundCount,
			blockedCount,
			mobilityTypeCounts,
			regionUsageCounts,
			List.of(),
			List.of(),
			List.of(),
			List.of()
		);
	}

	public RouteSearchDashboardSummary(
		long totalCount,
		long foundCount,
		long blockedCount,
		List<MobilityTypeCount> mobilityTypeCounts,
		List<RegionUsageCount> regionUsageCounts,
		List<BlockedReasonCount> blockedReasonCounts
	) {
		this(
			totalCount,
			foundCount,
			blockedCount,
			mobilityTypeCounts,
			regionUsageCounts,
			blockedReasonCounts,
			List.of(),
			List.of(),
			List.of()
		);
	}

	public RouteSearchDashboardSummary {
		if (totalCount < 0 || foundCount < 0 || blockedCount < 0) {
			throw new InvalidRouteSearchException("경로 검색 집계 수는 0 이상이어야 합니다.");
		}
		if (totalCount != foundCount + blockedCount) {
			throw new InvalidRouteSearchException("전체 경로 검색 수와 상태별 검색 수가 일치하지 않습니다.");
		}
		mobilityTypeCounts = List.copyOf(mobilityTypeCounts);
		long mobilityTotalCount = mobilityTypeCounts.stream()
			.mapToLong(MobilityTypeCount::count)
			.sum();
		if (totalCount != mobilityTotalCount) {
			throw new InvalidRouteSearchException("전체 경로 검색 수와 이동 프로필별 검색 수가 일치하지 않습니다.");
		}
		regionUsageCounts = List.copyOf(regionUsageCounts);
		blockedReasonCounts = List.copyOf(blockedReasonCounts);
		etaSourceCounts = List.copyOf(etaSourceCounts);
		fallbackReasonCounts = List.copyOf(fallbackReasonCounts);
		routeQualitySignalCounts = List.copyOf(routeQualitySignalCounts);
	}

	public record MobilityTypeCount(MobilityType mobilityType, long count) {

		public MobilityTypeCount {
			if (mobilityType == null) {
				throw new InvalidRouteSearchException("이동 프로필별 검색 집계에는 이동 프로필이 필요합니다.");
			}
			if (count < 0) {
				throw new InvalidRouteSearchException("이동 프로필별 경로 검색 수는 0 이상이어야 합니다.");
			}
		}
	}

	public record RegionUsageCount(String region, long originCount, long destinationCount) {

		public RegionUsageCount {
			if (region == null || region.isBlank()) {
				throw new InvalidRouteSearchException("지역별 경로 검색 집계에는 지역명이 필요합니다.");
			}
			if (originCount < 0 || destinationCount < 0) {
				throw new InvalidRouteSearchException("지역별 경로 검색 수는 0 이상이어야 합니다.");
			}
		}
	}

	public record BlockedReasonCount(String reason, long count) {

		public BlockedReasonCount {
			if (reason == null || reason.isBlank()) {
				throw new InvalidRouteSearchException("경로 검색 차단 사유 집계에는 사유가 필요합니다.");
			}
			if (count < 0) {
				throw new InvalidRouteSearchException("경로 검색 차단 사유 수는 0 이상이어야 합니다.");
			}
		}
	}

	public record EtaSourceCount(EtaSource etaSource, long count) {

		public EtaSourceCount {
			if (etaSource == null) {
				throw new InvalidRouteSearchException("ETA source 집계에는 ETA source가 필요합니다.");
			}
			if (count < 0) {
				throw new InvalidRouteSearchException("ETA source 집계 수는 0 이상이어야 합니다.");
			}
		}
	}

	public record FallbackReasonCount(String reason, long count) {

		public FallbackReasonCount {
			if (reason == null || reason.isBlank()) {
				throw new InvalidRouteSearchException("fallback 사유 집계에는 사유가 필요합니다.");
			}
			if (count < 0) {
				throw new InvalidRouteSearchException("fallback 사유 집계 수는 0 이상이어야 합니다.");
			}
		}
	}

	public record RouteQualitySignalCount(String signal, long count) {

		public RouteQualitySignalCount {
			if (signal == null || signal.isBlank()) {
				throw new InvalidRouteSearchException("경로 품질 신호 집계에는 신호명이 필요합니다.");
			}
			if (count < 0) {
				throw new InvalidRouteSearchException("경로 품질 신호 집계 수는 0 이상이어야 합니다.");
			}
		}
	}
}
