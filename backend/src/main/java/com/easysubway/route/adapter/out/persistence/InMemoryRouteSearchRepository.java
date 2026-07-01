package com.easysubway.route.adapter.out.persistence;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.out.LoadRouteSearchPort;
import com.easysubway.route.application.port.out.SaveRouteFeedbackPort;
import com.easysubway.route.application.port.out.SaveRouteSearchPort;
import com.easysubway.route.application.port.out.SummarizeRouteFeedbackPort;
import com.easysubway.route.application.port.out.SummarizeRouteSearchPort;
import com.easysubway.route.application.port.out.SummarizeRouteSearchPort.RouteSearchBlockedReasons;
import com.easysubway.route.application.port.out.SummarizeRouteSearchPort.RouteSearchStationPair;
import com.easysubway.route.domain.RouteFeedback;
import com.easysubway.route.domain.RouteFeedbackDashboardSummary;
import com.easysubway.route.domain.RouteFeedbackDashboardSummary.EtaCalibrationBucket;
import com.easysubway.route.domain.RouteFeedbackDashboardSummary.RecentBlockedFeedback;
import com.easysubway.route.domain.RouteFeedbackRating;
import com.easysubway.route.domain.RouteSearchDashboardSummary;
import com.easysubway.route.domain.RouteSearchDashboardSummary.MobilityTypeCount;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.user.application.port.out.AnonymizeUserRouteFeedbackPort;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!prod & !staging & !release & !prod-like")
public class InMemoryRouteSearchRepository
	implements LoadRouteSearchPort, SaveRouteSearchPort, SaveRouteFeedbackPort, SummarizeRouteFeedbackPort,
	SummarizeRouteSearchPort, AnonymizeUserRouteFeedbackPort {

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
	public RouteFeedbackDashboardSummary summarizeRouteFeedbacks() {
		synchronized (routeFeedbacks) {
			long helpfulCount = countByRating(RouteFeedbackRating.HELPFUL);
			long notHelpfulCount = countByRating(RouteFeedbackRating.NOT_HELPFUL);
			long blockedByRealWorldCount = countByRating(RouteFeedbackRating.BLOCKED_BY_REAL_WORLD);
			return new RouteFeedbackDashboardSummary(
				routeFeedbacks.size(),
				helpfulCount,
				notHelpfulCount,
				blockedByRealWorldCount,
				recentBlockedFeedbacks(),
				etaCalibrationBuckets()
			);
		}
	}

	@Override
	public RouteSearchDashboardSummary summarizeRouteSearches() {
		synchronized (routeSearches) {
			long foundCount = countByStatus(RouteSearchStatus.FOUND);
			long blockedCount = countByStatus(RouteSearchStatus.BLOCKED);
			return new RouteSearchDashboardSummary(
				routeSearches.size(),
				foundCount,
				blockedCount,
				mobilityTypeCounts()
			);
		}
	}

	@Override
	public List<RouteSearchStationPair> loadRouteSearchStationPairsForDashboard() {
		synchronized (routeSearches) {
			return routeSearches.values()
				.stream()
				.map(routeSearch -> new RouteSearchStationPair(
					routeSearch.originStationId(),
					routeSearch.destinationStationId()
				))
				.toList();
		}
	}

	@Override
	public List<RouteSearchBlockedReasons> loadRouteSearchBlockedReasonsForDashboard() {
		synchronized (routeSearches) {
			return routeSearches.values()
				.stream()
				.filter(routeSearch -> routeSearch.status() == RouteSearchStatus.BLOCKED)
				.map(routeSearch -> new RouteSearchBlockedReasons(routeSearch.blockedReasons()))
				.toList();
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
			feedback.itineraryId(),
			feedback.mobilityType(),
			feedback.constraintMode(),
			feedback.etaSource(),
			feedback.etaOffsetBucket(),
			feedback.etaFeedbackOptedIn(),
			feedback.createdAt()
		);
	}

	private long countByRating(RouteFeedbackRating rating) {
		return routeFeedbacks.values()
			.stream()
			.filter(feedback -> feedback.rating() == rating)
			.count();
	}

	private List<RecentBlockedFeedback> recentBlockedFeedbacks() {
		synchronized (routeSearches) {
			return routeFeedbacks.values()
				.stream()
				.filter(feedback -> feedback.rating() == RouteFeedbackRating.BLOCKED_BY_REAL_WORLD)
				.map(this::toRecentBlockedFeedback)
				.filter(Objects::nonNull)
				.sorted(Comparator.comparing(RecentBlockedFeedback::createdAt).reversed())
				.limit(5)
				.toList();
		}
	}

	private RecentBlockedFeedback toRecentBlockedFeedback(RouteFeedback feedback) {
		RouteSearchResult routeSearch = routeSearches.get(feedback.routeSearchId());
		if (routeSearch == null) {
			return null;
		}
		return new RecentBlockedFeedback(
			routeSearch.originStationName(),
			routeSearch.destinationStationName(),
			routeSearch.mobilityType(),
			feedback.createdAt()
		);
	}

	private List<EtaCalibrationBucket> etaCalibrationBuckets() {
		Map<EtaCalibrationKey, Long> counts = new LinkedHashMap<>();
		routeFeedbacks.values()
			.stream()
			.filter(RouteFeedback::etaFeedbackOptedIn)
			.filter(feedback -> feedback.mobilityType() != null
				&& feedback.constraintMode() != null
				&& feedback.etaSource() != null
				&& feedback.etaOffsetBucket() != null)
			.sorted(Comparator
				.comparing(RouteFeedback::mobilityType)
				.thenComparing(RouteFeedback::constraintMode)
				.thenComparing(RouteFeedback::etaSource)
				.thenComparing(RouteFeedback::etaOffsetBucket))
			.forEach(feedback -> counts.merge(
				new EtaCalibrationKey(
					feedback.mobilityType(),
					feedback.constraintMode(),
					feedback.etaSource(),
					feedback.etaOffsetBucket()
				),
				1L,
				Long::sum
			));
		return counts.entrySet()
			.stream()
			.map(entry -> new EtaCalibrationBucket(
				entry.getKey().mobilityType(),
				entry.getKey().constraintMode(),
				entry.getKey().etaSource(),
				entry.getKey().etaOffsetBucket(),
				entry.getValue()
			))
			.toList();
	}

	private long countByStatus(RouteSearchStatus status) {
		return routeSearches.values()
			.stream()
			.filter(routeSearch -> routeSearch.status() == status)
			.count();
	}

	private List<MobilityTypeCount> mobilityTypeCounts() {
		return Arrays.stream(MobilityType.values())
			.map(mobilityType -> new MobilityTypeCount(mobilityType, countByMobilityType(mobilityType)))
			.filter(row -> row.count() > 0)
			.toList();
	}

	private long countByMobilityType(MobilityType mobilityType) {
		return routeSearches.values()
			.stream()
			.filter(routeSearch -> routeSearch.mobilityType() == mobilityType)
			.count();
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

	private record EtaCalibrationKey(
		MobilityType mobilityType,
		com.easysubway.route.domain.ConstraintMode constraintMode,
		com.easysubway.route.domain.EtaSource etaSource,
		com.easysubway.route.domain.RouteEtaOffsetBucket etaOffsetBucket
	) {
	}
}
