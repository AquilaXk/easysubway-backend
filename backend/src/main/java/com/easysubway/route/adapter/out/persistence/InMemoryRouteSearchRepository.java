package com.easysubway.route.adapter.out.persistence;

import com.easysubway.route.application.port.out.LoadRouteSearchPort;
import com.easysubway.route.application.port.out.SaveRouteSearchPort;
import com.easysubway.route.domain.RouteSearchResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryRouteSearchRepository implements LoadRouteSearchPort, SaveRouteSearchPort {

	static final int MAX_STORED_ROUTE_SEARCHES = 1_000;

	private final Map<String, RouteSearchResult> routeSearches = new LinkedHashMap<>();

	@Override
	public Optional<RouteSearchResult> loadRouteSearch(String routeSearchId) {
		synchronized (routeSearches) {
			return Optional.ofNullable(routeSearches.get(routeSearchId));
		}
	}

	@Override
	public RouteSearchResult saveRouteSearch(RouteSearchResult routeSearchResult) {
		synchronized (routeSearches) {
			routeSearches.put(routeSearchResult.routeSearchId(), routeSearchResult);
			evictOldestRouteSearches();
			return routeSearchResult;
		}
	}

	private void evictOldestRouteSearches() {
		// 공개 API 요청으로 생성되는 임시 결과가 프로세스 메모리에 무한히 쌓이지 않게 한다.
		while (routeSearches.size() > MAX_STORED_ROUTE_SEARCHES) {
			String oldestRouteSearchId = routeSearches.keySet().iterator().next();
			routeSearches.remove(oldestRouteSearchId);
		}
	}
}
