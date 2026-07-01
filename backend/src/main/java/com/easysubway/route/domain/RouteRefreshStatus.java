package com.easysubway.route.domain;

public enum RouteRefreshStatus {
	UPDATED_ETA,
	UNCHANGED,
	STALE_FALLBACK,
	REROUTE_REQUIRED
}
