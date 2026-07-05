package com.easysubway.route.application.port.in;

import com.easysubway.route.domain.BlockedStationRanking;
import com.easysubway.route.domain.RouteSearchDashboardSummary;
import java.util.List;

public interface RouteSearchDashboardUseCase {

	RouteSearchDashboardSummary summarizeRouteSearches();

	/** 차단 검색에 가장 많이 얽힌 상위 {@code limit}개 역을 차단 횟수 내림차순으로 돌려준다. */
	List<BlockedStationRanking> topBlockedStations(int limit);
}
