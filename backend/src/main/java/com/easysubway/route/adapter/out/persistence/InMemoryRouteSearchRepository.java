package com.easysubway.route.adapter.out.persistence;

import com.easysubway.route.application.port.out.LoadRouteSearchPort;
import com.easysubway.route.application.port.out.SaveRouteFeedbackPort;
import com.easysubway.route.application.port.out.SaveRouteSearchPort;
import com.easysubway.route.domain.RouteFeedback;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.user.application.port.out.AnonymizeUserRouteFeedbackPort;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!prod")
public class InMemoryRouteSearchRepository
	implements LoadRouteSearchPort, SaveRouteSearchPort, SaveRouteFeedbackPort, AnonymizeUserRouteFeedbackPort {

	static final int MAX_STORED_ROUTE_SEARCHES = 1_000;
	static final int MAX_STORED_ROUTE_FEEDBACKS = 5_000;
	private static final String DELETED_USER_ID = "deleted-user";
	private static final String DELETED_COMMENT = "사용자 데이터 삭제로 경로 피드백 내용이 삭제되었습니다.";

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

	@Override
	public int anonymizeRouteFeedbacksByUserId(String userId) {
		synchronized (routeFeedbacks) {
			int anonymizedCount = 0;
			for (RouteFeedback feedback : routeFeedbacks.values()) {
				if (!feedback.userId().equals(userId)) {
					continue;
				}
				routeFeedbacks.put(feedback.feedbackId(), anonymized(feedback));
				anonymizedCount++;
			}
			return anonymizedCount;
		}
	}

	private RouteFeedback anonymized(RouteFeedback feedback) {
		// 피드백 평점은 운영 개선 지표로 남기고, 작성자와 자유 입력 코멘트만 제거한다.
		return new RouteFeedback(
			feedback.feedbackId(),
			feedback.routeSearchId(),
			DELETED_USER_ID,
			feedback.rating(),
			DELETED_COMMENT,
			feedback.createdAt()
		);
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
