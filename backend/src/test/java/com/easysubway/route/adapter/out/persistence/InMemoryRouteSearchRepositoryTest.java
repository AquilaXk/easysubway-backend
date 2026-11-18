package com.easysubway.route.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.domain.RouteFeedback;
import com.easysubway.route.domain.RouteFeedbackRating;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("인메모리 경로 검색 저장소")
class InMemoryRouteSearchRepositoryTest {

	@Test
	@DisplayName("공개 경로 검색 결과는 최대 보관 개수를 넘으면 오래된 항목부터 제거한다")
	void saveRouteSearchEvictsOldestResultWhenLimitIsExceeded() {
		var repository = new InMemoryRouteSearchRepository();

		for (int index = 0; index <= InMemoryRouteSearchRepository.MAX_STORED_ROUTE_SEARCHES; index++) {
			repository.saveRouteSearch(routeSearchResult("route-" + index));
		}

		assertThat(repository.loadRouteSearch("route-0")).isEmpty();
		assertThat(repository.loadRouteSearch("route-" + InMemoryRouteSearchRepository.MAX_STORED_ROUTE_SEARCHES))
			.isPresent();
		assertThat(repository.loadRouteSearchStationPairsForDashboard())
			.extracting("originStationId", "destinationStationId")
			.contains(tuple("station-a", "station-b"));
	}

	@Test
	@DisplayName("최근 현장 차단 신고는 연결된 경로 정보와 함께 최신순으로 집계한다")
	void summarizeRecentBlockedFeedbacksWithRouteContext() {
		var repository = new InMemoryRouteSearchRepository();
		repository.saveRouteSearch(routeSearchResult("route-1", "상록수", "사당", MobilityType.SENIOR));
		repository.saveRouteSearch(routeSearchResult("route-2", "서울역", "시청", MobilityType.WHEELCHAIR));
		repository.saveRouteFeedback(routeFeedback(
			"feedback-1",
			"route-1",
			RouteFeedbackRating.BLOCKED_BY_REAL_WORLD,
			LocalDateTime.of(2026, 6, 17, 10, 0)
		));
		repository.saveRouteFeedback(routeFeedback(
			"feedback-2",
			"route-2",
			RouteFeedbackRating.BLOCKED_BY_REAL_WORLD,
			LocalDateTime.of(2026, 6, 17, 11, 0)
		));
		repository.saveRouteFeedback(routeFeedback(
			"feedback-3",
			"route-1",
			RouteFeedbackRating.NOT_HELPFUL,
			LocalDateTime.of(2026, 6, 17, 12, 0)
		));

		var summary = repository.summarizeRouteFeedbacks();

		assertThat(summary.recentBlockedFeedbacks())
			.extracting("originStationName", "destinationStationName", "mobilityType", "createdAt")
			.containsExactly(
				tuple("서울역", "시청", MobilityType.WHEELCHAIR, LocalDateTime.of(2026, 6, 17, 11, 0)),
				tuple("상록수", "사당", MobilityType.SENIOR, LocalDateTime.of(2026, 6, 17, 10, 0))
			);
	}

	private RouteSearchResult routeSearchResult(String routeSearchId) {
		return routeSearchResult(routeSearchId, "출발역", "도착역", MobilityType.SENIOR);
	}

	private RouteSearchResult routeSearchResult(
		String routeSearchId,
		String originStationName,
		String destinationStationName,
		MobilityType mobilityType
	) {
		return new RouteSearchResult(
			routeSearchId,
			"station-a",
			originStationName,
			"station-b",
			destinationStationName,
			mobilityType,
			RouteSearchStatus.FOUND,
			"line-a",
			"테스트 노선",
			10,
			List.of(),
			List.of(),
			List.of(),
			LocalDateTime.of(2026, 6, 13, 9, 0)
		);
	}

	private RouteFeedback routeFeedback(
		String feedbackId,
		String routeSearchId,
		RouteFeedbackRating rating,
		LocalDateTime createdAt
	) {
		return new RouteFeedback(
			feedbackId,
			routeSearchId,
			"anonymous-user",
			rating,
			"관리자 화면에 직접 노출하지 않는 코멘트",
			createdAt
		);
	}
}
