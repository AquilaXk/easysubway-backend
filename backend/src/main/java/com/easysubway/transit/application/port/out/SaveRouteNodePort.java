package com.easysubway.transit.application.port.out;

import com.easysubway.transit.domain.RouteNode;

public interface SaveRouteNodePort {

	void saveRouteNode(RouteNode routeNode);

	default void saveRouteNode(RouteNode routeNode, String updatedBy) {
		saveRouteNode(routeNode);
	}
}
