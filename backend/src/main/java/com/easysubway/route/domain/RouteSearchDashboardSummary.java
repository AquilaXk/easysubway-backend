package com.easysubway.route.domain;

import com.easysubway.profile.domain.MobilityType;
import java.util.List;

public record RouteSearchDashboardSummary(
	long totalCount,
	long foundCount,
	long blockedCount,
	List<MobilityTypeCount> mobilityTypeCounts
) {

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
}
