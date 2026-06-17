package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.route.adapter.out.persistence.InMemoryRouteSearchRepository;
import com.easysubway.route.domain.RouteFeedback;
import com.easysubway.route.domain.RouteFeedbackRating;
import java.time.LocalDateTime;
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

	private RouteFeedback routeFeedback(String feedbackId, RouteFeedbackRating rating) {
		return new RouteFeedback(
			feedbackId,
			"route-search-1",
			"anonymous-user-1",
			rating,
			"경로 피드백 " + feedbackId,
			LocalDateTime.of(2026, 6, 17, 10, 0)
		);
	}
}
