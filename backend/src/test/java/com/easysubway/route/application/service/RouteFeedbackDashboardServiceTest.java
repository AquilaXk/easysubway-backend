package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.adapter.out.persistence.InMemoryRouteSearchRepository;
import com.easysubway.route.domain.RouteFeedback;
import com.easysubway.route.domain.RouteFeedbackRating;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("경로 피드백 현황 서비스")
class RouteFeedbackDashboardServiceTest {

	@Test
	@DisplayName("전체 경로 피드백을 평점별로 집계한다")
	void summarizeRouteFeedbacksByRating() {
		var repository = new InMemoryRouteSearchRepository();
		repository.saveRouteFeedback(routeFeedback("feedback-1", RouteFeedbackRating.HELPFUL));
		repository.saveRouteFeedback(routeFeedback("feedback-2", RouteFeedbackRating.NOT_HELPFUL));
		repository.saveRouteFeedback(routeFeedback("feedback-3", RouteFeedbackRating.BLOCKED_BY_REAL_WORLD));
		var service = new RouteFeedbackDashboardService(repository);

		var summary = service.summarizeRouteFeedbacks();

		assertThat(summary.totalCount()).isEqualTo(3);
		assertThat(summary.helpfulCount()).isEqualTo(1);
		assertThat(summary.notHelpfulCount()).isEqualTo(1);
		assertThat(summary.blockedByRealWorldCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("최근 현장 차단 신고를 사용자 정보 없이 최신순으로 집계한다")
	void summarizeRecentBlockedRouteFeedbacks() {
		var repository = new InMemoryRouteSearchRepository();
		repository.saveRouteSearch(routeSearch("route-search-1", "상록수", "사당", MobilityType.SENIOR));
		repository.saveRouteSearch(routeSearch("route-search-2", "서울역", "시청", MobilityType.WHEELCHAIR));
		repository.saveRouteFeedback(routeFeedback(
			"feedback-1",
			"route-search-1",
			RouteFeedbackRating.BLOCKED_BY_REAL_WORLD,
			"anonymous-user-1",
			"엘리베이터가 막혀 있었어요",
			LocalDateTime.of(2026, 6, 17, 10, 0)
		));
		repository.saveRouteFeedback(routeFeedback(
			"feedback-2",
			"route-search-2",
			RouteFeedbackRating.BLOCKED_BY_REAL_WORLD,
			"anonymous-user-2",
			"공사로 우회가 필요했어요",
			LocalDateTime.of(2026, 6, 17, 11, 0)
		));
		repository.saveRouteFeedback(routeFeedback(
			"feedback-3",
			"route-search-1",
			RouteFeedbackRating.HELPFUL,
			"anonymous-user-3",
			"도움이 됐어요",
			LocalDateTime.of(2026, 6, 17, 12, 0)
		));
		var service = new RouteFeedbackDashboardService(repository);

		var summary = service.summarizeRouteFeedbacks();

		assertThat(summary.recentBlockedFeedbacks())
			.extracting("originStationName", "destinationStationName", "mobilityType", "createdAt")
			.containsExactly(
				tuple("서울역", "시청", MobilityType.WHEELCHAIR, LocalDateTime.of(2026, 6, 17, 11, 0)),
				tuple("상록수", "사당", MobilityType.SENIOR, LocalDateTime.of(2026, 6, 17, 10, 0))
			);
	}

	private RouteFeedback routeFeedback(String feedbackId, RouteFeedbackRating rating) {
		return routeFeedback(
			feedbackId,
			"route-search-1",
			rating,
			"anonymous-user-1",
			"경로 피드백 " + feedbackId,
			LocalDateTime.of(2026, 6, 17, 10, 0)
		);
	}

	private RouteFeedback routeFeedback(
		String feedbackId,
		String routeSearchId,
		RouteFeedbackRating rating,
		String userId,
		String comment,
		LocalDateTime createdAt
	) {
		return new RouteFeedback(
			feedbackId,
			routeSearchId,
			userId,
			rating,
			comment,
			createdAt
		);
	}

	private RouteSearchResult routeSearch(
		String routeSearchId,
		String originStationName,
		String destinationStationName,
		MobilityType mobilityType
	) {
		return new RouteSearchResult(
			routeSearchId,
			"station-origin-" + routeSearchId,
			originStationName,
			"station-destination-" + routeSearchId,
			destinationStationName,
			mobilityType,
			RouteSearchStatus.FOUND,
			"line-a",
			"테스트 노선",
			90,
			List.of(),
			List.of(),
			List.of(),
			LocalDateTime.of(2026, 6, 17, 9, 0)
		);
	}
}
