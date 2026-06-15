package com.easysubway.route.adapter.out.persistence;

import com.easysubway.route.application.port.out.LoadRouteSearchPort;
import com.easysubway.route.application.port.out.SaveRouteFeedbackPort;
import com.easysubway.route.application.port.out.SaveRouteSearchPort;
import com.easysubway.route.domain.RouteFeedback;
import com.easysubway.route.domain.RouteSearchResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryRouteSearchRepository
	implements LoadRouteSearchPort, SaveRouteSearchPort, SaveRouteFeedbackPort {

	static final int MAX_STORED_ROUTE_SEARCHES = 1_000;
	static final int MAX_STORED_ROUTE_FEEDBACKS = 5_000;

	private final Map<String, RouteSearchResult> routeSearches = new LinkedHashMap<>();
	private final Map<String, RouteFeedback> routeFeedbacks = new LinkedHashMap<>();

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

	@Override
	public RouteFeedback saveRouteFeedback(RouteFeedback feedback) {
		synchronized (routeFeedbacks) {
			routeFeedbacks.put(feedback.feedbackId(), feedback);
			evictOldestRouteFeedbacks();
			return feedback;
		}
	}

	private void evictOldestRouteSearches() {
		// 공개 API 요청으로 생성되는 임시 결과가 프로세스 메모리에 무한히 쌓이지 않게 한다.
		while (routeSearches.size() > MAX_STORED_ROUTE_SEARCHES) {
			String oldestRouteSearchId = routeSearches.keySet().iterator().next();
			routeSearches.remove(oldestRouteSearchId);
		}
	}

	private void evictOldestRouteFeedbacks() {
		// 피드백은 운영 DB 전환 전까지 임시 보관하되 공개 요청으로 메모리가 무한 증가하지 않게 한다.
		while (routeFeedbacks.size() > MAX_STORED_ROUTE_FEEDBACKS) {
			String oldestFeedbackId = routeFeedbacks.keySet().iterator().next();
			routeFeedbacks.remove(oldestFeedbackId);
		}
	}
}
