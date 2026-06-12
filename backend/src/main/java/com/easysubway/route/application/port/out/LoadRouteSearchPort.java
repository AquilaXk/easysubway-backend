package com.easysubway.route.application.port.out;

import com.easysubway.route.domain.RouteSearchResult;
import java.util.Optional;

public interface LoadRouteSearchPort {

	Optional<RouteSearchResult> loadRouteSearch(String routeSearchId);
}
