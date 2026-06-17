package com.easysubway.route.application.port.out;

import com.easysubway.route.domain.RouteSearchDashboardSummary;
import java.util.List;

public interface SummarizeRouteSearchPort {

	RouteSearchDashboardSummary summarizeRouteSearches();

	List<RouteSearchStationPair> loadRouteSearchStationPairsForDashboard();

	record RouteSearchStationPair(String originStationId, String destinationStationId) {
	}
}
