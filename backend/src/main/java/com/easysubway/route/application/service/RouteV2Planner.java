package com.easysubway.route.application.service;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteSearchUseCase;
import com.easysubway.route.application.port.in.SearchRouteCommand;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.RouteSearchResult;
import java.time.OffsetDateTime;
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
		RouteSearchResult primary = routeSearchUseCase.searchRoute(command.toSearchRouteCommand());
		return new RouteV2Plan(List.of(primary), PLANNER_ADR);
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
				maxTransfers
			);
		}
	}

	public record RouteV2Plan(
		List<RouteSearchResult> itineraries,
		String plannerAdr
	) {
	}
}
