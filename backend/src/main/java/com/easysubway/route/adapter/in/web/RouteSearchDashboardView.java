package com.easysubway.route.adapter.in.web;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.domain.RouteSearchDashboardSummary;
import java.util.List;

public record RouteSearchDashboardView(
	long totalCount,
	long foundCount,
	long blockedCount,
	List<MobilityTypeCountRow> mobilityTypeRows,
	List<RegionUsageCountRow> regionUsageRows,
	List<BlockedReasonCountRow> blockedReasonRows
) {

	static RouteSearchDashboardView from(RouteSearchDashboardSummary summary) {
		return new RouteSearchDashboardView(
			summary.totalCount(),
			summary.foundCount(),
			summary.blockedCount(),
			summary.mobilityTypeCounts()
				.stream()
				.map(row -> new MobilityTypeCountRow(mobilityTypeLabel(row.mobilityType()), row.count()))
				.toList(),
			summary.regionUsageCounts()
				.stream()
				.map(row -> new RegionUsageCountRow(row.region(), row.originCount(), row.destinationCount()))
				.toList(),
			summary.blockedReasonCounts()
				.stream()
				.map(row -> new BlockedReasonCountRow(row.reason(), row.count()))
				.toList()
		);
	}

	public record MobilityTypeCountRow(String label, long count) {
	}

	public record RegionUsageCountRow(String region, long originCount, long destinationCount) {
	}

	public record BlockedReasonCountRow(String reason, long count) {
	}

	private static String mobilityTypeLabel(MobilityType mobilityType) {
		return switch (mobilityType) {
			case SENIOR -> "고령자";
			case STROLLER -> "유모차 동반";
			case WHEELCHAIR -> "휠체어 사용자";
			case PREGNANT -> "임산부";
			case TEMPORARY_INJURY -> "일시 부상";
			case LUGGAGE -> "큰 짐 동반";
		};
	}
}
