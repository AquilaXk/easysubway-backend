package com.easysubway.route.application.service;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteSearchUseCase;
import com.easysubway.route.application.port.in.SearchRouteCommand;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.EtaSource;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RouteV2Planner {

	private static final String PLANNER_ADR = "tools/routes/route-algorithm-v2-adr.json";

	private final RouteSearchUseCase routeSearchUseCase;

	public RouteV2Planner(RouteSearchUseCase routeSearchUseCase) {
		this.routeSearchUseCase = routeSearchUseCase;
	}

	public RouteV2Plan search(SearchRouteV2Command command) {
		SearchRouteCommand searchRouteCommand = command.toSearchRouteCommand();
		if (routeSearchUseCase instanceof RouteSearchService routeSearchService) {
			List<RouteSearchResult> itineraries = routeSearchService.searchRouteAlternatives(
				searchRouteCommand,
				command.alternativeCount()
			);
			return new RouteV2Plan(
				itineraries,
				statusesOf(itineraries, command.useRealtime()),
				PLANNER_ADR
			);
		}
		RouteSearchResult primary = routeSearchUseCase.searchRoute(searchRouteCommand);
		List<RouteSearchResult> itineraries = List.of(primary);
		return new RouteV2Plan(itineraries, statusesOf(itineraries, command.useRealtime()), PLANNER_ADR);
	}

	private List<String> statusesOf(List<RouteSearchResult> itineraries, boolean useRealtime) {
		List<String> statuses = new ArrayList<>();
		for (RouteSearchResult itinerary : itineraries) {
			statuses.add(statusOf(itinerary));
			if (usesPlannedEtaAfterRealtimeRequest(itinerary, useRealtime)) {
				statuses.add("REALTIME_UNAVAILABLE_PLANNED_USED");
			}
		}
		return List.copyOf(statuses.stream().distinct().toList());
	}

	private boolean usesPlannedEtaAfterRealtimeRequest(RouteSearchResult itinerary, boolean useRealtime) {
		if (!useRealtime || itinerary.status() != RouteSearchStatus.FOUND) {
			return false;
		}
		return itinerary.etaSource() == EtaSource.PLANNED || itinerary.etaSource() == EtaSource.FALLBACK;
	}

	private String statusOf(RouteSearchResult itinerary) {
		return itinerary.status() == RouteSearchStatus.BLOCKED ? "BLOCKED_ACCESSIBILITY" : itinerary.status().name();
	}

	public record SearchRouteV2Command(
		String originStationId,
		String destinationStationId,
		OffsetDateTime departureTime,
		MobilityType mobilityType,
		ConstraintMode constraintMode,
		boolean useRealtime,
		int maxTransfers,
		int alternativeCount
	) {

		private SearchRouteCommand toSearchRouteCommand() {
			return new SearchRouteCommand(
				originStationId,
				destinationStationId,
				mobilityType,
				constraintMode,
				maxTransfers,
				departureTime,
				useRealtime
			);
		}
	}

	public record RouteV2Plan(
		List<RouteSearchResult> itineraries,
		List<String> statuses,
		String plannerAdr
	) {
	}
}
