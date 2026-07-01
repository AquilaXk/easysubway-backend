package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.adapter.out.persistence.InMemoryRouteSearchRepository;
import com.easysubway.route.domain.RouteRefreshStatus;
import com.easysubway.route.domain.RouteSearchNotFoundException;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteStep;
import com.easysubway.route.domain.RouteWarning;
import com.easysubway.route.domain.RouteWarningCode;
import com.easysubway.transit.adapter.out.persistence.InMemoryTransitMasterRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("경로 refresh 서비스")
class RouteRefreshServiceTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-13T09:00:00Z"), ZoneId.of("Asia/Seoul"));

	private final InMemoryRouteSearchRepository routeSearchRepository = new InMemoryRouteSearchRepository();
	private final RouteSearchService service = new RouteSearchService(
		routeSearchRepository,
		routeSearchRepository,
		new InMemoryTransitMasterRepository(),
		CLOCK
	);

	@Test
	@DisplayName("저장된 planned itinerary는 재검색 없이 unchanged refresh로 반환한다")
	void refreshRouteReturnsUnchangedForStoredPlannedRoute() {
		RouteSearchResult stored = routeSearchRepository.saveRouteSearch(routeSearch("route-planned", List.of(plannedStep()), List.of()));

		var refreshed = service.refreshRoute(stored.routeSearchId());

		assertThat(refreshed.routeSearch()).isEqualTo(stored);
		assertThat(refreshed.status()).isEqualTo(RouteRefreshStatus.UNCHANGED);
		assertThat(refreshed.sourceLabel()).isEqualTo("계획 시간 기준");
		assertThat(refreshed.refreshedAt()).isEqualTo(LocalDate.of(2026, 6, 13).atTime(18, 0));
	}

	@Test
	@DisplayName("stale provider 근거가 있는 저장 경로는 error 대신 stale fallback 상태를 반환한다")
	void refreshRouteReturnsStaleFallbackForStaleStoredRoute() {
		RouteSearchResult stored = routeSearchRepository.saveRouteSearch(routeSearch(
			"route-stale",
			List.of(fallbackStep()),
			List.of(new RouteWarning(RouteWarningCode.STALE_ACCESSIBILITY_DATA))
		));

		var refreshed = service.refreshRoute(stored.routeSearchId());

		assertThat(refreshed.status()).isEqualTo(RouteRefreshStatus.STALE_FALLBACK);
		assertThat(refreshed.reasonCodes()).contains("STALE_FALLBACK", "STALE_ACCESSIBILITY_DATA");
		assertThat(refreshed.etaConfidence().name()).isEqualTo("LOW");
	}

	@Test
	@DisplayName("없는 routeSearchId는 안정 not found 예외로 거부한다")
	void refreshRouteRejectsUnknownRouteSearchId() {
		assertThatThrownBy(() -> service.refreshRoute("route-missing"))
			.isInstanceOf(RouteSearchNotFoundException.class)
			.hasMessage("경로 검색 결과를 찾을 수 없습니다.");
	}

	private RouteSearchResult routeSearch(String routeSearchId, List<RouteStep> steps, List<RouteWarning> warnings) {
		return new RouteSearchResult(
			routeSearchId,
			"station-sangnoksu",
			"상록수",
			"station-sadang",
			"사당",
			MobilityType.STROLLER,
			RouteSearchStatus.FOUND,
			"line-4",
			"수도권 4호선",
			18,
			steps,
			warnings,
			List.of(),
			LocalDateTime.of(2026, 6, 30, 9, 0)
		);
	}

	private RouteStep plannedStep() {
		return step("PLANNED", "MEDIUM");
	}

	private RouteStep fallbackStep() {
		return step("FALLBACK", "LOW");
	}

	private RouteStep step(String timeSource, String confidenceLabel) {
		return new RouteStep(
			1,
			"ride",
			"수도권 4호선으로 이동",
			"열차로 이동합니다.",
			"line-4",
			"수도권 4호선",
			"station-sangnoksu",
			"station-sadang",
			7,
			1800,
			false,
			"VERIFIED",
			false,
			timeSource,
			"ESTIMATED_CONSTANT",
			confidenceLabel
		);
	}
}
