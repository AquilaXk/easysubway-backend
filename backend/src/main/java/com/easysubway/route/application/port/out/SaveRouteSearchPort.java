package com.easysubway.route.application.port.out;

import com.easysubway.route.domain.RouteSearchResult;

public interface SaveRouteSearchPort {

	RouteSearchResult saveRouteSearch(RouteSearchResult routeSearchResult);
}
