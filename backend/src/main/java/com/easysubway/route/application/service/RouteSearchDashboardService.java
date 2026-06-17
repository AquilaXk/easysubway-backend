package com.easysubway.route.application.service;

import com.easysubway.route.application.port.in.RouteSearchDashboardUseCase;
import com.easysubway.route.application.port.out.SummarizeRouteSearchPort;
import com.easysubway.route.domain.RouteSearchDashboardSummary;
import org.springframework.stereotype.Service;

@Service
public class RouteSearchDashboardService implements RouteSearchDashboardUseCase {

	private final SummarizeRouteSearchPort summarizeRouteSearchPort;

	public RouteSearchDashboardService(SummarizeRouteSearchPort summarizeRouteSearchPort) {
		this.summarizeRouteSearchPort = summarizeRouteSearchPort;
	}

	@Override
	public RouteSearchDashboardSummary summarizeRouteSearches() {
		return summarizeRouteSearchPort.summarizeRouteSearches();
	}
}
