package com.easysubway.route.application.port.out;

import com.easysubway.route.domain.EtaSource;
import com.easysubway.route.domain.RouteSearchDashboardSummary;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteWarningCode;
import java.util.List;

public interface SummarizeRouteSearchPort {

	RouteSearchDashboardSummary summarizeRouteSearches();

	List<RouteSearchStationPair> loadRouteSearchStationPairsForDashboard();

	/** 차단(BLOCKED)된 경로 검색의 출발·도착역 쌍만 돌려준다(차단 상위 역 랭킹 집계용). */
	List<RouteSearchStationPair> loadBlockedRouteSearchStationPairsForDashboard();

	List<RouteSearchBlockedReasons> loadRouteSearchBlockedReasonsForDashboard();

	List<RouteSearchQualitySignals> loadRouteSearchQualitySignalsForDashboard();

	record RouteSearchStationPair(String originStationId, String destinationStationId) {
	}

	record RouteSearchBlockedReasons(List<String> blockedReasons) {

		public RouteSearchBlockedReasons {
			blockedReasons = List.copyOf(blockedReasons);
		}
	}

	record RouteSearchQualitySignals(
		RouteSearchStatus status,
		EtaSource etaSource,
		List<RouteWarningCode> warningCodes
	) {

		public RouteSearchQualitySignals {
			warningCodes = List.copyOf(warningCodes);
		}
	}
}
