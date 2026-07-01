package com.easysubway.route.application.port.out;

import com.easysubway.route.domain.EtaSource;
import com.easysubway.route.domain.RouteSearchDashboardSummary;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteWarningCode;
import java.util.List;

public interface SummarizeRouteSearchPort {

	RouteSearchDashboardSummary summarizeRouteSearches();

	List<RouteSearchStationPair> loadRouteSearchStationPairsForDashboard();

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
