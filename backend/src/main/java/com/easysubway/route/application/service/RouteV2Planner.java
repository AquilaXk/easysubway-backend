package com.easysubway.route.application.service;

import com.easysubway.route.application.port.in.RouteSearchUseCase;
import com.easysubway.route.application.port.in.SearchRouteCommand;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.SearchRouteV2Command;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteV2Plan;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteV2Status;
import com.easysubway.route.domain.EtaSource;
import com.easysubway.route.domain.RouteNotFoundException;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RouteV2Planner implements RouteV2SearchUseCase {

	private static final String PLANNER_ADR = "tools/routes/route-algorithm-v2-adr.json";

	private final RouteSearchUseCase routeSearchUseCase;

	public RouteV2Planner(RouteSearchUseCase routeSearchUseCase) {
		this.routeSearchUseCase = routeSearchUseCase;
	}

	@Override
	public RouteV2Plan search(SearchRouteV2Command command) {
		try {
			SearchRouteCommand searchRouteCommand = toSearchRouteCommand(command);
			List<RouteSearchResult> itineraries = routeSearchUseCase.searchRouteAlternatives(
				searchRouteCommand,
				command.alternativeCount()
			);
			return new RouteV2Plan(
				itineraries,
				statusesOf(itineraries, command.useRealtime()),
				PLANNER_ADR
			);
		} catch (RouteNotFoundException exception) {
			return new RouteV2Plan(List.of(), List.of(RouteV2Status.NO_TIMETABLE_SERVICE), PLANNER_ADR);
		}
	}

	private List<RouteV2Status> statusesOf(List<RouteSearchResult> itineraries, boolean useRealtime) {
		List<RouteV2Status> statuses = new ArrayList<>();
		for (RouteSearchResult itinerary : itineraries) {
			statuses.add(statusOf(itinerary));
			if (usesPlannedEtaAfterRealtimeRequest(itinerary, useRealtime)) {
				statuses.add(RouteV2Status.REALTIME_UNAVAILABLE_PLANNED_USED);
			}
		}
		return List.copyOf(statuses.stream().distinct().toList());
	}

	private boolean usesPlannedEtaAfterRealtimeRequest(RouteSearchResult itinerary, boolean useRealtime) {
		if (!useRealtime || itinerary.status() != RouteSearchStatus.FOUND) {
			return false;
		}
		return itinerary.etaSource() == EtaSource.STATIC_BACKEND_ESTIMATE
			|| itinerary.etaSource() == EtaSource.PLANNED
			|| itinerary.etaSource() == EtaSource.FALLBACK;
	}

	private RouteV2Status statusOf(RouteSearchResult itinerary) {
		return itinerary.status() == RouteSearchStatus.BLOCKED
			? RouteV2Status.BLOCKED_ACCESSIBILITY
			: RouteV2Status.valueOf(itinerary.status().name());
	}

	private SearchRouteCommand toSearchRouteCommand(SearchRouteV2Command command) {
		return new SearchRouteCommand(
			command.originStationId(),
			command.destinationStationId(),
			command.mobilityType(),
			command.constraintMode(),
			command.maxTransfers(),
			command.departureTime(),
			command.useRealtime()
		);
	}
}
