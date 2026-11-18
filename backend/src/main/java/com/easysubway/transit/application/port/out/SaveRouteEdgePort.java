package com.easysubway.transit.application.port.out;

import com.easysubway.transit.domain.RouteEdge;

public interface SaveRouteEdgePort {

	void saveRouteEdge(RouteEdge routeEdge);

	default void saveRouteEdge(RouteEdge routeEdge, String updatedBy) {
		saveRouteEdge(routeEdge);
	}
}
