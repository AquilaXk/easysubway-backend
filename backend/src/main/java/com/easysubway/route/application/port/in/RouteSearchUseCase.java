package com.easysubway.route.application.port.in;

import com.easysubway.route.domain.InternalRouteResult;
import com.easysubway.route.domain.RouteFeedback;
import com.easysubway.route.domain.RouteRefreshResult;
import com.easysubway.route.domain.RouteSearchResult;
import java.util.List;

public interface RouteSearchUseCase {

	RouteSearchResult searchRoute(SearchRouteCommand command);

	List<RouteSearchResult> searchRouteAlternatives(SearchRouteCommand command, int alternativeCount);

	default void validateRouteSearch(SearchRouteCommand command) {
	}

	default List<RouteSearchResult> stabilizeTimetableRouteResults(
		SearchRouteCommand command,
		int alternativeCount,
		List<RouteSearchResult> timetableResults
	) {
		return List.copyOf(timetableResults);
	}

	default boolean supportsRealtimeOverlay() {
		return true;
	}

	InternalRouteResult searchInternalRoute(SearchInternalRouteCommand command);

	RouteSearchResult getRouteSearch(String routeSearchId);

	RouteRefreshResult refreshRoute(String routeSearchId);

	RouteFeedback submitRouteFeedback(SubmitRouteFeedbackCommand command);
}
