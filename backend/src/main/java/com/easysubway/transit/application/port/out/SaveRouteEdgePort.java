package com.easysubway.transit.application.port.out;

import com.easysubway.transit.domain.RouteEdge;

public interface SaveRouteEdgePort {

	void saveRouteEdge(RouteEdge routeEdge);
}
