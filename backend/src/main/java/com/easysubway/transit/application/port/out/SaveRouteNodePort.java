package com.easysubway.transit.application.port.out;

import com.easysubway.transit.domain.RouteNode;

public interface SaveRouteNodePort {

	void saveRouteNode(RouteNode routeNode);
}
