package com.easysubway.route.application.port.in;

import com.easysubway.route.domain.RouteFeedback;
import com.easysubway.route.domain.RouteSearchResult;

public interface RouteSearchUseCase {

	RouteSearchResult searchRoute(SearchRouteCommand command);

	RouteSearchResult getRouteSearch(String routeSearchId);

	RouteFeedback submitRouteFeedback(SubmitRouteFeedbackCommand command);
}
