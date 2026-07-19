package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.adapter.out.persistence.InMemoryRouteSearchRepository;
import com.easysubway.route.application.port.in.RouteSearchUseCase;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableCandidateSource;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeQuery;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableTripDeparture;
import com.easysubway.route.application.port.in.SearchInternalRouteCommand;
import com.easysubway.route.application.port.in.SearchRouteCommand;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteV2Plan;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteV2Status;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteV2PlanSource;
import com.easysubway.route.application.port.in.SubmitRouteFeedbackCommand;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.RealtimeArrivalResolver;
import com.easysubway.route.domain.ArrivalCandidate;
import com.easysubway.route.domain.ArrivalFreshness;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.EtaConfidence;
import com.easysubway.route.domain.InvalidRouteFeedbackException;
import com.easysubway.route.domain.InvalidRouteSearchException;
import com.easysubway.route.domain.EtaSource;
import com.easysubway.route.domain.InternalRouteResult;
import com.easysubway.route.domain.RouteFeedback;
import com.easysubway.route.domain.RouteNotFoundException;
import com.easysubway.route.domain.RouteEtaOffsetBucket;
import com.easysubway.route.domain.RouteFeedbackRating;
import com.easysubway.route.domain.ProfileWalkTimeCalculator.MobilityPreset;
import com.easysubway.route.domain.RouteProfileWeight;
import com.easysubway.route.domain.RouteRefreshResult;
import com.easysubway.route.domain.RouteRefreshStatus;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchNotFoundException;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteStep;
import com.easysubway.route.domain.RouteWarningCode;
import com.easysubway.transit.adapter.out.persistence.InMemoryTransitMasterRepository;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.RouteEdge;
import com.easysubway.transit.domain.RouteEdgeType;
import com.easysubway.transit.domain.RouteNode;
import com.easysubway.transit.domain.RouteNodeType;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.StationNotFoundException;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("경로 검색 서비스")
class RouteSearchServiceTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-13T09:00:00Z"), ZoneId.of("Asia/Seoul"));

	private final InMemoryRouteSearchRepository routeSearchRepository = new InMemoryRouteSearchRepository();
	private final RouteSearchService service = new RouteSearchService(
		routeSearchRepository,
		routeSearchRepository,
		new InMemoryTransitMasterRepository(),
		CLOCK
	);

	@Test
	@DisplayName("유모차 이동 유형은 같은 노선 직접 경로와 접근성 경고를 반환한다")
	void searchRouteReturnsDirectLineRecommendationForStroller() {
		var result = service.searchRoute(new SearchRouteCommand(
			"station-sangnoksu",
			"station-sadang",
			MobilityType.STROLLER
		));

		assertThat(result.routeSearchId()).startsWith("route-");
		assertThat(result.status()).isEqualTo(RouteSearchStatus.FOUND);
		assertThat(result.mobilityType()).isEqualTo(MobilityType.STROLLER);
		assertThat(result.originStationName()).isEqualTo("상록수");
		assertThat(result.destinationStationName()).isEqualTo("사당");
		assertThat(result.lineName()).isEqualTo("수도권 4호선");
		assertThat(result.score()).isGreaterThan(0);
		assertThat(result.recommendationReasons())
			.containsExactly(
				"선택된 경로에서 접근성 확인이 필요한 구간을 표시합니다.",
				"출구와 시설 상태는 현장 안내를 함께 확인해 주세요.",
				"계단 포함 구간을 미리 표시했어요"
			);
		assertThat(String.join("\n", result.recommendationReasons())).doesNotContain("확인했어요");
		assertThat(result.steps())
			.extracting("title")
			.containsExactly(
				"상록수역에서 4호선 승강장으로 이동",
				"수도권 4호선으로 사당역까지 이동",
				"사당역에서 출구 접근성 정보를 확인"
			);
		assertThat(result.steps())
			.extracting("stepType")
			.containsExactly("entry", "ride", "exit");
		assertThat(result.steps().getFirst().estimatedMinutes()).isEqualTo(5);
		assertThat(result.steps().getFirst().distanceMeters()).isEqualTo(180);
		assertThat(result.steps().getFirst().includesStairs()).isFalse();
		assertThat(result.steps().getFirst().stairAccessState()).isEqualTo("UNKNOWN");
		assertThat(result.steps().getFirst().requiresAccessibilityCheck()).isTrue();
		assertThat(result.steps().get(1).estimatedMinutes()).isGreaterThan(0);
		assertThat(result.steps().get(1).requiresAccessibilityCheck()).isFalse();
		assertThat(result.warnings())
			.extracting("code")
			.contains(RouteWarningCode.LOW_DATA_CONFIDENCE);
	}

	@Test
	@DisplayName("경로 warning API 계약은 사용자 문장 없이 code만 직렬화한다")
	void routeWarningSerializesCodeOnly() throws Exception {
		var result = service.searchRoute(new SearchRouteCommand(
			"station-sangnoksu",
			"station-sadang",
			MobilityType.STROLLER
		));

		String warningJson = new ObjectMapper().writeValueAsString(result.warnings().getFirst());

		assertThat(warningJson).contains("\"code\":\"LOW_DATA_CONFIDENCE\"");
		assertThat(warningJson).doesNotContain("message");
		assertThat(warningJson).doesNotContain("이동 경로");
	}

	@Test
	@DisplayName("경로 검색 API 계약은 비용과 요약 사실값을 score와 분리해 직렬화한다")
	void routeSearchSerializesBurdenCostAndSummaryFacts() throws Exception {
		var result = service.searchRoute(new SearchRouteCommand(
			"station-sangnoksu",
			"station-sadang",
			MobilityType.STROLLER
		));

		var mapper = new ObjectMapper().findAndRegisterModules();
		Map<?, ?> payload = mapper.readValue(mapper.writeValueAsString(result), Map.class);
		int stepDurationSeconds = result.steps()
			.stream()
			.mapToInt(step -> step.estimatedMinutes() * 60)
			.sum();

		assertThat(payload.get("score")).isEqualTo(result.score());
		assertThat(payload.get("burdenCost")).isEqualTo(result.score());
		assertThat(payload.get("estimatedDurationSeconds")).isEqualTo(stepDurationSeconds);
		assertThat(payload.get("walkingDistanceMeters")).isEqualTo(result.walkingDistanceMeters());
		assertThat(payload.get("transferCount")).isEqualTo(0);
		assertThat(payload.get("evidenceSummary"))
			.asList()
			.contains("ACCESSIBILITY_CHECK_REQUIRED", "DURATION_ESTIMATED", "DISTANCE_MEASURED");
	}

	@Test
	@DisplayName("생성된 경로 검색 결과는 식별자로 다시 조회할 수 있다")
	void getRouteSearchReturnsStoredResult() {
		var created = service.searchRoute(new SearchRouteCommand(
			"station-sangnoksu",
			"station-sadang",
			MobilityType.SENIOR
		));

		var loaded = service.getRouteSearch(created.routeSearchId());

		assertThat(loaded).isEqualTo(created);
	}

	@Test
	@DisplayName("경로 refresh는 저장된 itinerary를 재사용하고 ETA 상태를 반환한다")
	void refreshRouteReusesStoredRouteSearch() {
		var created = service.searchRoute(new SearchRouteCommand(
			"station-sangnoksu",
			"station-sadang",
			MobilityType.SENIOR
		));

		var refreshed = service.refreshRoute(created.routeSearchId());

		assertThat(refreshed.routeSearch()).isEqualTo(created);
		assertThat(refreshed.status()).isEqualTo(RouteRefreshStatus.UNCHANGED);
		assertThat(refreshed.etaSource()).isEqualTo(created.etaSource());
		assertThat(refreshed.sourceLabel()).isEqualTo("상수 추정 기준");
		assertThat(refreshed.refreshedAt()).isEqualTo(LocalDate.of(2026, 6, 13).atTime(18, 0));
	}

	@Test
	@DisplayName("경로 refresh는 알 수 없는 routeSearchId를 안정 not found로 거부한다")
	void refreshRouteRejectsUnknownRouteSearchId() {
		assertThatThrownBy(() -> service.refreshRoute("route-missing"))
			.isInstanceOf(RouteSearchNotFoundException.class)
			.hasMessage("경로 검색 결과를 찾을 수 없습니다.");
	}

	@Test
	@DisplayName("경로 피드백은 생성된 경로 검색 결과에 연결해 저장한다")
	void submitRouteFeedbackStoresFeedbackForRouteSearch() {
		var routeSearch = service.searchRoute(new SearchRouteCommand(
			"station-sangnoksu",
			"station-sadang",
			MobilityType.SENIOR
		));

		var feedback = service.submitRouteFeedback(new SubmitRouteFeedbackCommand(
			routeSearch.routeSearchId(),
			"anonymous-user-1",
			RouteFeedbackRating.HELPFUL,
			"엘리베이터 안내가 실제 이동에 맞았어요"
		));

		assertThat(feedback.feedbackId()).startsWith("route-feedback-");
		assertThat(feedback.routeSearchId()).isEqualTo(routeSearch.routeSearchId());
		assertThat(feedback.userId()).isEqualTo("anonymous-user-1");
		assertThat(feedback.rating()).isEqualTo(RouteFeedbackRating.HELPFUL);
		assertThat(feedback.comment()).isEqualTo("엘리베이터 안내가 실제 이동에 맞았어요");
		assertThat(feedback.createdAt()).isEqualTo(LocalDate.of(2026, 6, 13).atTime(18, 0));
	}

	@Test
	@DisplayName("경로 ETA 피드백은 opt-in 이후 bucketed offset과 calibration context만 저장한다")
	void submitRouteFeedbackStoresPrivacySafeEtaCalibrationContext() {
		var routeSearch = service.searchRoute(new SearchRouteCommand(
			"station-sangnoksu",
			"station-sadang",
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE
		));

		var feedback = service.submitRouteFeedback(new SubmitRouteFeedbackCommand(
			routeSearch.routeSearchId(),
			"anonymous-user-1",
			RouteFeedbackRating.NOT_HELPFUL,
			"예상보다 늦었어요",
			routeSearch.routeSearchId() + "-primary",
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			EtaSource.PLANNED,
			RouteEtaOffsetBucket.LATE_1_TO_3_MINUTES,
			true
		));

		assertThat(feedback.etaFeedbackOptedIn()).isTrue();
		assertThat(feedback.itineraryId()).isEqualTo(routeSearch.routeSearchId() + "-primary");
		assertThat(feedback.mobilityType()).isEqualTo(MobilityType.SENIOR);
		assertThat(feedback.constraintMode()).isEqualTo(ConstraintMode.PREFER_STEP_FREE);
		assertThat(feedback.etaSource()).isEqualTo(EtaSource.PLANNED);
		assertThat(feedback.etaOffsetBucket()).isEqualTo(RouteEtaOffsetBucket.LATE_1_TO_3_MINUTES);
	}

	@Test
	@DisplayName("경로 피드백은 알 수 없는 경로 검색 식별자를 거부한다")
	void submitRouteFeedbackRejectsUnknownRouteSearchId() {
		assertThatThrownBy(() -> service.submitRouteFeedback(new SubmitRouteFeedbackCommand(
			"route-missing",
			"anonymous-user-1",
			RouteFeedbackRating.HELPFUL,
			"안내가 도움이 됐어요"
		)))
			.isInstanceOf(RouteSearchNotFoundException.class)
			.hasMessage("경로 검색 결과를 찾을 수 없습니다.");
	}

	@Test
	@DisplayName("경로 피드백은 작성자와 평가가 필요하다")
	void submitRouteFeedbackRequiresUserIdAndRating() {
		var routeSearch = service.searchRoute(new SearchRouteCommand(
			"station-sangnoksu",
			"station-sadang",
			MobilityType.SENIOR
		));

		assertThatThrownBy(() -> service.submitRouteFeedback(new SubmitRouteFeedbackCommand(
			routeSearch.routeSearchId(),
			" ",
			null,
			" "
		)))
			.isInstanceOf(InvalidRouteFeedbackException.class)
			.hasMessage("피드백 작성자를 확인해야 합니다.");

		assertThatThrownBy(() -> service.submitRouteFeedback(new SubmitRouteFeedbackCommand(
			routeSearch.routeSearchId(),
			"anonymous-user-1",
			null,
			"안내 확인이 필요했어요"
		)))
			.isInstanceOf(InvalidRouteFeedbackException.class)
			.hasMessage("피드백 평가를 선택해야 합니다.");
	}

	@Test
	@DisplayName("휠체어 이동 유형은 계단만 있는 역 접근 경로를 차단한다")
	void wheelchairRouteBlocksStairOnlyStationAccess() {
		var repository = new InMemoryRouteSearchRepository();
		var stairOnlyService = new RouteSearchService(
			repository,
			repository,
			new StairOnlyTransitMasterPort(),
			CLOCK
		);

		var result = stairOnlyService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.WHEELCHAIR
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.BLOCKED);
		assertThat(result.steps()).isEmpty();
		assertThat(result.recommendationReasons()).isEmpty();
		assertThat(result.blockedReasons())
			.containsExactly("계단 없는 역 접근 경로를 확인할 수 없습니다.");
	}

	@Test
	@DisplayName("일시적 부상 strict step-free 조건은 계단만 있는 역 접근 경로를 차단한다")
	void temporaryInjuryStrictStepFreeBlocksStairOnlyStationAccess() {
		var repository = new InMemoryRouteSearchRepository();
		var stairOnlyService = new RouteSearchService(
			repository,
			repository,
			new StairOnlyTransitMasterPort(),
			CLOCK
		);

		var result = stairOnlyService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.TEMPORARY_INJURY,
			ConstraintMode.STRICT_STEP_FREE
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.BLOCKED);
		assertThat(result.steps()).isEmpty();
		assertThat(result.blockedReasons())
			.containsExactly("계단 없는 역 접근 경로를 확인할 수 없습니다.");
	}

	@Test
	@DisplayName("유모차 strict step-free 조건은 계단만 있는 역 접근 경로를 차단한다")
	void strollerStrictStepFreeBlocksStairOnlyStationAccess() {
		var repository = new InMemoryRouteSearchRepository();
		var stairOnlyService = new RouteSearchService(
			repository,
			repository,
			new StairOnlyTransitMasterPort(),
			CLOCK
		);

		var result = stairOnlyService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.STROLLER,
			ConstraintMode.STRICT_STEP_FREE
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.BLOCKED);
		assertThat(result.steps()).isEmpty();
		assertThat(result.blockedReasons())
			.containsExactly("계단 없는 역 접근 경로를 확인할 수 없습니다.");
	}

	@Test
	@DisplayName("공통 노선이 없으면 한 번 환승 가능한 역을 경로로 반환한다")
	void searchRouteReturnsOneTransferRecommendation() {
		var repository = new InMemoryRouteSearchRepository();
		var transferService = new RouteSearchService(
			repository,
			repository,
			new OneTransferTransitMasterPort(),
			CLOCK
		);

		var result = transferService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.SENIOR
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.FOUND);
		assertThat(result.lineName()).isEqualTo("A 노선 / B 노선");
		assertThat(result.steps())
			.extracting("title")
			.containsExactly(
				"출발역역에서 A 노선 승강장으로 이동",
				"A 노선으로 환승역역까지 이동",
				"환승역역에서 B 노선 승강장으로 환승",
				"B 노선으로 도착역역까지 이동",
				"도착역역에서 출구 접근성 정보를 확인"
			);
		assertThat(result.steps().get(2).description())
			.isEqualTo("환승역의 엘리베이터와 계단 없는 연결 동선을 먼저 확인합니다.");
		assertThat(result.steps().get(2).estimatedMinutes()).isEqualTo(9);
		assertThat(result.steps().get(2).distanceMeters()).isEqualTo(260);
		assertThat(result.steps().get(2).requiresAccessibilityCheck()).isTrue();
	}

	@Test
	@DisplayName("maxTransfers 2는 2회 환승 경로를 찾고 환승역 순서를 보존한다")
	void searchRouteFindsTwoTransferRouteWhenAllowed() {
		var repository = new InMemoryRouteSearchRepository();
		var transferService = new RouteSearchService(
			repository,
			repository,
			new TwoTransferTransitMasterPort(),
			CLOCK
		);

		var result = transferService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			2
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.FOUND);
		assertThat(result.transferCount()).isEqualTo(2);
		assertThat(result.lineName()).isEqualTo("A 노선 / B 노선 / C 노선");
		assertThat(result.steps())
			.filteredOn(step -> "transfer".equals(step.stepType()))
			.extracting("fromStationId")
			.containsExactly("station-transfer-1", "station-transfer-2");
	}

	@Test
	@DisplayName("maxTransfers 1은 2회 환승 전용 경로를 찾지 않는다")
	void searchRouteDoesNotFindTwoTransferRouteWhenLimitIsOne() {
		var repository = new InMemoryRouteSearchRepository();
		var transferService = new RouteSearchService(
			repository,
			repository,
			new TwoTransferTransitMasterPort(),
			CLOCK
		);

		assertThatThrownBy(() -> transferService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			1
		))).isInstanceOf(RouteNotFoundException.class);
	}

	@Test
	@DisplayName("maxTransfers 0은 직접 경로만 허용한다")
	void searchRouteWithZeroMaxTransfersIsDirectOnly() {
		var repository = new InMemoryRouteSearchRepository();
		var transferService = new RouteSearchService(
			repository,
			repository,
			new OneTransferTransitMasterPort(),
			CLOCK
		);

		assertThatThrownBy(() -> transferService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			0
		))).isInstanceOf(RouteNotFoundException.class);
	}

	@Test
	@DisplayName("maxTransfers 3은 3회 환승 경로를 찾는다")
	void searchRouteFindsThreeTransferRouteWhenAllowed() {
		var repository = new InMemoryRouteSearchRepository();
		var transferService = new RouteSearchService(
			repository,
			repository,
			new ThreeTransferTransitMasterPort(),
			CLOCK
		);

		var result = transferService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			3
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.FOUND);
		assertThat(result.transferCount()).isEqualTo(3);
		assertThat(result.steps())
			.filteredOn(step -> "transfer".equals(step.stepType()))
			.extracting("fromStationId")
			.containsExactly("station-transfer-1", "station-transfer-2", "station-transfer-3");
	}

	@Test
	@DisplayName("휠체어 이동 유형은 계단 전용 1회 환승보다 무단차 2회 환승을 우선한다")
	void wheelchairRoutePrefersStepFreeTwoTransferRouteOverStairOnlyOneTransferRoute() {
		var repository = new InMemoryRouteSearchRepository();
		var transferService = new RouteSearchService(
			repository,
			repository,
			new StairOnlyOneTransferWithStepFreeTwoTransferTransitMasterPort(),
			CLOCK
		);

		var result = transferService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.WHEELCHAIR,
			ConstraintMode.STRICT_STEP_FREE,
			2
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.FOUND);
		assertThat(result.transferCount()).isEqualTo(2);
		assertThat(result.steps())
			.filteredOn(step -> "transfer".equals(step.stepType()))
			.extracting("fromStationId")
			.containsExactly("station-transfer-1", "station-transfer-2");
		assertThat(result.warnings())
			.extracting("code")
			.doesNotContain(RouteWarningCode.STAIR_ONLY_ACCESS);
	}

	@Test
	@DisplayName("휠체어 이동 유형은 짧은 계단 전용 다중 환승보다 긴 무단차 다중 환승을 우선한다")
	void wheelchairRoutePrefersStepFreeMultiTransferRouteEvenWhenDetourIsLong() {
		var repository = new InMemoryRouteSearchRepository();
		var transferService = new RouteSearchService(
			repository,
			repository,
			new MixedMultiTransferAccessibilityTransitMasterPort(),
			CLOCK
		);

		var result = transferService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.WHEELCHAIR,
			ConstraintMode.STRICT_STEP_FREE,
			2
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.FOUND);
		assertThat(result.transferCount()).isEqualTo(2);
		assertThat(result.steps())
			.filteredOn(step -> "transfer".equals(step.stepType()))
			.extracting("fromStationId")
			.containsExactly("station-step-free-transfer-1", "station-step-free-transfer-2");
		assertThat(result.warnings())
			.extracting("code")
			.doesNotContain(RouteWarningCode.STAIR_ONLY_ACCESS);
	}

	@Test
	@DisplayName("access graph 계약은 진입, 환승, 진출 시간과 no-path reason을 분리한다")
	void accessGraphContractSeparatesAccessTimesAndNoPathReasons() {
		var profileWeight = RouteProfileWeight.from(MobilityType.WHEELCHAIR, ConstraintMode.STRICT_STEP_FREE);
		var accessRouter = new AccessGraphRouter();
		var stationRouter = new StationPathwayRouter();

		var entry = accessRouter.entryAccess("station-a", "line-a", false, profileWeight);
		var transfer = stationRouter.transferPath("station-x", "line-a", "line-b", false, profileWeight);
		var egress = accessRouter.egressAccess("station-b", "line-b", false, profileWeight);
		var ready = new TransferAccessResolver().resolve(transfer, 600, 1000);

		assertThat(entry.estimatedMinutes()).isEqualTo(5);
		assertThat(transfer.estimatedMinutes()).isEqualTo(7);
		assertThat(egress.estimatedMinutes()).isEqualTo(4);
		assertThat(transfer.evidenceSources()).containsExactly(
			"station:station-x",
			"transfer:line-a:line-b",
			"access:transfer"
		);
		assertThat(ready.transferReadyAtMinutes()).isEqualTo(607);
		assertThat(ready.slackMinutes()).isEqualTo(393);
		assertThat(ready.feasible()).isTrue();
		assertThat(accessRouter.entryAccess("station-a", "line-a", true, profileWeight).noPathReason())
			.isEqualTo(AccessNoPathReason.BLOCKED);
		assertThat(accessRouter.generatedConnector("edge-generated", profileWeight).noPathReason())
			.isEqualTo(AccessNoPathReason.UNKNOWN);
		assertThat(AccessPath.unsupported(List.of("STRICT_EVIDENCE_UNSUPPORTED")).noPathReason())
			.isEqualTo(AccessNoPathReason.UNSUPPORTED);
		assertThat(AccessPath.noData().noPathReason()).isEqualTo(AccessNoPathReason.NO_DATA);
	}

	@Test
	@DisplayName("access graph 시간은 mobility type 기본 보행 프리셋을 반영한다")
	void accessGraphTimesApplyMobilityProfilePreset() {
		var profileWeight = RouteProfileWeight.from(MobilityType.LUGGAGE);
		var accessRouter = new AccessGraphRouter();
		var stationRouter = new StationPathwayRouter();

		assertThat(accessRouter.entryAccess("station-a", "line-a", false, profileWeight).estimatedMinutes())
			.isEqualTo(5);
		assertThat(stationRouter.transferPath("station-x", "line-a", "line-b", false, profileWeight).estimatedMinutes())
			.isEqualTo(8);
		assertThat(accessRouter.egressAccess("station-b", "line-b", false, profileWeight).estimatedMinutes())
			.isEqualTo(4);
	}

	@Test
	@DisplayName("환승 경로는 같은 이동 거리의 직접 경로보다 점수가 높다")
	void transferRouteScoreIncludesTransferCost() {
		int transferScore = scoreFor(MobilityType.SENIOR, new OneTransferTransitMasterPort());
		int directScore = scoreFor(MobilityType.SENIOR, new DirectComparableTransitMasterPort());

		assertThat(transferScore).isGreaterThan(directScore);
	}

	@Test
	@DisplayName("직접 경로가 있어도 더 낮은 비용의 환승 후보를 우선한다")
	void directRouteDoesNotShortCircuitLowerCostTransferCandidate() {
		var repository = new InMemoryRouteSearchRepository();
		var transferService = new RouteSearchService(
			repository,
			repository,
			new DirectAndShorterTransferTransitMasterPort(),
			CLOCK
		);

		var results = transferService.searchRouteAlternatives(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			1
		), 2);

		assertThat(results).hasSize(2);
		assertThat(results)
			.extracting(RouteSearchResult::status)
			.containsExactly(RouteSearchStatus.FOUND, RouteSearchStatus.FOUND);
		assertThat(results)
			.extracting(RouteSearchResult::transferCount)
			.containsExactly(1, 0);
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "transfer".equals(step.stepType()))
			.extracting("fromStationId")
			.containsExactly("station-transfer");
		assertThat(results.get(1).lineName()).isEqualTo("테스트 직통");
	}

	@Test
	@DisplayName("휠체어 이동 유형은 환승역이 계단 전용이면 경로를 차단한다")
	void wheelchairRouteBlocksStairOnlyTransferStation() {
		var repository = new InMemoryRouteSearchRepository();
		var transferService = new RouteSearchService(
			repository,
			repository,
			new StairOnlyTransferTransitMasterPort(),
			CLOCK
		);

		var result = transferService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.WHEELCHAIR
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.BLOCKED);
		assertThat(result.steps()).isEmpty();
		assertThat(result.warnings())
			.extracting("code")
			.contains(RouteWarningCode.STAIR_ONLY_ACCESS);
	}

	@Test
	@DisplayName("휠체어 이동 유형은 계단 전용 환승역보다 무단차 환승역을 우선한다")
	void wheelchairRoutePrefersStepFreeTransferStation() {
		var repository = new InMemoryRouteSearchRepository();
		var transferService = new RouteSearchService(
			repository,
			repository,
			new MixedTransferAccessibilityTransitMasterPort(),
			CLOCK
		);

		var result = transferService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.WHEELCHAIR
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.FOUND);
		assertThat(result.steps())
			.extracting("title")
			.contains(
				"무단차환승역역에서 B 노선 승강장으로 환승"
			);
		assertThat(result.warnings())
			.extracting("code")
			.doesNotContain(RouteWarningCode.STAIR_ONLY_ACCESS);
	}

	@Test
	@DisplayName("V2 대안 검색은 Pareto 후보 안에서 접근 가능 경로와 차단 경로를 함께 반환한다")
	void searchRouteAlternativesReturnsParetoItinerariesWithActualStatuses() {
		var repository = new InMemoryRouteSearchRepository();
		var transferService = new RouteSearchService(
			repository,
			repository,
			new MixedTransferAccessibilityTransitMasterPort(),
			CLOCK
		);

		var results = transferService.searchRouteAlternatives(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.WHEELCHAIR,
			ConstraintMode.STRICT_STEP_FREE,
			1
		), 2);

		assertThat(results).hasSize(2);
		assertThat(results)
			.extracting(RouteSearchResult::status)
			.containsExactly(RouteSearchStatus.FOUND, RouteSearchStatus.BLOCKED);
		assertThat(results)
			.extracting(RouteSearchResult::routeSearchId)
			.doesNotHaveDuplicates();
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "transfer".equals(step.stepType()))
			.extracting("fromStationId")
			.containsExactly("station-step-free-transfer");
		assertThat(results.get(1).blockedReasons())
			.containsExactly("계단 없는 역 접근 경로를 확인할 수 없습니다.");
		assertThat(transferService.refreshRoute(results.getFirst().routeSearchId()).routeSearch())
			.isEqualTo(results.getFirst());
	}

	@Test
	@DisplayName("V2 planner는 직접 경로를 FOUND 단일 itinerary로 반환한다")
	void routeV2PlannerReturnsDirectItinerary() {
		var planner = routeV2Planner(new StairOnlyTransitMasterPort());

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 0, 3));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND);
		assertThat(plan.source()).isEqualTo(RouteV2PlanSource.LEGACY_GRAPH);
		assertThat(plan.itineraries()).hasSize(1);
		assertThat(plan.itineraries().getFirst().transferCount()).isZero();
	}

	@Test
	@DisplayName("V2 planner는 alternativeCount 범위 안에서 복수 itinerary와 실제 상태를 반환한다")
	void routeV2PlannerReturnsAlternativeItinerariesWithActualStatuses() {
		var planner = routeV2Planner(new MixedTransferAccessibilityTransitMasterPort());

		var plan = planner.search(routeV2Command(ConstraintMode.STRICT_STEP_FREE, MobilityType.WHEELCHAIR, 1, 2));

		assertThat(plan.plannerAdr()).isEqualTo("tools/routes/route-algorithm-v2-adr.json");
		assertThat(plan.source()).isEqualTo(RouteV2PlanSource.LEGACY_GRAPH);
		assertThat(plan.itineraries()).hasSize(2);
		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND, RouteV2Status.BLOCKED_ACCESSIBILITY);
		assertThat(plan.itineraries())
			.extracting(RouteSearchResult::status)
			.containsExactly(RouteSearchStatus.FOUND, RouteSearchStatus.BLOCKED);
	}

	@Test
	@DisplayName("V2 planner는 1회 환승 경로를 FOUND itinerary로 반환한다")
	void routeV2PlannerReturnsOneTransferItinerary() {
		var planner = routeV2Planner(new OneTransferTransitMasterPort());

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND);
		assertThat(plan.source()).isEqualTo(RouteV2PlanSource.LEGACY_GRAPH);
		assertThat(plan.itineraries()).hasSize(1);
		assertThat(plan.itineraries().getFirst().transferCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("V2 planner는 2회 환승 경로를 FOUND itinerary로 반환한다")
	void routeV2PlannerReturnsTwoTransferItinerary() {
		var planner = routeV2Planner(new TwoTransferTransitMasterPort());

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 2, 3));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND);
		assertThat(plan.itineraries()).hasSize(1);
		assertThat(plan.itineraries().getFirst().transferCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("V2 planner는 커버되지 않는 O/D는 graph 검색으로 폴백한다")
	void routeV2PlannerFallsBackToGraphForUncoveredStations() {
		var repository = new InMemoryRouteSearchRepository();
		var routeSearchService = new RouteSearchService(repository, repository, new StairOnlyTransitMasterPort(), CLOCK);
		// 시간표는 station-x·station-y만 커버 → routeV2Command()의 station-a·station-b는 비커버(그래프는 라우팅 가능).
		var planner = new RouteV2Planner(routeSearchService, uncoveredRouteTimetablePort());

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 0, 3));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND);
		assertThat(plan.itineraries()).isNotEmpty();
	}

	@Test
	@DisplayName("V2 planner는 빈 시간표면 graph 검색으로 폴백한다")
	void routeV2PlannerFallsBackToGraphWhenTimetableEmpty() {
		var repository = new InMemoryRouteSearchRepository();
		var routeSearchService = new RouteSearchService(repository, repository, new RampAccessibleTransitMasterPort(), CLOCK);
		var planner = new RouteV2Planner(routeSearchService, LoadRouteTimetablePort.RouteTimetable::empty);

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND);
		assertThat(plan.itineraries()).isNotEmpty();
	}

	@Test
	@DisplayName("V2 planner는 막차 이후 NO_TIMETABLE_SERVICE에 다음 운행 시각을 포함한다")
	void routeV2PlannerReturnsNextServiceTimeAfterLastTrain() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), routeTimetablePort());

		var plan = planner.search(new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-a",
			"station-b",
			OffsetDateTime.parse("2026-07-01T23:55:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			false,
			1,
			3
		));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.NO_TIMETABLE_SERVICE);
		assertThat(plan.source()).isEqualTo(RouteV2PlanSource.TIMETABLE_RAPTOR);
		assertThat(plan.itineraries()).isEmpty();
		assertThat(plan.nextServiceTime()).isEqualTo(OffsetDateTime.parse("2026-07-02T09:07:00+09:00"));
	}

	@Test
	@DisplayName("V2 planner는 환승 전용 경로의 다음 운행 시각도 RAPTOR scan으로 계산한다")
	void routeV2PlannerReturnsNextServiceTimeForTransferOnlyRoute() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), transferRouteTimetablePort());

		var plan = planner.search(new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-a",
			"station-b",
			OffsetDateTime.parse("2026-07-01T23:55:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			false,
			1,
			3
		));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.NO_TIMETABLE_SERVICE);
		assertThat(plan.itineraries()).isEmpty();
		assertThat(plan.nextServiceTime()).isEqualTo(OffsetDateTime.parse("2026-07-02T09:07:00+09:00"));
	}

	@Test
	@DisplayName("V2 planner는 먼저 출발한 유효 운행이 추월되어도 다음 운행 시각으로 유지한다")
	void routeV2PlannerReturnsEarliestNextServiceTimeWhenLaterTripOvertakes() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), overtakenNextServiceRouteTimetablePort());

		var plan = planner.search(new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-a",
			"station-b",
			OffsetDateTime.parse("2026-07-01T23:55:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			false,
			1,
			3
		));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.NO_TIMETABLE_SERVICE);
		assertThat(plan.itineraries()).isEmpty();
		assertThat(plan.nextServiceTime()).isEqualTo(OffsetDateTime.parse("2026-07-02T09:07:00+09:00"));
	}

	@Test
	@DisplayName("V2 planner는 03시 전 요청에서 이미 지난 당일 00시대 운행을 다음 운행으로 반환하지 않는다")
	void routeV2PlannerKeepsNextServiceTimeAfterEarlyMorningDeparture() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), earlyMorningNextServiceRouteTimetablePort());

		var plan = planner.search(new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-a",
			"station-b",
			OffsetDateTime.parse("2026-07-02T02:50:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			false,
			1,
			3
		));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.NO_TIMETABLE_SERVICE);
		assertThat(plan.itineraries()).isEmpty();
		assertThat(plan.nextServiceTime()).isEqualTo(OffsetDateTime.parse("2026-07-02T09:07:00+09:00"));
	}

	@Test
	@DisplayName("V2 planner는 미래 service day의 00시대 운행에 요청 직후 대기 시간을 반영한다")
	void routeV2PlannerKeepsLeadTimeForFutureServiceDayNextService() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), futureMidnightNextServiceRouteTimetablePort());

		var plan = planner.search(new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-a",
			"station-b",
			OffsetDateTime.parse("2026-07-01T23:55:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			false,
			1,
			3
		));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.NO_TIMETABLE_SERVICE);
		assertThat(plan.itineraries()).isEmpty();
		assertThat(plan.nextServiceTime()).isEqualTo(OffsetDateTime.parse("2026-07-02T00:03:00+09:00"));
	}

	@Test
	@DisplayName("V2 planner는 시간표 adapter 미연결 fallback에서는 기존 경로 검색을 유지한다")
	void routeV2PlannerKeepsLegacySearchWhenTimetableAdapterMissing() {
		var repository = new InMemoryRouteSearchRepository();
		var routeSearchService = new RouteSearchService(repository, repository, new RampAccessibleTransitMasterPort(), CLOCK);
		var planner = new RouteV2Planner(routeSearchService);

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND);
		assertThat(plan.itineraries()).hasSize(1);
	}

	@Test
	@DisplayName("V2 planner는 시간표 기반 탐색 시 legacy graph search에 위임하지 않는다")
	void routeV2PlannerUsesTimetableScanWithoutLegacyGraphSearch() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), routeTimetablePort());

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND);
		assertThat(plan.itineraries()).hasSize(1);
		assertThat(plan.itineraries().getFirst().etaSource()).isEqualTo(EtaSource.PLANNED);
		assertThat(plan.itineraries().getFirst().estimatedDurationSeconds()).isEqualTo(1320);
		assertThat(plan.itineraries().getFirst().steps())
			.extracting("stepType", "fromStationId", "toStationId", "timeSource")
			.containsExactly(
				tuple("entry", "station-a", "station-a", EtaSource.PLANNED.name()),
				tuple("ride", "station-a", "station-b", EtaSource.PLANNED.name()),
				tuple("exit", "station-b", "station-b", EtaSource.PLANNED.name())
			);
		assertThat(plan.itineraries().getFirst().steps())
			.extracting("stepType", "estimatedMinutes")
			.containsExactly(
				tuple("entry", 7),
				tuple("ride", 10),
				tuple("exit", 5)
			);
	}

	@Test
	@DisplayName("V2 planner는 이동 프로필 보행 시간을 시간표 접근 step에 반영한다")
	void routeV2PlannerAppliesMobilityProfileWalkTimeToTimetableAccessSteps() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), routeTimetablePort());

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.LUGGAGE, 1, 3));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND);
		assertThat(plan.itineraries().getFirst().estimatedDurationSeconds()).isEqualTo(1260);
		assertThat(plan.itineraries().getFirst().steps())
			.extracting("stepType", "estimatedMinutes")
			.containsExactly(
				tuple("entry", 7),
				tuple("ride", 10),
				tuple("exit", 4)
			);
		assertThat(plan.itineraries().getFirst().steps())
			.extracting("stepType", "walkSeconds")
			.containsExactly(
				tuple("entry", 288),
				tuple("ride", null),
				tuple("exit", 216)
			);
	}

	@Test
	@DisplayName("V2 planner는 명시적 보행 프리셋을 mobility type 기본값보다 우선한다")
	void routeV2PlannerUsesExplicitMobilityPresetBeforeMobilityTypeMapping() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), routeTimetablePort());

		var plan = planner.search(routeV2Command(
			ConstraintMode.PREFER_STEP_FREE,
			MobilityType.SENIOR,
			MobilityPreset.STANDARD,
			1,
			3
		));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND);
		assertThat(plan.itineraries().getFirst().estimatedDurationSeconds()).isEqualTo(1200);
		assertThat(plan.itineraries().getFirst().steps())
			.extracting("stepType", "estimatedMinutes")
			.containsExactly(
				tuple("entry", 7),
				tuple("ride", 10),
				tuple("exit", 3)
			);
	}

	@Test
	@DisplayName("V2 planner는 realtime 요청에서 overlay가 없으면 시간표 PLANNED로 강등한다")
	void routeV2PlannerFallsBackToPlannedTimetableWhenRealtimeOverlayMissing() {
		var repository = new InMemoryRouteSearchRepository();
		var routeSearchService = new RouteSearchService(repository, repository, new DisconnectedTransitMasterPort(), CLOCK);
		var planner = new RouteV2Planner(routeSearchService, routeTimetablePort());

		var plan = planner.search(new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-a",
			"station-b",
			OffsetDateTime.parse("2026-07-01T09:00:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			true,
			1,
			3
		));

		assertThat(plan.statuses())
			.containsExactly(RouteV2Status.FOUND, RouteV2Status.REALTIME_UNAVAILABLE_PLANNED_USED);
		assertThat(plan.itineraries().getFirst().etaSource()).isEqualTo(EtaSource.PLANNED);
	}

	@Test
	@DisplayName("V2 planner는 동일 ETA 후보에서 접근성 위험보다 환승 수를 먼저 반영한다")
	void routeV2PlannerRanksFewerTransfersBeforeAccessibilityRiskForSameEtaCandidates() {
		var risky = routeSearchResultWithAccessState("route-risky", "UNKNOWN", true);
		var verified = transferRouteSearchResultWithAccessState("route-verified", "AVAILABLE", false);
		var planner = new RouteV2Planner(stabilizingRouteSearchUseCase(List.of(risky, verified)), routeTimetablePort());

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 1));

		assertThat(plan.itineraries())
			.extracting(RouteSearchResult::routeSearchId)
			.containsExactly("route-risky");
	}

	@Test
	@DisplayName("V2 planner는 동일 ETA·환승 수 후보에서 접근성 미확인 위험이 낮은 경로를 우선한다")
	void routeV2PlannerRanksLowerAccessibilityRiskAfterTransferCountForSameEtaCandidates() {
		var risky = transferRouteSearchResultWithAccessState("route-risky", "UNKNOWN", true);
		var verified = transferRouteSearchResultWithAccessState("route-verified", "AVAILABLE", false);
		var planner = new RouteV2Planner(stabilizingRouteSearchUseCase(List.of(risky, verified)), routeTimetablePort());

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 1));

		assertThat(plan.itineraries())
			.extracting(RouteSearchResult::routeSearchId)
			.containsExactly("route-verified");
	}

	@Test
	@DisplayName("V2 planner는 동일 도착 시각 시간표 후보에서 환승 수가 많은 경로를 제거한다")
	void routeV2PlannerDropsMoreTransfersForSameArrivalTimetableScanCandidates() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), sameArrivalRouteTimetablePort());

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 2));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND);
		assertThat(plan.itineraries())
			.extracting(RouteSearchResult::transferCount)
			.containsExactly(0);
	}

	@Test
	@DisplayName("V2 planner는 시간표 환승 경로에 접근과 환승 step을 보강한다")
	void routeV2PlannerAddsAccessAndTransferStepsToTimetableTransferRoute() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), transferRouteTimetablePort());

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND);
		assertThat(plan.itineraries().getFirst().transferCount()).isEqualTo(1);
		assertThat(plan.itineraries().getFirst().walkingDistanceMeters()).isEqualTo(560);
		assertThat(plan.itineraries().getFirst().steps())
			.extracting("stepType", "fromStationId", "toStationId", "requiresAccessibilityCheck")
			.containsExactly(
				tuple("entry", "station-a", "station-a", true),
				tuple("ride", "station-a", "station-transfer", false),
				tuple("transfer", "station-transfer", "station-transfer", true),
				tuple("ride", "station-transfer", "station-b", false),
				tuple("exit", "station-b", "station-b", true)
			);
	}

	@Test
	@DisplayName("V2 planner는 환승 이동 시간 전에 출발하는 시간표 후보를 제외한다")
	void routeV2PlannerRejectsTransferBeforeTransferTime() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), transferRouteTimetablePort(33300));

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.NO_TIMETABLE_SERVICE);
		assertThat(plan.itineraries()).isEmpty();
	}

	@Test
	@DisplayName("V2 planner는 보행 프리셋별 환승 가능 시간을 시간표 scan에 반영한다")
	void routeV2PlannerAppliesMobilityPresetToTransferFeasibility() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), transferRouteTimetablePort(33630));

		var seniorPlan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));
		var standardPlan = planner.search(routeV2Command(
			ConstraintMode.PREFER_STEP_FREE,
			MobilityType.SENIOR,
			MobilityPreset.STANDARD,
			1,
			3
		));

		assertThat(seniorPlan.statuses()).containsExactly(RouteV2Status.NO_TIMETABLE_SERVICE);
		assertThat(standardPlan.statuses()).containsExactly(RouteV2Status.FOUND);
		assertThat(standardPlan.itineraries().getFirst().transferCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("V2 planner는 maxTransfers 0에서 시간표 환승 후보를 제외한다")
	void routeV2PlannerRejectsTimetableTransferWhenMaxTransfersIsZero() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), transferRouteTimetablePort());

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 0, 3));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.NO_TIMETABLE_SERVICE);
		assertThat(plan.itineraries()).isEmpty();
	}

	@Test
	@DisplayName("V2 planner는 시간표 scan 결과를 legacy refresh와 feedback 저장소에 쓰지 않는다")
	void routeV2PlannerKeepsTimetableScanResultsOutOfLegacyStore() {
		var repository = new InMemoryRouteSearchRepository();
		var routeSearchService = new RouteSearchService(repository, repository, new RampAccessibleTransitMasterPort(), CLOCK);
		var planner = new RouteV2Planner(routeSearchService, routeTimetablePort());

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND);
		assertThat(plan.itineraries().getFirst().etaSource()).isEqualTo(EtaSource.PLANNED);
		assertThat(repository.loadRouteSearch(plan.itineraries().getFirst().routeSearchId())).isEmpty();
		assertThat(repository.summarizeRouteSearches().totalCount()).isZero();
	}

	@Test
	@DisplayName("V2 planner는 실제 사용한 timetable snapshot의 artifact identity를 plan에 결합한다")
	void routeV2PlannerBindsArtifactIdentityToUsedTimetableSnapshot() {
		var delegate = routeTimetablePort();
		var port = new LoadRouteTimetablePort() {
			@Override
			public RouteTimetable loadRouteTimetable() {
				return delegate.loadRouteTimetable();
			}

			@Override
			public String timetableCacheKey() {
				return "ITX_CHEONGCHUN:artifact-used:2999-01-01T00:00:00Z";
			}

			@Override
			public Optional<String> activeItxTimetableArtifactId() {
				return Optional.of("artifact-used");
			}
		};
		var repository = new InMemoryRouteSearchRepository();
		var planner = new RouteV2Planner(
			new RouteSearchService(repository, repository, new RampAccessibleTransitMasterPort(), CLOCK),
			port
		);

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));

		assertThat(plan.source()).isEqualTo(RouteV2PlanSource.TIMETABLE_RAPTOR);
		assertThat(plan.timetableArtifactId()).isEqualTo("artifact-used");
	}

	@Test
	@DisplayName("V2 planner는 빠른 환승과 늦은 직통을 objective별 대표로 결정한다")
	void routeV2PlannerSelectsOneRepresentativePerObjective() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), objectiveRouteTimetablePort());

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));

		assertThat(plan.itineraries()).hasSize(2);
		assertThat(plan.itineraries().get(0).transferCount()).isOne();
		assertThat(plan.itineraries().get(0).objectiveTags()).containsExactly("FASTEST");
		assertThat(plan.itineraries().get(0).officialFare().adultFareWon()).isEqualTo(2_500);
		assertThat(plan.itineraries().get(0).steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("serviceClass")
			.contains("ITX_CHEONGCHUN");
		assertThat(plan.itineraries().get(1).transferCount()).isZero();
		assertThat(plan.itineraries().get(1).objectiveTags()).containsExactly("FEWEST_TRANSFERS");
		assertThat(plan.itineraries().get(1).officialFare().adultFareWon()).isEqualTo(2_000);
	}

	@Test
	@DisplayName("V2 objective 대표는 요청한 alternativeCount를 넘지 않는다")
	void routeV2PlannerCapsObjectiveRepresentativesByAlternativeCount() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), objectiveRouteTimetablePort());

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 1));

		assertThat(plan.itineraries()).singleElement().satisfies(itinerary ->
			assertThat(itinerary.objectiveTags()).containsExactly("FASTEST"));
	}

	@Test
	@DisplayName("V2 FEWEST_TRANSFERS는 세 빠른 환승 후보 뒤의 느린 직통도 보존한다")
	void routeV2PlannerPreservesSlowDirectCandidateForFewestTransfers() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), objectiveOverflowRouteTimetablePort());

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 3, 2));

		assertThat(plan.itineraries()).hasSize(2);
		assertThat(plan.itineraries().getFirst().transferCount()).isEqualTo(3);
		assertThat(plan.itineraries().getLast().transferCount()).isZero();
		assertThat(plan.itineraries().getLast().objectiveTags()).containsExactly("FEWEST_TRANSFERS");
	}

	@Test
	@DisplayName("V2 objective 결과는 candidate row 순서가 바뀌어도 결정적이다")
	void routeV2PlannerRankingIsDeterministicAcrossCandidateOrder() {
		var original = objectiveRouteTimetablePort().loadRouteTimetable();
		var trips = new ArrayList<>(original.transitTrips());
		var stopTimes = new ArrayList<>(original.transitStopTimes());
		var fares = new ArrayList<>(original.officialFares());
		Collections.reverse(trips);
		Collections.reverse(stopTimes);
		Collections.reverse(fares);
		LoadRouteTimetablePort reversed = () -> new LoadRouteTimetablePort.RouteTimetable(
			original.serviceCalendars(), original.serviceCalendarDates(), original.transitRoutes(),
			trips, stopTimes, original.transitFrequencies(), fares, original.feedEndDate()
		);
		var command = routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3);

		var first = new RouteV2Planner(legacySearchMustNotBeCalled(), () -> original).search(command);
		var second = new RouteV2Planner(legacySearchMustNotBeCalled(), reversed).search(command);

		assertThat(second.itineraries())
			.extracting("routeSearchId", "objectiveTags", "officialFare")
			.containsExactlyElementsOf(first.itineraries().stream()
				.map(result -> tuple(result.routeSearchId(), result.objectiveTags(), result.officialFare()))
				.toList());
	}

	@Test
	@DisplayName("signed RC가 소비할 deterministic planner canary JSON을 생성할 수 있다")
	void routeV2PlannerProducesSyntheticCanaryResult() throws Exception {
		var delegate = objectiveRouteTimetablePort();
		byte[] canonicalPack = canonicalPackBytes();
		byte[] canonicalSqlite;
		try (var input = new GZIPInputStream(new ByteArrayInputStream(canonicalPack))) {
			canonicalSqlite = input.readAllBytes();
		}
		var identity = new LoadRouteTimetablePort.PlannerIdentity(
			"a".repeat(64), sha256(canonicalPack), sha256(canonicalSqlite), "sha256:" + "d".repeat(64),
			"d".repeat(64), "e".repeat(64), "f".repeat(64)
		);
		var port = new LoadRouteTimetablePort() {
			@Override
			public RouteTimetable loadRouteTimetable() {
				return delegate.loadRouteTimetable();
			}

			@Override
			public RouteTimetableSnapshot loadRouteTimetableSnapshot() {
				return new RouteTimetableSnapshot("rc-snapshot", "rc-timetable-artifact", identity, loadRouteTimetable());
			}

			@Override
			public Optional<String> activeItxTimetableArtifactId() {
				return Optional.of("rc-timetable-artifact");
			}
		};
		var plan = new RouteV2Planner(legacySearchMustNotBeCalled(), port)
			.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));

		assertThat(plan.source()).isEqualTo(RouteV2PlanSource.TIMETABLE_RAPTOR);
		assertThat(plan.plannerIdentity()).isEqualTo(identity);
		assertThat(plan.itineraries()).hasSize(2).allSatisfy(itinerary -> {
			assertThat(itinerary.officialFare()).isNotNull();
			assertThat(itinerary.objectiveTags()).isNotEmpty();
		});

		String output = System.getenv("EASYSUBWAY_PLANNER_SUCCESS_OUTPUT");
		if (output != null && !output.isBlank()) {
			Path outputPath = Path.of(output);
			Files.createDirectories(outputPath.getParent());
			new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValue(
				outputPath.toFile(),
				Map.of(
					"schemaVersion", 1,
					"artifactKind", "route-v2-planner-canary-result",
					"sourceIssue", 2098,
					"transportScope", "SUBWAY_AND_ITX_CHEONGCHUN",
					"objective", "FASTEST",
					"plan", plan
				)
			);
		}
	}

	private static byte[] canonicalPackBytes() throws Exception {
		String packPath = System.getenv("EASYSUBWAY_CANONICAL_PACK_PATH");
		if (packPath != null && !packPath.isBlank()) {
			return Files.readAllBytes(Path.of(packPath));
		}
		var output = new ByteArrayOutputStream();
		try (var gzip = new GZIPOutputStream(output)) {
			gzip.write("route-v2-planner-canary-fixture".getBytes(StandardCharsets.UTF_8));
		}
		return output.toByteArray();
	}

	private static String sha256(byte[] value) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
	}

	@Test
	@DisplayName("V2 planner는 두 objective가 같은 경로면 한 itinerary에 dual tag를 부여한다")
	void routeV2PlannerDeduplicatesSameObjectiveRepresentative() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), routeTimetablePort());

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));

		assertThat(plan.itineraries()).singleElement().satisfies(itinerary ->
			assertThat(itinerary.objectiveTags()).containsExactly("FASTEST", "FEWEST_TRANSFERS"));
	}

	@Test
	@DisplayName("V2 planner는 A→B→A 전환에서도 같은 트랜잭션에서 읽은 snapshot identity를 사용한다")
	void routeV2PlannerLoadsSnapshotIdentityAndRowsAtomicallyAcrossAbaSwitch() {
		var port = new AbaSwitchingRouteTimetablePort();
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), port);

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));

		assertThat(plan.source()).isEqualTo(RouteV2PlanSource.TIMETABLE_RAPTOR);
		assertThat(plan.timetableArtifactId()).isEqualTo("snapshot-b");
		assertThat(port.atomicLoadCount()).isOne();
		assertThat(port.legacyLoadCount()).isZero();
	}

	@Test
	@DisplayName("V2 planner는 concurrent artifact 교체 중 구·신 snapshot을 섞지 않는다")
	void routeV2PlannerPublishesOnlyCompleteOldOrNewCompiledSnapshotDuringReplacement() throws Exception {
		var port = new ConcurrentSwitchingRouteTimetablePort();
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), port);

		List<RouteV2Plan> plans;
		try (var executor = Executors.newFixedThreadPool(2)) {
			var oldPlan = executor.submit(() -> planner.search(routeV2Command(
				ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3)));
			assertThat(port.awaitOldLoadStarted()).isTrue();
			port.use("b");
			var newPlan = executor.submit(() -> planner.search(routeV2Command(
				ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3)));
			port.releaseOldLoad();
			plans = List.of(oldPlan.get(10, TimeUnit.SECONDS), newPlan.get(10, TimeUnit.SECONDS));
		}

		var observed = plans.stream().map(plan -> List.of(
			plan.timetableArtifactId(),
			plan.itineraries().getFirst().steps().stream()
				.filter(step -> "ride".equals(step.stepType()))
				.findFirst()
				.orElseThrow()
				.tripId()
		)).toList();
		assertThat(observed).containsExactlyInAnyOrder(
			List.of("artifact-a", "trip-a"),
			List.of("artifact-b", "trip-b")
		);
	}

	@Test
	@DisplayName("V2 planner는 시간표 개정 유효기간이 지난 데이터를 STALE_TIMETABLE로 강등하고 PLANNED를 만들지 않는다")
	void routeV2PlannerDemotesExpiredTimetableRevisionToStaleTimetable() {
		var planner = new RouteV2Planner(
			legacySearchMustNotBeCalled(),
			feedValidityRouteTimetablePort(LocalDate.parse("2026-06-30"))
		);

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.STALE_TIMETABLE);
		assertThat(plan.itineraries()).isEmpty();
	}

	@Test
	@DisplayName("V2 planner는 요청일이 시간표 개정 유효기간 이내면 PLANNED로 계획한다")
	void routeV2PlannerPlansTimetableWhenRequestWithinRevisionValidity() {
		var repository = new InMemoryRouteSearchRepository();
		var routeSearchService = new RouteSearchService(repository, repository, new RampAccessibleTransitMasterPort(), CLOCK);
		var planner = new RouteV2Planner(routeSearchService, feedValidityRouteTimetablePort(LocalDate.parse("2026-07-01")));

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND);
		assertThat(plan.itineraries().getFirst().etaSource()).isEqualTo(EtaSource.PLANNED);
	}

	@Test
	@DisplayName("V2 planner는 legacy 저장 없이 시간표 scan 결과에 검색별 ID와 생성 시각을 부여한다")
	void routeV2PlannerBuildsTimetableScanResultsWithoutLegacyPersistence() {
		var repository = new InMemoryRouteSearchRepository();
		var routeSearchService = new RouteSearchService(repository, repository, new RampAccessibleTransitMasterPort(), CLOCK);
		var planner = new RouteV2Planner(routeSearchService, routeTimetablePort());

		var firstPlan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));
		var secondPlan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));

		var first = firstPlan.itineraries().getFirst();
		var second = secondPlan.itineraries().getFirst();
		assertThat(first.routeSearchId()).isEqualTo(second.routeSearchId());
		assertThat(first.originStationName()).isEqualTo("출발역");
		assertThat(first.destinationStationName()).isEqualTo("도착역");
		assertThat(first.createdAt()).isEqualTo(LocalDate.of(2026, 6, 13).atTime(18, 0));
		assertThat(second.createdAt()).isEqualTo(LocalDate.of(2026, 6, 13).atTime(18, 0));
		assertThat(repository.summarizeRouteSearches().totalCount()).isZero();
	}

	@Test
	@DisplayName("시간표 후보 stabilization은 legacy 결과를 저장하지 않는다")
	void stabilizeTimetableRouteCandidatesDoesNotPersistLegacyResults() {
		var repository = new InMemoryRouteSearchRepository();
		var routeSearchService = new RouteSearchService(repository, repository, new DisconnectedTransitMasterPort(), CLOCK);

		var results = routeSearchService.stabilizeTimetableRouteCandidates(
			new SearchRouteCommand("station-a", "station-b", MobilityType.SENIOR, ConstraintMode.PREFER_STEP_FREE, 1),
			3,
			1,
			List.of(
				routeSearchResultWithAccessState("route-dropped", "UNKNOWN", true),
				routeSearchResultWithAccessState("route-selected", "AVAILABLE", false)
			),
			candidates -> candidates.stream()
				.filter(candidate -> "route-selected".equals(candidate.routeSearchId()))
				.toList()
		);

		assertThat(results).hasSize(1);
		assertThat(repository.summarizeRouteSearches().totalCount()).isZero();
		assertThat(repository.loadRouteSearch(results.getFirst().routeSearchId())).isEmpty();
	}

	@Test
	@DisplayName("시간표 후보 stabilization은 탈락 후보의 접근성 signal로 응답 source를 바꾸지 않는다")
	void stabilizeTimetableRouteCandidatesIgnoresDroppedAccessibilitySignals() {
		var repository = new InMemoryRouteSearchRepository();
		var routeSearchService = new RouteSearchService(
			repository,
			repository,
			new MixedTransferAccessibilityTransitMasterPort(),
			CLOCK
		);

		var results = routeSearchService.stabilizeTimetableRouteCandidates(
			new SearchRouteCommand("station-a", "station-b", MobilityType.WHEELCHAIR, ConstraintMode.STRICT_STEP_FREE, 1),
			2,
			1,
			List.of(routeSearchResultWithAccessState("route-timetable", "AVAILABLE", false)),
			candidates -> candidates.stream()
				.limit(1)
				.toList()
		);

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().etaSource()).isEqualTo(EtaSource.PLANNED);
	}

	@Test
	@DisplayName("legacyGraphCandidateAllowed=true면 접근성 신호가 있는 레거시 결과를 LEGACY_ACCESSIBILITY_CHECK로 채택한다")
	void stabilizeTimetableRouteCandidatesWithSourceAdoptsLegacyWhenAllowedAndAccessibilitySignalPresent() {
		// #2292 Minor 2: RouteV2Planner가 legacyGraphCandidateAllowed=false로 고정 호출하게
		// 되면서 stabilizeTimetableRouteCandidatesWithSource(..., true)의 원래 동작(접근성
		// 신호가 있는 레거시 결과를 채택)을 직접 검증하는 테스트가 없어졌다. 이 메서드 자체의
		// 계약은 살아있음을 고정한다.
		var repository = new InMemoryRouteSearchRepository();
		var routeSearchService = new RouteSearchService(repository, repository, new StairOnlyTransitMasterPort(), CLOCK);

		var selection = routeSearchService.stabilizeTimetableRouteCandidatesWithSource(
			new SearchRouteCommand("station-a", "station-b", MobilityType.SENIOR, ConstraintMode.PREFER_STEP_FREE, 1),
			3,
			1,
			List.of(routeSearchResultWithAccessState("route-timetable", "AVAILABLE", false)),
			List::copyOf,
			true
		);

		assertThat(selection.source()).isEqualTo(TimetableCandidateSource.LEGACY_ACCESSIBILITY_CHECK);
		assertThat(selection.itineraries()).isNotEmpty();
		assertThat(selection.itineraries().getFirst().warnings())
			.extracting("code")
			.contains(RouteWarningCode.STAIR_ONLY_ACCESS);
	}

	@Test
	@DisplayName("V2 planner는 legacy graph가 놓친 시간표 경로를 NO_TIMETABLE_SERVICE로 버리지 않는다")
	void routeV2PlannerKeepsTimetableRouteWhenLegacyGraphMisses() {
		var repository = new InMemoryRouteSearchRepository();
		var routeSearchService = new RouteSearchService(repository, repository, new DisconnectedTransitMasterPort(), CLOCK);
		var planner = new RouteV2Planner(routeSearchService, routeTimetablePort());

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND);
		assertThat(plan.itineraries().getFirst().etaSource()).isEqualTo(EtaSource.PLANNED);
		assertThat(repository.loadRouteSearch(plan.itineraries().getFirst().routeSearchId())).isEmpty();
		assertThat(repository.summarizeRouteSearches().totalCount()).isZero();
	}

	@Test
	@DisplayName("V2 planner는 시간표 scan 전에 출발역과 도착역을 검증한다")
	void routeV2PlannerValidatesStationsBeforeTimetableScan() {
		var repository = new InMemoryRouteSearchRepository();
		var routeSearchService = new RouteSearchService(repository, repository, new RampAccessibleTransitMasterPort(), CLOCK);
		var planner = new RouteV2Planner(routeSearchService, routeTimetablePort());

		assertThatThrownBy(() -> planner.search(new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-a",
			"station-a",
			OffsetDateTime.parse("2026-07-01T09:00:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			false,
			1,
			3
		))).isInstanceOf(InvalidRouteSearchException.class)
			.hasMessage("출발역과 도착역이 달라야 합니다.");

		assertThatThrownBy(() -> planner.search(new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-unknown",
			"station-b",
			OffsetDateTime.parse("2026-07-01T09:00:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			false,
			1,
			3
		))).isInstanceOf(StationNotFoundException.class);
	}

	@Test
	@DisplayName("V2 planner는 stair-only 위험이 있어도 레거시 그래프로 새치기하지 않고 시간표 scan을 그대로 쓰되 접근성 경고는 재부착한다")
	void routeV2PlannerAlwaysPrefersTimetableScanEvenWithLegacyAccessibilitySignal() {
		// #2095/#2286/#2292: 인증 Route V2(prod 게이트가 TIMETABLE_RAPTOR 출처만 허용)는
		// 접근성 warning이 있어도 레거시 그래프를 먼저 시도하지 않는다 — 레거시가 채택되면
		// timetableArtifactId가 null이 돼 그 게이트에서 막히기 때문이다(ITX pilot 역처럼
		// STATION_LINES는 있지만 접근성 시설 데이터가 없는 역에서 실제로 발생했다). 다만
		// canonical access transition이 비어 있어 RAPTOR가 LOW_DATA_CONFIDENCE를 남기고,
		// 후보 안정화는 레거시 station evidence의 STAIR_ONLY_ACCESS도 보존한다.
		var delegate = routeTimetablePort();
		var port = new LoadRouteTimetablePort() {
			@Override
			public RouteTimetable loadRouteTimetable() {
				return delegate.loadRouteTimetable();
			}

			@Override
			public String timetableCacheKey() {
				return "ITX_CHEONGCHUN:artifact-legacy-conflict:2999-01-01T00:00:00Z";
			}

			@Override
			public Optional<String> activeItxTimetableArtifactId() {
				return Optional.of("artifact-legacy-conflict");
			}
		};
		var repository = new InMemoryRouteSearchRepository();
		var routeSearchService = new RouteSearchService(repository, repository, new StairOnlyTransitMasterPort(), CLOCK);
		var planner = new RouteV2Planner(routeSearchService, port);

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND);
		assertThat(plan.source()).isEqualTo(RouteV2PlanSource.TIMETABLE_RAPTOR);
		assertThat(plan.timetableArtifactId()).isEqualTo("artifact-legacy-conflict");
		assertThat(plan.itineraries().getFirst().etaSource()).isEqualTo(EtaSource.PLANNED);
		assertThat(plan.itineraries().getFirst().warnings())
			.extracting("code")
			.containsExactly(RouteWarningCode.LOW_DATA_CONFIDENCE, RouteWarningCode.STAIR_ONLY_ACCESS);
	}

	@Test
	@DisplayName("V2 planner는 출구 데이터가 없는 역을 지나는 RAPTOR itinerary에 LOW_DATA_CONFIDENCE만 부착하고 STAIR_ONLY_ACCESS는 지어내지 않는다")
	void routeV2PlannerAttachesLowDataConfidenceNotStairOnlyForNoExitDataStationsOnTimetableScan() {
		// #2292 2라운드 리뷰: 위 flip 테스트(StairOnlyTransitMasterPort)는 "출구는 있지만
		// 계단뿐"인 실데이터만 검증한다. 이 PR을 유발한 실제 시나리오는 ITX pilot 역처럼
		// 출구 데이터 자체가 없는 역이고, 이 경우 STAIR_ONLY_ACCESS를 지어내면 안 된다(근거
		// 없는 과잉 경고) — LOW_DATA_CONFIDENCE만 붙어야 레거시(hasStairOnlyAccess/
		// hasLowAccessibilityData)와 동일한 기준이다.
		var delegate = routeTimetablePort();
		var port = new LoadRouteTimetablePort() {
			@Override
			public RouteTimetable loadRouteTimetable() {
				return delegate.loadRouteTimetable();
			}

			@Override
			public String timetableCacheKey() {
				return "ITX_CHEONGCHUN:artifact-no-exit-data:2999-01-01T00:00:00Z";
			}

			@Override
			public Optional<String> activeItxTimetableArtifactId() {
				return Optional.of("artifact-no-exit-data");
			}
		};
		var repository = new InMemoryRouteSearchRepository();
		var routeSearchService = new RouteSearchService(repository, repository, new NoExitDataTransitMasterPort(), CLOCK);
		var planner = new RouteV2Planner(routeSearchService, port);

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND);
		assertThat(plan.source()).isEqualTo(RouteV2PlanSource.TIMETABLE_RAPTOR);
		assertThat(plan.timetableArtifactId()).isEqualTo("artifact-no-exit-data");
		assertThat(plan.itineraries().getFirst().warnings())
			.extracting("code")
			.containsExactly(RouteWarningCode.LOW_DATA_CONFIDENCE);
	}

	@Test
	@DisplayName("V2 planner는 strict wheelchair 요청을 RAPTOR에서 BLOCKED_ACCESSIBILITY로 진단한다")
	void routeV2PlannerDiagnosesStrictWheelchairAccessibilityBlockInRaptor() {
		var repository = new InMemoryRouteSearchRepository();
		var routeSearchService = new RouteSearchService(
			repository, repository, new StaleAccessibilityFacilityTransitMasterPort(), CLOCK);
		var planner = new RouteV2Planner(routeSearchService, routeTimetablePort());

		var plan = planner.search(new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-a",
			"station-b",
			OffsetDateTime.parse("2026-07-01T08:59:00+09:00"),
			MobilityType.WHEELCHAIR,
			ConstraintMode.STRICT_STEP_FREE,
			false,
			1,
			3
		));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.BLOCKED_ACCESSIBILITY);
		assertThat(plan.source()).isEqualTo(RouteV2PlanSource.TIMETABLE_RAPTOR);
		assertThat(plan.itineraries()).hasSize(1);
		assertThat(plan.itineraries().getFirst()).satisfies(itinerary -> {
			assertThat(itinerary.status()).isEqualTo(RouteSearchStatus.BLOCKED);
			assertThat(itinerary.steps()).isEmpty();
			assertThat(itinerary.warnings()).extracting("code")
				.containsExactly(RouteWarningCode.LOW_DATA_CONFIDENCE, RouteWarningCode.STALE_ACCESSIBILITY_DATA);
			assertThat(itinerary.blockedReasons())
				.containsExactly("검증된 계단 없는 접근 경로를 확인할 수 없습니다.");
		});
	}

	@Test
	@DisplayName("V2 planner는 frequency 기반 배차를 시간표 scan 후보로 확장한다")
	void routeV2PlannerExpandsFrequencyBasedDepartures() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), frequencyRouteTimetablePort());

		var plan = planner.search(new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-a",
			"station-b",
			OffsetDateTime.parse("2026-07-01T09:05:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			false,
			1,
			3
		));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND);
		assertThat(plan.itineraries().getFirst().estimatedDurationSeconds()).isEqualTo(2100);
	}

	@Test
	@DisplayName("V2 planner는 심야 24시대 시간표를 이전 service day로 탐색한다")
	void routeV2PlannerUsesPreviousServiceDayForAfterMidnightTrips() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), lateNightRouteTimetablePort());

		var plan = planner.search(new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-a",
			"station-b",
			OffsetDateTime.parse("2026-07-02T00:00:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			false,
			1,
			3
		));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND);
		assertThat(plan.itineraries().getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("fromStationId", "toStationId", "estimatedMinutes")
			.containsExactly(tuple("station-a", "station-b", 15));
	}

	@Test
	@DisplayName("V2 planner는 막차 이후 요청에서 다음 24시대 운행을 선택한다")
	void routeV2PlannerSelectsNextAfterLateNightMissedTrain() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), lateNightMissedLastTrainRouteTimetablePort());

		var plan = planner.search(new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-a",
			"station-b",
			OffsetDateTime.parse("2026-07-01T23:55:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			false,
			1,
			3
		));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND);
		assertThat(plan.itineraries().getFirst().createdAt()).isEqualTo(LocalDate.of(2026, 7, 1).atTime(23, 55));
		assertThat(plan.itineraries().getFirst().estimatedDurationSeconds()).isEqualTo(2100);
		assertThat(plan.itineraries().getFirst().steps())
			.extracting("stepType", "estimatedMinutes")
			.containsExactly(
				tuple("entry", 15),
				tuple("ride", 15),
				tuple("exit", 5)
			);
	}

	@Test
	@DisplayName("V2 planner는 단축 운행 열차를 종착 이후 목적지로 연결하지 않는다")
	void routeV2PlannerDoesNotRouteShortTurnTripPastTerminal() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), shortTurnRouteTimetablePort());

		var plan = planner.search(new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-a",
			"station-c",
			OffsetDateTime.parse("2026-07-01T09:00:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			false,
			1,
			3
		));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND);
		assertThat(plan.itineraries().getFirst().estimatedDurationSeconds()).isEqualTo(2040);
		assertThat(plan.itineraries().getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("lineName", "fromStationId", "toStationId", "estimatedMinutes")
			.containsExactly(tuple("테스트 단축운행", "station-a", "station-c", 17));
	}

	@Test
	@DisplayName("V2 planner는 진입 준비 시간보다 이른 직통 후보를 제외한다")
	void routeV2PlannerSkipsDirectTripBeforeEntrySlack() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), entrySlackRouteTimetablePort());

		var plan = planner.search(new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-a",
			"station-b",
			OffsetDateTime.parse("2026-07-01T08:59:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			false,
			1,
			3
		));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND);
		assertThat(plan.itineraries().getFirst().estimatedDurationSeconds()).isEqualTo(1920);
		assertThat(plan.itineraries().getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("lineName", "fromStationId", "toStationId", "estimatedMinutes", "serviceClass", "servicePattern")
			.containsExactly(tuple("테스트 완행", "station-a", "station-b", 20, "SUBWAY", "LOCAL"));
	}

	@Test
	@DisplayName("V2 planner는 대기 포함 실제 도착이 빠르면 EXPRESS를 선택한다")
	void routeV2PlannerSelectsExpressOnlyWhenActuallyFaster() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), entrySlackRouteTimetablePort());

		var plan = planner.search(new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-a", "station-b", OffsetDateTime.parse("2026-07-01T08:55:00+09:00"),
			MobilityType.SENIOR, ConstraintMode.PREFER_STEP_FREE, false, 1, 3
		));

		assertThat(plan.itineraries().getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("tripId", "serviceClass", "servicePattern")
			.containsExactly(tuple("express-0904", "SUBWAY", "EXPRESS"));
	}

	@Test
	@DisplayName("V2 planner는 EXPRESS 통과역을 승하차 후보로 만들지 않는다")
	void routeV2PlannerRejectsBoardingAtExpressPassThroughStation() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), expressSkipRouteTimetablePort());

		var plan = planner.search(new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-a", "station-b", OffsetDateTime.parse("2026-07-01T08:55:00+09:00"),
			MobilityType.SENIOR, ConstraintMode.PREFER_STEP_FREE, false, 1, 3
		));

		assertThat(plan.itineraries().getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("tripId", "servicePattern", "toStationId")
			.containsExactly(tuple("local-0906", "LOCAL", "station-b"));
	}

	@Test
	@DisplayName("V2 planner는 calendar exception 제거일의 시간표를 제외한다")
	void routeV2PlannerRemovesCalendarDateExceptionService() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), removedCalendarDateRouteTimetablePort());

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.NO_TIMETABLE_SERVICE);
		assertThat(plan.itineraries()).isEmpty();
	}

	@Test
	@DisplayName("V2 planner는 calendar exception 추가일의 임시 시간표를 탐색한다")
	void routeV2PlannerAddsCalendarDateExceptionService() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), addedCalendarDateRouteTimetablePort(
			LocalDate.parse("2026-07-01")
		));

		var plan = planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND);
		assertThat(plan.itineraries()).hasSize(1);
		assertThat(plan.itineraries().getFirst().etaSource()).isEqualTo(EtaSource.PLANNED);
	}

	@Test
	@DisplayName("V2 planner는 다음 운행 시각 계산에도 calendar exception 추가일을 반영한다")
	void routeV2PlannerUsesAddedCalendarDateForNextServiceTime() {
		var planner = new RouteV2Planner(legacySearchMustNotBeCalled(), addedCalendarDateRouteTimetablePort(
			LocalDate.parse("2026-07-02")
		));

		var plan = planner.search(new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-a",
			"station-b",
			OffsetDateTime.parse("2026-07-01T23:55:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			false,
			1,
			3
		));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.NO_TIMETABLE_SERVICE);
		assertThat(plan.itineraries()).isEmpty();
		assertThat(plan.nextServiceTime()).isEqualTo(OffsetDateTime.parse("2026-07-02T09:07:00+09:00"));
	}

	@Test
	@DisplayName("V2 planner는 pickup/drop-off 제한 stop_times를 승하차 후보에서 제외한다")
	void routeV2PlannerHonorsPickupAndDropOffRestrictions() {
		var noPickupPlanner = new RouteV2Planner(legacySearchMustNotBeCalled(), restrictedStopRouteTimetablePort(1, 0));
		var noDropOffPlanner = new RouteV2Planner(legacySearchMustNotBeCalled(), restrictedStopRouteTimetablePort(0, 1));

		assertThat(noPickupPlanner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3)).statuses())
			.containsExactly(RouteV2Status.NO_TIMETABLE_SERVICE);
		assertThat(noDropOffPlanner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3)).statuses())
			.containsExactly(RouteV2Status.NO_TIMETABLE_SERVICE);
	}

	@Test
	@DisplayName("V2 비커버 fallback 경로는 게이트 판정에 전체 snapshot을 읽지 않는다")
	void routeV2PlannerDoesNotLoadFullTimetableForRealtimeAvailabilityGuard() {
		var repository = new InMemoryRouteSearchRepository();
		var routeSearchService = new RouteSearchService(repository, repository, new RampAccessibleTransitMasterPort(), CLOCK);
		var planner = new RouteV2Planner(routeSearchService, new LoadRouteTimetablePort() {
			@Override
			public boolean hasRouteTimetable() {
				return false;
			}

			@Override
			public LoadRouteTimetablePort.RouteTimetable loadRouteTimetable() {
				throw new AssertionError("RouteV2Planner must not materialize the full timetable before RAPTOR uses it");
			}
		});

		var plan = planner.search(new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-a",
			"station-b",
			OffsetDateTime.parse("2026-07-01T09:00:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			true,
			1,
			3
		));

		// hasRouteTimetable()==false면 게이트가 loadRouteTimetable() 없이 graph 폴백한다(AssertionError 미발생=불변식).
		assertThat(plan.statuses()).containsExactly(
			RouteV2Status.FOUND, RouteV2Status.REALTIME_UNAVAILABLE_PLANNED_USED);
		assertThat(plan.itineraries()).isNotEmpty();
	}

	@Test
	@DisplayName("V2 planner는 RAPTOR 시간표 snapshot을 planner 인스턴스에서 재사용한다")
	void routeV2PlannerReusesLoadedTimetableSnapshot() {
		var repository = new InMemoryRouteSearchRepository();
		var routeSearchService = new RouteSearchService(repository, repository, new RampAccessibleTransitMasterPort(), CLOCK);
		var timetablePort = new CountingRouteTimetablePort();
		var registry = new SimpleMeterRegistry();
		var planner = new RouteV2Planner(routeSearchService, timetablePort, registry);

		planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));
		planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));

		assertThat(timetablePort.loadCount()).isEqualTo(1);
		assertThat(registry.get("easysubway.route.v2.timetable.cache")
			.tag("result", "miss").counter().count()).isEqualTo(1);
		assertThat(registry.get("easysubway.route.v2.timetable.cache")
			.tag("result", "hit").counter().count()).isEqualTo(1);
	}

	@Test
	@DisplayName("V2 planner는 동일 freshness라도 snapshot SHA가 바뀌면 시간표를 다시 읽는다")
	void routeV2PlannerReloadsTimetableWhenCacheKeyChanges() {
		var repository = new InMemoryRouteSearchRepository();
		var routeSearchService = new RouteSearchService(repository, repository, new RampAccessibleTransitMasterPort(), CLOCK);
		var timetablePort = new CountingRouteTimetablePort();
		var planner = new RouteV2Planner(routeSearchService, timetablePort);

		planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));
		timetablePort.replaceSnapshotAtSameFreshness();
		planner.search(routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 3));

		assertThat(timetablePort.loadCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("V2 planner는 접근성 차단 경로를 BLOCKED_ACCESSIBILITY status로 반환한다")
	void routeV2PlannerReturnsBlockedAccessibilityStatus() {
		var planner = routeV2Planner(new StairOnlyTransitMasterPort());

		var plan = planner.search(routeV2Command(ConstraintMode.STRICT_STEP_FREE, MobilityType.WHEELCHAIR, 0, 3));

		assertThat(plan.statuses()).containsExactly(RouteV2Status.BLOCKED_ACCESSIBILITY);
		assertThat(plan.itineraries()).hasSize(1);
		assertThat(plan.itineraries().getFirst().status()).isEqualTo(RouteSearchStatus.BLOCKED);
	}

	@Test
	@DisplayName("V2 useRealtime=true는 provider ETA를 첫 승차 단계에 반영한다")
	void routeV2PlannerAppliesRealtimeEtaWhenRequested() {
		var repository = new InMemoryRouteSearchRepository();
		var resolver = new CountingRealtimeArrivalResolver();
		var routeSearchService = new RouteSearchService(
			repository,
			repository,
			new TimetableAlignedRampAccessibleTransitMasterPort(),
			CLOCK,
			resolver
		);
		var planner = new RouteV2Planner(routeSearchService, routeTimetablePort());

		var plan = planner.search(new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-a",
			"station-b",
			OffsetDateTime.parse("2026-07-01T09:00:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			true,
			1,
			1
		));

		assertThat(resolver.callCount()).isEqualTo(1);
		assertThat(resolver.lastQuery().stationId()).isEqualTo("station-a");
		assertThat(resolver.lastQuery().readyAt()).isEqualTo(Instant.parse("2026-07-01T00:08:30Z"));
		assertThat(plan.itineraries().getFirst().etaSource()).isEqualTo(EtaSource.MIXED);
		assertThat(plan.itineraries().getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("timeSource")
			.containsExactly(EtaSource.REALTIME.name());
		assertThat(plan.itineraries().getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.first()
			.satisfies(step -> {
				assertThat(step.reasonCodes()).containsExactly(
					"MATCHED_REALTIME", "REALTIME_POST_SCAN_FALLBACK");
				assertThat(step.providerSnapshotId()).isEqualTo("test-realtime-snapshot");
				assertThat(step.providerObservedAt()).isEqualTo("2026-07-01T00:08:00Z");
				assertThat(step.gatewayReceivedAt()).isEqualTo("2026-07-01T00:08:00Z");
				assertThat(step.servedAt()).isEqualTo("2026-06-13T09:00:00Z");
			});
		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND);
		assertThat(plan.source()).isEqualTo(RouteV2PlanSource.TIMETABLE_RAPTOR);
	}

	@Test
	@DisplayName("V2 planner는 fresh delay를 RAPTOR 전에 반영해 선택 trip 자체를 바꾼다")
	void routeV2PlannerAppliesFreshDelayBeforeRaptorSelection() {
		var repository = new InMemoryRouteSearchRepository();
		var calls = new AtomicInteger();
		RealtimeArrivalResolver resolver = query -> {
			calls.incrementAndGet();
			Instant observedAt = query.readyAt().minusSeconds(30);
			return new RealtimeArrivalResolver.Resolution(
				ArrivalFreshness.FRESH_REALTIME,
				null,
				"snapshot-pre-scan",
				observedAt,
				List.of(new ArrivalCandidate(
					"train-express", query.lineId(), query.direction(), "도착역", 1_440,
					query.readyAt().plusSeconds(1_440), observedAt, "EXPRESS",
					ArrivalFreshness.FRESH_REALTIME, EtaConfidence.HIGH))
			);
		};
		var routeSearchService = new RouteSearchService(
			repository, repository, new TimetableAlignedRampAccessibleTransitMasterPort(), CLOCK, resolver);
		var planner = new RouteV2Planner(routeSearchService, preScanRealtimeRouteTimetablePort());

		var plan = planner.search(new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-a", "station-b", OffsetDateTime.parse("2026-07-01T09:00:00+09:00"),
			MobilityType.SENIOR, ConstraintMode.PREFER_STEP_FREE, true, 0, 1));

		assertThat(calls).hasValue(2);
		assertThat(plan.itineraries()).singleElement().satisfies(itinerary -> {
			assertThat(itinerary.steps()).filteredOn(step -> "ride".equals(step.stepType()))
				.extracting("tripId").containsExactly("trip-local");
			assertThat(itinerary.etaSource()).isEqualTo(EtaSource.FALLBACK);
			assertThat(itinerary.steps()).filteredOn(step -> "ride".equals(step.stepType()))
				.extracting("reasonCodes").containsExactly(List.of("NO_USABLE_REALTIME_CANDIDATE"));
		});
		assertThat(plan.statuses())
			.containsExactly(RouteV2Status.FOUND, RouteV2Status.REALTIME_UNAVAILABLE_PLANNED_USED);
	}

	@Test
	@DisplayName("동시 스캔은 원자 교체된 구·신 overlay 중 한 version만 관측한다")
	void routeV2PlannerObservesOneAtomicRealtimeOverlayVersionPerScan() throws Exception {
		var repository = new InMemoryRouteSearchRepository();
		var version = new AtomicInteger();
		RealtimeArrivalResolver resolver = query -> {
			boolean old = version.getAndIncrement() % 2 == 0;
			int etaSeconds = old ? 600 : 660;
			String snapshotId = old ? "snapshot-old" : "snapshot-new";
			Instant observedAt = query.readyAt().minusSeconds(30);
			return new RealtimeArrivalResolver.Resolution(
				ArrivalFreshness.FRESH_REALTIME, null, snapshotId, observedAt,
				List.of(new ArrivalCandidate(
					"train-express", query.lineId(), query.direction(), "도착역", etaSeconds,
					query.readyAt().plusSeconds(etaSeconds), observedAt, "EXPRESS",
					ArrivalFreshness.FRESH_REALTIME, EtaConfidence.HIGH)));
		};
		var routeSearchService = new RouteSearchService(
			repository, repository, new TimetableAlignedRampAccessibleTransitMasterPort(), CLOCK, resolver);
		var planner = new RouteV2Planner(routeSearchService, preScanRealtimeRouteTimetablePort());
		var command = new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-a", "station-b", OffsetDateTime.parse("2026-07-01T09:00:00+09:00"),
			MobilityType.SENIOR, ConstraintMode.PREFER_STEP_FREE, true, 0, 1);

		List<RouteV2Plan> plans;
		try (var executor = Executors.newFixedThreadPool(8)) {
			var tasks = java.util.stream.IntStream.range(0, 64)
				.mapToObj(ignored -> (java.util.concurrent.Callable<RouteV2Plan>) () -> planner.search(command))
				.toList();
			plans = executor.invokeAll(tasks).stream().map(future -> {
				try {
					return future.get();
				} catch (Exception exception) {
					throw new AssertionError(exception);
				}
			}).toList();
		}

		assertThat(plans).allSatisfy(plan -> {
			RouteStep ride = plan.itineraries().getFirst().steps().stream()
				.filter(step -> "ride".equals(step.stepType())).findFirst().orElseThrow();
			assertThat(ride.providerSnapshotId()).isIn("snapshot-old", "snapshot-new");
			if ("snapshot-old".equals(ride.providerSnapshotId())) {
				assertThat(ride.plannedArrivalTime()).isEqualTo("2026-07-01T09:20:00+09:00");
			} else {
				assertThat(ride.plannedArrivalTime()).isEqualTo("2026-07-01T09:21:00+09:00");
			}
		});
	}

	@Test
	@DisplayName("동시 요청은 같은 timetable에서도 다른 출발역의 overlay를 재사용하지 않는다")
	void routeV2PlannerKeepsRealtimeOverlayRequestLocalAcrossOrigins() throws Exception {
		var repository = new InMemoryRouteSearchRepository();
		var resolverBarrier = new CyclicBarrier(2);
		RealtimeArrivalResolver resolver = query -> {
			try {
				resolverBarrier.await(5, TimeUnit.SECONDS);
			} catch (Exception exception) {
				throw new AssertionError("realtime resolver barrier failed", exception);
			}
			String suffix = query.stationId().substring(query.stationId().lastIndexOf('-') + 1);
			Instant observedAt = query.readyAt().minusSeconds(30);
			return new RealtimeArrivalResolver.Resolution(
				ArrivalFreshness.FRESH_REALTIME, null, "snapshot-" + suffix, observedAt,
				List.of(new ArrivalCandidate(
					"train-" + suffix, query.lineId(), query.direction(), "도착역", 600,
					query.readyAt().plusSeconds(600), observedAt, "LOCAL",
					ArrivalFreshness.FRESH_REALTIME, EtaConfidence.HIGH)));
		};
		var routeSearchService = new RouteSearchService(
			repository, repository, new DualOriginRealtimeTransitMasterPort(), CLOCK, resolver);
		var planner = new RouteV2Planner(routeSearchService, dualOriginRealtimeRouteTimetablePort());
		var commandA = new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-a", "station-b", OffsetDateTime.parse("2026-07-01T09:00:00+09:00"),
			MobilityType.SENIOR, ConstraintMode.PREFER_STEP_FREE, true, 0, 1);
		var commandC = new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-c", "station-b", OffsetDateTime.parse("2026-07-01T09:00:00+09:00"),
			MobilityType.SENIOR, ConstraintMode.PREFER_STEP_FREE, true, 0, 1);

		try (var executor = Executors.newFixedThreadPool(2)) {
			var planA = executor.submit(() -> planner.search(commandA));
			var planC = executor.submit(() -> planner.search(commandC));
			assertThat(realtimeRide(planA.get()).providerSnapshotId()).isEqualTo("snapshot-a");
			assertThat(realtimeRide(planC.get()).providerSnapshotId()).isEqualTo("snapshot-c");
		}
	}

	private static RouteStep realtimeRide(RouteV2Plan plan) {
		return plan.itineraries().getFirst().steps().stream()
			.filter(step -> "ride".equals(step.stepType()))
			.findFirst()
			.orElseThrow();
	}

	@Test
	@DisplayName("pre-scan realtime은 exact trainNo만 sparse delta로 변환한다")
	void resolvesExactRealtimeTripForPreScanOverlay() {
		var repository = new InMemoryRouteSearchRepository();
		var resolver = new CountingRealtimeArrivalResolver();
		var routeSearchService = new RouteSearchService(
			repository, repository, new TimetableAlignedRampAccessibleTransitMasterPort(), CLOCK, resolver);
		Instant scheduledArrival = CLOCK.instant().plusSeconds(60);

		var updates = routeSearchService.resolveTimetableRealtime(List.of(new TimetableRealtimeQuery(
			"station-a",
			"seoul-4",
			CLOCK.instant(),
			List.of(new TimetableTripDeparture(
				"trip-live", "train-test", "LOCAL",
				scheduledArrival, scheduledArrival.plusSeconds(30)
			))
		)));

		assertThat(updates.available()).isTrue();
		assertThat(updates.version()).isEqualTo("test-realtime-snapshot");
		assertThat(updates.updates()).singleElement().satisfies(update -> {
			assertThat(update.tripId()).isEqualTo("trip-live");
			assertThat(update.arrivalDeltaSeconds()).isEqualTo(60);
			assertThat(update.departureDeltaSeconds()).isEqualTo(60);
			assertThat(update.cancelled()).isFalse();
			assertThat(update.providerObservedAt()).isEqualTo(CLOCK.instant().minusSeconds(30));
		});
	}

	@Test
	@DisplayName("여러 탑승점에서 같은 trip을 확인해도 동일한 pre-scan update는 한 번만 유지한다")
	void deduplicatesConsistentRealtimeTripAcrossQueries() {
		var repository = new InMemoryRouteSearchRepository();
		var resolver = new CountingRealtimeArrivalResolver();
		var routeSearchService = new RouteSearchService(
			repository, repository, new TimetableAlignedRampAccessibleTransitMasterPort(), CLOCK, resolver);
		var query = new TimetableRealtimeQuery(
			"station-a", "seoul-4", CLOCK.instant(), List.of(new TimetableTripDeparture(
				"trip-live", "train-test", "LOCAL",
				CLOCK.instant().plusSeconds(60), CLOCK.instant().plusSeconds(90))));

		var updates = routeSearchService.resolveTimetableRealtime(List.of(query, query));

		assertThat(updates.available()).isTrue();
		assertThat(updates.updates()).singleElement()
			.satisfies(update -> assertThat(update.tripId()).isEqualTo("trip-live"));
	}

	@Test
	@DisplayName("pre-scan realtime 묶음 중 하나라도 stale이면 전체 overlay를 적용하지 않는다")
	void rejectsPartialRealtimeOverlayWhenAnyQueryIsStale() {
		var repository = new InMemoryRouteSearchRepository();
		var resolver = new CountingRealtimeArrivalResolver(
			ArrivalFreshness.FRESH_REALTIME, ArrivalFreshness.STALE_REALTIME);
		var routeSearchService = new RouteSearchService(
			repository, repository, new TimetableAlignedRampAccessibleTransitMasterPort(), CLOCK, resolver);
		var query = new TimetableRealtimeQuery(
			"station-a", "seoul-4", CLOCK.instant(), List.of(new TimetableTripDeparture(
				"trip-live", "train-test", "LOCAL",
				CLOCK.instant().plusSeconds(60), CLOCK.instant().plusSeconds(90))));

		var updates = routeSearchService.resolveTimetableRealtime(List.of(query, query));

		assertThat(updates.available()).isFalse();
		assertThat(updates.updates()).isEmpty();
		assertThat(updates.fallbackCode()).isEqualTo("PROVIDER_UNAVAILABLE");
	}

	@Test
	@DisplayName("stale·unsupported·unavailable realtime은 pre-scan live update로 승격하지 않는다")
	void rejectsEveryNonFreshRealtimeStatusBeforeScan() {
		for (ArrivalFreshness status : List.of(
			ArrivalFreshness.STALE_REALTIME,
			ArrivalFreshness.UNSUPPORTED,
			ArrivalFreshness.UNAVAILABLE,
			ArrivalFreshness.EMPTY_PROVIDER_RESULT
		)) {
			var repository = new InMemoryRouteSearchRepository();
			var routeSearchService = new RouteSearchService(
				repository,
				repository,
				new TimetableAlignedRampAccessibleTransitMasterPort(),
				CLOCK,
				new CountingRealtimeArrivalResolver(status)
			);
			var updates = routeSearchService.resolveTimetableRealtime(List.of(new TimetableRealtimeQuery(
				"station-a", "seoul-4", CLOCK.instant(), List.of(new TimetableTripDeparture(
					"trip-live", "train-test", "LOCAL",
					CLOCK.instant().plusSeconds(60), CLOCK.instant().plusSeconds(90))))));

			assertThat(updates.available()).as(status.name()).isFalse();
			assertThat(updates.updates()).as(status.name()).isEmpty();
		}
	}

	@Test
	@DisplayName("명시적 cancel trainNo는 추정 없이 해당 timetable trip의 cancel bit로 변환한다")
	void resolvesExplicitCancellationForPreScanOverlay() {
		var repository = new InMemoryRouteSearchRepository();
		var routeSearchService = new RouteSearchService(
			repository,
			repository,
			new TimetableAlignedRampAccessibleTransitMasterPort(),
			CLOCK,
			query -> new RealtimeArrivalResolver.Resolution(
				ArrivalFreshness.FRESH_REALTIME,
				null,
				"snapshot-cancel",
				CLOCK.instant(),
				List.of(),
				List.of("  train-cancelled  ")
			)
		);
		var updates = routeSearchService.resolveTimetableRealtime(List.of(new TimetableRealtimeQuery(
			"station-a", "seoul-4", CLOCK.instant(), List.of(new TimetableTripDeparture(
				"trip-cancelled", "train-cancelled", "LOCAL",
				CLOCK.instant().plusSeconds(60), CLOCK.instant().plusSeconds(90))))));

		assertThat(updates.available()).isTrue();
		assertThat(updates.updates()).singleElement().satisfies(update -> {
			assertThat(update.tripId()).isEqualTo("trip-cancelled");
			assertThat(update.cancelled()).isTrue();
			assertThat(update.arrivalDeltaSeconds()).isZero();
			assertThat(update.departureDeltaSeconds()).isZero();
		});
	}

	@Test
	@DisplayName("V2 useRealtime=true는 환승 이후 승차 단계에도 provider ETA를 반영한다")
	void routeV2PlannerAppliesRealtimeEtaAfterTransfer() {
		var repository = new InMemoryRouteSearchRepository();
		var resolver = new CountingRealtimeArrivalResolver();
		var routeSearchService = new RouteSearchService(
			repository,
			repository,
			new OneTransferTransitMasterPort(),
			CLOCK,
			resolver
		);
		var planner = new RouteV2Planner(routeSearchService, transferRouteTimetablePort());

		var plan = planner.search(new RouteV2Planner.SearchRouteV2Command(
			"station-a",
			"station-b",
			OffsetDateTime.parse("2026-07-01T09:00:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			true,
			1,
			1
		));

		assertThat(resolver.callCount()).isEqualTo(2);
		assertThat(resolver.queries())
			.extracting(RealtimeArrivalResolver.Query::readyAt)
			.containsExactly(
				Instant.parse("2026-07-01T00:08:30Z"),
				Instant.parse("2026-07-01T00:28:00Z")
			);
		assertThat(plan.itineraries().getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("timeSource")
			.containsExactly(EtaSource.REALTIME.name(), EtaSource.REALTIME.name());
		assertThat(plan.itineraries().getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("estimatedMinutes")
			.containsExactly(8, 9);
	}

	@Test
	@DisplayName("pre-scan overlay는 후보 경로의 환승 ride까지 최종 스캔 전에 보정한다")
	void routeV2PlannerAppliesRealtimeToTransferBeforeFinalScan() {
		var repository = new InMemoryRouteSearchRepository();
		List<RealtimeArrivalResolver.Query> queries = Collections.synchronizedList(new ArrayList<>());
		RealtimeArrivalResolver resolver = query -> {
			queries.add(query);
			boolean origin = "station-a".equals(query.stationId());
			Instant observedAt = query.readyAt().minusSeconds(30);
			return new RealtimeArrivalResolver.Resolution(
				ArrivalFreshness.FRESH_REALTIME,
				null,
				origin ? "snapshot-origin" : "snapshot-transfer",
				observedAt,
				List.of(new ArrivalCandidate(
					origin ? "train-a" : "train-b", query.lineId(), query.direction(), "도착역",
					origin ? 600 : 60, query.readyAt().plusSeconds(origin ? 600 : 60), observedAt, "LOCAL",
					ArrivalFreshness.FRESH_REALTIME, EtaConfidence.HIGH)));
		};
		var routeSearchService = new RouteSearchService(
			repository, repository, new OneTransferTransitMasterPort(), CLOCK, resolver);
		var planner = new RouteV2Planner(routeSearchService, preScanTransferRouteTimetablePort());

		var plan = planner.search(new RouteV2Planner.SearchRouteV2Command(
			"station-a", "station-b", OffsetDateTime.parse("2026-07-01T09:00:00+09:00"),
			MobilityType.SENIOR, ConstraintMode.PREFER_STEP_FREE, true, 1, 1));

		assertThat(plan.itineraries().getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting(RouteStep::timeSource)
			.containsExactly(EtaSource.REALTIME.name(), EtaSource.REALTIME.name());
		assertThat(queries).extracting(RealtimeArrivalResolver.Query::stationId)
			.containsExactly("station-a", "station-transfer");
		assertThat(plan.itineraries().getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting(step -> step.reasonCodes().getLast())
			.containsExactly("REALTIME_PRE_SCAN_OVERLAY", "REALTIME_PRE_SCAN_OVERLAY");
		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND);
	}

	@Test
	@DisplayName("pre-scan overlay는 후보 경로의 취소된 환승 열차를 최종 스캔 전에 제외한다")
	void routeV2PlannerExcludesCancelledTransferBeforeFinalScan() {
		var repository = new InMemoryRouteSearchRepository();
		List<RealtimeArrivalResolver.Query> queries = Collections.synchronizedList(new ArrayList<>());
		RealtimeArrivalResolver resolver = query -> {
			queries.add(query);
			Instant observedAt = query.readyAt().minusSeconds(30);
			if ("station-transfer".equals(query.stationId())) {
				return new RealtimeArrivalResolver.Resolution(
					ArrivalFreshness.FRESH_REALTIME,
					null,
					"snapshot-transfer",
					observedAt,
					List.of(),
					List.of("train-b")
				);
			}
			return new RealtimeArrivalResolver.Resolution(
				ArrivalFreshness.FRESH_REALTIME,
				null,
				"snapshot-origin",
				observedAt,
				List.of(new ArrivalCandidate(
					"train-a", query.lineId(), query.direction(), "환승역",
					600, query.readyAt().plusSeconds(600), observedAt, "LOCAL",
					ArrivalFreshness.FRESH_REALTIME, EtaConfidence.HIGH
				))
			);
		};
		var routeSearchService = new RouteSearchService(
			repository, repository, new OneTransferTransitMasterPort(), CLOCK, resolver);
		var planner = new RouteV2Planner(routeSearchService, preScanTransferRouteTimetablePort());

		var plan = planner.search(new RouteV2Planner.SearchRouteV2Command(
			"station-a", "station-b", OffsetDateTime.parse("2026-07-01T09:00:00+09:00"),
			MobilityType.SENIOR, ConstraintMode.PREFER_STEP_FREE, true, 1, 1));

		assertThat(queries).extracting(RealtimeArrivalResolver.Query::stationId)
			.contains("station-transfer");
		assertThat(plan.itineraries()).isEmpty();
		assertThat(plan.statuses()).containsExactly(RouteV2Status.NO_TIMETABLE_SERVICE);
		assertThat(plan.nextServiceTime()).isEqualTo(OffsetDateTime.parse("2026-07-02T09:09:00+09:00"));
	}

	@Test
	@DisplayName("V2 useRealtime=true는 환승 전 fallback ride의 planned duration을 wait으로 중복 계산하지 않는다")
	void routeV2PlannerDoesNotDoubleCountFallbackWaitBeforeTransfer() {
		var repository = new InMemoryRouteSearchRepository();
		var resolver = new CountingRealtimeArrivalResolver(ArrivalFreshness.UNAVAILABLE, ArrivalFreshness.FRESH_REALTIME);
		var routeSearchService = new RouteSearchService(
			repository,
			repository,
			new OneTransferTransitMasterPort(),
			CLOCK,
			resolver
		);
		var planner = new RouteV2Planner(routeSearchService, transferRouteTimetablePort());

		var plan = planner.search(new RouteV2Planner.SearchRouteV2Command(
			"station-a",
			"station-b",
			OffsetDateTime.parse("2026-07-01T09:00:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			true,
			1,
			1
		));

		assertThat(resolver.callCount()).isEqualTo(2);
		assertThat(resolver.queries())
			.extracting(RealtimeArrivalResolver.Query::readyAt)
			.containsExactly(
				Instant.parse("2026-07-01T00:08:30Z"),
				Instant.parse("2026-07-01T00:26:00Z")
			);
		assertThat(plan.itineraries().getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("timeSource")
			.containsExactly(EtaSource.FALLBACK.name(), EtaSource.REALTIME.name());
		assertThat(plan.itineraries().getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("estimatedMinutes")
			.containsExactly(6, 9);
	}

	@Test
	@DisplayName("V2 useRealtime=false는 provider를 호출하지 않고 계획 ETA를 유지한다")
	void routeV2PlannerSkipsRealtimeProviderWhenDisabled() {
		var repository = new InMemoryRouteSearchRepository();
		var resolver = new CountingRealtimeArrivalResolver();
		var routeSearchService = new RouteSearchService(
			repository,
			repository,
			new RampAccessibleTransitMasterPort(),
			CLOCK,
			resolver
		);
		var planner = new RouteV2Planner(routeSearchService, routeTimetablePort());

		var plan = planner.search(new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-a",
			"station-b",
			OffsetDateTime.parse("2026-07-01T09:00:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			false,
			1,
			1
		));

		assertThat(resolver.callCount()).isZero();
		assertThat(plan.itineraries().getFirst().etaSource()).isEqualTo(EtaSource.PLANNED);
		assertThat(plan.statuses()).containsExactly(RouteV2Status.FOUND);
	}

	@Test
	@DisplayName("V2 realtime provider를 사용할 수 없으면 fallback ETA source와 planned 사용 상태를 노출한다")
	void routeV2PlannerReportsRealtimeUnavailableFallbackStatus() {
		var repository = new InMemoryRouteSearchRepository();
		var routeSearchService = new RouteSearchService(
			repository,
			repository,
			new TimetableAlignedRampAccessibleTransitMasterPort(),
			CLOCK,
			query -> new RealtimeArrivalResolver.Resolution(
				ArrivalFreshness.UNAVAILABLE,
				"PROVIDER_QUOTA_EXCEEDED",
				null,
				null,
				List.of()
			)
		);
		var planner = new RouteV2Planner(routeSearchService, routeTimetablePort());

		var plan = planner.search(new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-a",
			"station-b",
			OffsetDateTime.parse("2026-07-01T09:00:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.PREFER_STEP_FREE,
			true,
			1,
			1
		));

		assertThat(plan.itineraries().getFirst().etaSource()).isEqualTo(EtaSource.FALLBACK);
		assertThat(plan.itineraries().getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.first()
			.satisfies(step -> assertThat(step.reasonCodes())
				.containsExactly("REALTIME_UNAVAILABLE_PLANNED_USED", "PROVIDER_QUOTA_EXCEEDED"));
		assertThat(plan.statuses())
			.containsExactly(RouteV2Status.FOUND, RouteV2Status.REALTIME_UNAVAILABLE_PLANNED_USED);
	}

	@Test
	@DisplayName("pre-scan·post-scan realtime provider 예외는 planned fallback으로 fail closed한다")
	void routeV2PlannerFailsClosedWhenRealtimeProviderThrows() {
		var repository = new InMemoryRouteSearchRepository();
		var routeSearchService = new RouteSearchService(
			repository,
			repository,
			new TimetableAlignedRampAccessibleTransitMasterPort(),
			CLOCK,
			query -> {
				throw new IllegalStateException("provider down");
			}
		);
		var planner = new RouteV2Planner(routeSearchService, routeTimetablePort());

		var plan = planner.search(new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-a", "station-b", OffsetDateTime.parse("2026-07-01T09:00:00+09:00"),
			MobilityType.SENIOR, ConstraintMode.PREFER_STEP_FREE, true, 1, 1));

		assertThat(plan.itineraries().getFirst().etaSource()).isEqualTo(EtaSource.FALLBACK);
		assertThat(plan.itineraries().getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.first()
			.satisfies(step -> assertThat(step.reasonCodes())
				.containsExactly("REALTIME_UNAVAILABLE_PLANNED_USED", "REALTIME_PROVIDER_ERROR"));
		assertThat(plan.statuses())
			.containsExactly(RouteV2Status.FOUND, RouteV2Status.REALTIME_UNAVAILABLE_PLANNED_USED);
	}

	@Test
	@DisplayName("V2 search port command는 adapter를 우회한 잘못된 planner 조건을 거부한다")
	void routeV2SearchCommandRejectsInvalidPlannerBounds() {
		assertThatThrownBy(() -> routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, -1, 1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("maxTransfers must not be negative");
		assertThatThrownBy(() -> routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 4, 1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("maxTransfers must be 3 or less");
		assertThatThrownBy(() -> routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("alternativeCount must be at least 1");
		assertThatThrownBy(() -> routeV2Command(ConstraintMode.PREFER_STEP_FREE, MobilityType.SENIOR, 1, 4))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("alternativeCount must be 3 or less");
	}

	@Test
	@DisplayName("휠체어 이동 유형은 우회 거리가 길어도 무단차 환승역을 우선한다")
	void wheelchairRoutePrefersStepFreeTransferStationEvenWhenDetourIsLong() {
		var repository = new InMemoryRouteSearchRepository();
		var transferService = new RouteSearchService(
			repository,
			repository,
			new LongDetourTransferAccessibilityTransitMasterPort(),
			CLOCK
		);

		var result = transferService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.WHEELCHAIR
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.FOUND);
		assertThat(result.steps())
			.extracting("title")
			.contains(
				"무단차환승역역에서 B 노선 승강장으로 환승"
			);
	}

	@Test
	@DisplayName("유모차 이동 유형은 계단만 있는 역 접근 경로를 경고하고 점수를 높인다")
	void strollerRouteWarnsStairOnlyStationAccess() {
		var stairOnlyRepository = new InMemoryRouteSearchRepository();
		var stairOnlyService = new RouteSearchService(
			stairOnlyRepository,
			stairOnlyRepository,
			new StairOnlyTransitMasterPort(),
			CLOCK
		);
		var accessibleRepository = new InMemoryRouteSearchRepository();
		var accessibleService = new RouteSearchService(
			accessibleRepository,
			accessibleRepository,
			new RampAccessibleTransitMasterPort(),
			CLOCK
		);

		var stairOnlyResult = stairOnlyService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.STROLLER
		));
		var accessibleResult = accessibleService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.STROLLER
		));

		assertThat(stairOnlyResult.status()).isEqualTo(RouteSearchStatus.FOUND);
		assertThat(stairOnlyResult.warnings())
			.extracting("code")
			.contains(RouteWarningCode.STAIR_ONLY_ACCESS);
		assertThat(stairOnlyResult.steps().getFirst().includesStairs()).isTrue();
		assertThat(stairOnlyResult.steps().get(1).includesStairs()).isFalse();
		assertThat(stairOnlyResult.steps().get(2).includesStairs()).isTrue();
		assertThat(stairOnlyResult.steps())
			.extracting("stairAccessState")
			.containsExactly("STAIR_ONLY", "UNKNOWN", "STAIR_ONLY");
		assertThat(stairOnlyResult.score()).isGreaterThan(accessibleResult.score());
	}

	@Test
	@DisplayName("계단 전용 환승역은 환승 단계에 계단 포함으로 표시한다")
	void routeStepMarksStairOnlyTransferAccess() {
		var repository = new InMemoryRouteSearchRepository();
		var transferService = new RouteSearchService(
			repository,
			repository,
			new StairOnlyTransferTransitMasterPort(),
			CLOCK
		);

		var result = transferService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.STROLLER
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.FOUND);
		assertThat(result.steps())
			.extracting("includesStairs")
			.containsExactly(false, false, true, false, false);
	}

	@Test
	@DisplayName("계단 접근 경고 점수는 이동 유형별 이동 부담을 다르게 반영한다")
	void stairOnlyWarningScoreReflectsMobilityProfileCost() {
		assertThat(stairOnlyScoreByMobilityType())
			.containsEntry(MobilityType.TEMPORARY_INJURY, 77)
			.containsEntry(MobilityType.STROLLER, 71)
			.containsEntry(MobilityType.PREGNANT, 62)
			.containsEntry(MobilityType.SENIOR, 59)
			.containsEntry(MobilityType.LUGGAGE, 53);
	}

	@Test
	@DisplayName("경로 단계 설명은 이동 유형별로 필요한 접근 조건을 안내한다")
	void routeStepDescriptionReflectsMobilityProfile() {
		assertThat(firstStepDescription(MobilityType.SENIOR))
			.isEqualTo("계단을 피하고 이동 거리가 짧은 출구를 먼저 확인합니다.");
		assertThat(firstStepDescription(MobilityType.STROLLER))
			.isEqualTo("엘리베이터와 넓은 통로가 있는 출구를 먼저 확인합니다.");
		assertThat(firstStepDescription(MobilityType.WHEELCHAIR))
			.isEqualTo("엘리베이터, 리프트, 경사로 연결을 먼저 확인합니다.");
		assertThat(firstStepDescription(MobilityType.PREGNANT))
			.isEqualTo("엘리베이터와 짧은 이동 동선을 먼저 확인합니다.");
		assertThat(firstStepDescription(MobilityType.TEMPORARY_INJURY))
			.isEqualTo("계단을 피하고 쉬어 갈 수 있는 동선을 먼저 확인합니다.");
		assertThat(firstStepDescription(MobilityType.LUGGAGE))
			.isEqualTo("엘리베이터와 넓은 출구 동선을 먼저 확인합니다.");
	}

	@Test
	@DisplayName("휠체어 이동 유형은 신뢰도 낮은 계단 정보만으로 경로를 차단하지 않는다")
	void wheelchairRouteDoesNotBlockWithLowConfidenceStairOnlyData() {
		var repository = new InMemoryRouteSearchRepository();
		var lowConfidenceService = new RouteSearchService(
			repository,
			repository,
			new LowConfidenceStairOnlyTransitMasterPort(),
			CLOCK
		);

		var result = lowConfidenceService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.WHEELCHAIR
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.FOUND);
		assertThat(result.blockedReasons()).isEmpty();
		assertThat(result.warnings())
			.extracting("code")
			.contains(RouteWarningCode.LOW_DATA_CONFIDENCE);
	}

	@Test
	@DisplayName("휠체어 이동 유형은 정상 램프가 있으면 계단 출구가 있어도 경로를 제공한다")
	void wheelchairRouteAllowsNormalRampAsStepFreeAccess() {
		var repository = new InMemoryRouteSearchRepository();
		var rampService = new RouteSearchService(
			repository,
			repository,
			new RampAccessibleTransitMasterPort(),
			CLOCK
		);

		var result = rampService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.WHEELCHAIR
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.FOUND);
		assertThat(result.blockedReasons()).isEmpty();
	}

	@Test
	@DisplayName("휠체어 이동 유형은 출구 요약만 있고 검증된 시설 행이 없으면 strict 경로를 차단한다")
	void wheelchairRouteBlocksElevatorConnectedExitWithoutFacilityRow() {
		var repository = new InMemoryRouteSearchRepository();
		var exitSummaryService = new RouteSearchService(
			repository,
			repository,
			new ExitSummaryAccessibleTransitMasterPort(),
			CLOCK
		);

		var result = exitSummaryService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.WHEELCHAIR
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.BLOCKED);
		assertThat(result.steps()).isEmpty();
		assertThat(result.blockedReasons())
			.containsExactly("계단 없는 역 접근 경로를 확인할 수 없습니다.");
	}

	@Test
	@DisplayName("휠체어 이동 유형은 출구와 연결되지 않은 시설 행만 있으면 strict 경로를 차단한다")
	void wheelchairRouteBlocksUnlinkedStepFreeFacilityRow() {
		var repository = new InMemoryRouteSearchRepository();
		var unlinkedFacilityService = new RouteSearchService(
			repository,
			repository,
			new UnlinkedStepFreeFacilityTransitMasterPort(),
			CLOCK
		);

		var result = unlinkedFacilityService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.WHEELCHAIR
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.BLOCKED);
		assertThat(result.steps()).isEmpty();
		assertThat(result.blockedReasons())
			.containsExactly("계단 없는 역 접근 경로를 확인할 수 없습니다.");
	}

	@Test
	@DisplayName("도착역에 접근 가능한 출구가 있으면 마지막 이동 단계에서 먼저 안내한다")
	void routeDescribesRecommendedDestinationExit() {
		var repository = new InMemoryRouteSearchRepository();
		var exitGuidanceService = new RouteSearchService(
			repository,
			repository,
			new ExitSummaryAccessibleTransitMasterPort(),
			CLOCK
		);

		var result = exitGuidanceService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.SENIOR
		));

		assertThat(result.steps().getLast().title()).contains("출구 접근성 정보를 확인");
		assertThat(result.steps().getLast().description()).contains("2번 출구");
		assertThat(result.steps().getLast().description()).contains("엘리베이터");
	}

	@Test
	@DisplayName("출구 신뢰도가 높아도 무단차 시설 신뢰도가 낮으면 경고한다")
	void routeWarnsWhenStepFreeFacilityConfidenceIsLow() {
		var repository = new InMemoryRouteSearchRepository();
		var lowConfidenceFacilityService = new RouteSearchService(
			repository,
			repository,
			new LowConfidenceStepFreeFacilityTransitMasterPort(),
			CLOCK
		);

		var result = lowConfidenceFacilityService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.STROLLER
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.FOUND);
		assertThat(result.warnings())
			.extracting("code")
			.contains(RouteWarningCode.LOW_DATA_CONFIDENCE);
	}

	@Test
	@DisplayName("접근성 시설 갱신일이 30일을 넘으면 이동 전 확인 경고를 표시한다")
	void routeWarnsWhenAccessibilityFacilityDataIsStale() {
		var repository = new InMemoryRouteSearchRepository();
		var staleDataService = new RouteSearchService(
			repository,
			repository,
			new StaleAccessibilityFacilityTransitMasterPort(),
			CLOCK
		);

		var result = staleDataService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.SENIOR
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.FOUND);
		assertThat(result.warnings())
			.extracting("code")
			.contains(RouteWarningCode.STALE_ACCESSIBILITY_DATA);
		assertThat(result.warnings())
			.allSatisfy(warning -> assertThat(warning.toString()).doesNotContain("접근성 시설 정보"));
	}

	@Test
	@DisplayName("휠체어 이동 유형은 고장난 엘리베이터만 있으면 계단 없는 경로로 보지 않는다")
	void wheelchairRouteBlocksBrokenElevatorAsStepFreeAccess() {
		var repository = new InMemoryRouteSearchRepository();
		var brokenElevatorService = new RouteSearchService(
			repository,
			repository,
			new BrokenElevatorTransitMasterPort(),
			CLOCK
		);

		var result = brokenElevatorService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.WHEELCHAIR
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.BLOCKED);
		assertThat(result.steps()).isEmpty();
		assertThat(result.blockedReasons())
			.containsExactly("계단 없는 역 접근 경로를 확인할 수 없습니다.");
	}

	@Test
	@DisplayName("휠체어 이동 유형은 고장난 엘리베이터 출구만 있는 역을 차단한다")
	void wheelchairRouteBlocksBrokenElevatorOnlyExit() {
		var repository = new InMemoryRouteSearchRepository();
		var brokenElevatorOnlyService = new RouteSearchService(
			repository,
			repository,
			new BrokenElevatorOnlyTransitMasterPort(),
			CLOCK
		);

		var result = brokenElevatorOnlyService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.WHEELCHAIR
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.BLOCKED);
		assertThat(result.steps()).isEmpty();
		assertThat(result.blockedReasons())
			.containsExactly("계단 없는 역 접근 경로를 확인할 수 없습니다.");
	}

	@Test
	@DisplayName("휠체어 이동 유형은 역 내부 활성 간선이 계단만 제공하면 경로를 차단한다")
	void wheelchairRouteBlocksWhenInternalEdgesRequireStairs() {
		var repository = new InMemoryRouteSearchRepository();
		var routeEdgeService = new RouteSearchService(
			repository,
			repository,
			new InternalStairEdgeTransitMasterPort(),
			CLOCK
		);

		var result = routeEdgeService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.WHEELCHAIR
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.BLOCKED);
		assertThat(result.steps()).isEmpty();
		assertThat(result.blockedReasons())
			.containsExactly("계단 없는 역 접근 경로를 확인할 수 없습니다.");
		assertThat(result.warnings())
			.extracting("code")
			.contains(RouteWarningCode.STAIR_ONLY_ACCESS);
	}

	@Test
	@DisplayName("휠체어 이동 유형은 비내부 간선이 섞여도 내부 계단 간선을 기준으로 차단한다")
	void wheelchairRouteIgnoresNonInternalEdgesWhenCheckingInternalStairs() {
		var repository = new InMemoryRouteSearchRepository();
		var routeEdgeService = new RouteSearchService(
			repository,
			repository,
			new MixedInternalAndTrainEdgeTransitMasterPort(),
			CLOCK
		);

		var result = routeEdgeService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.WHEELCHAIR
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.BLOCKED);
		assertThat(result.steps()).isEmpty();
		assertThat(result.warnings())
			.extracting("code")
			.contains(RouteWarningCode.STAIR_ONLY_ACCESS);
	}

	@Test
	@DisplayName("유모차 이동 유형은 역 내부 활성 간선의 계단 포함을 경고와 단계에 표시한다")
	void strollerRouteWarnsWhenInternalEdgesIncludeStairs() {
		var repository = new InMemoryRouteSearchRepository();
		var routeEdgeService = new RouteSearchService(
			repository,
			repository,
			new InternalStairEdgeTransitMasterPort(),
			CLOCK
		);

		var result = routeEdgeService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.STROLLER
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.FOUND);
		assertThat(result.warnings())
			.extracting("code")
			.contains(RouteWarningCode.STAIR_ONLY_ACCESS);
		assertThat(result.steps())
			.extracting("includesStairs")
			.containsExactly(true, false, true);
	}

	@Test
	@DisplayName("휠체어 이동 유형은 내부 간선의 엘리베이터가 고장나면 계단 없는 경로로 보지 않는다")
	void wheelchairRouteBlocksWhenInternalEdgeElevatorIsBroken() {
		var repository = new InMemoryRouteSearchRepository();
		var routeEdgeService = new RouteSearchService(
			repository,
			repository,
			new BrokenElevatorInternalEdgeTransitMasterPort(),
			CLOCK
		);

		var result = routeEdgeService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.WHEELCHAIR
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.BLOCKED);
		assertThat(result.steps()).isEmpty();
		assertThat(result.warnings())
			.extracting("code")
			.contains(RouteWarningCode.STAIR_ONLY_ACCESS);
	}

	@Test
	@DisplayName("역 내부 Dijkstra 후보는 경로 리스트를 보관하지 않는다")
	void internalRouteCandidateDoesNotCarryPathList() {
		Class<?> candidateType = java.util.Arrays.stream(RouteSearchService.class.getDeclaredClasses())
			.filter(type -> type.getSimpleName().equals("InternalRouteCandidate"))
			.findFirst()
			.orElseThrow();

		assertThat(candidateType.getRecordComponents())
			.extracting(java.lang.reflect.RecordComponent::getName)
			.containsExactly("nodeId", "cost");
	}

	@Test
	@DisplayName("역 내부 predecessor는 더 짧은 후속 relax의 전체 경로를 순서대로 복원한다")
	void internalRouteReconstructsLaterShorterRelaxation() {
		var repository = new InMemoryRouteSearchRepository();
		var routeEdgeService = new RouteSearchService(
			repository,
			repository,
			new LaterShorterInternalTransitMasterPort(),
			CLOCK
		);

		var result = routeEdgeService.searchInternalRoute(new SearchInternalRouteCommand(
			"station-a",
			"node-station-a-entrance",
			"node-station-a-platform",
			MobilityType.STROLLER
		));

		assertThat(result.steps())
			.extracting("edgeId")
			.containsExactly("edge-shortcut", "edge-shortcut-to-merge", "edge-merge-to-platform");
	}

	@Test
	@DisplayName("역 내부 이동 경로는 활성 노드와 간선을 단계로 반환한다")
	void searchInternalRouteReturnsActiveRouteEdgesAsSteps() {
		var result = service.searchInternalRoute(new SearchInternalRouteCommand(
			"station-sangnoksu",
			"node-sangnoksu-elevator-1",
			"node-sangnoksu-faregate",
			MobilityType.WHEELCHAIR
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.FOUND);
		assertThat(result.totalDistanceMeters()).isEqualTo(28);
		assertThat(result.totalEstimatedSeconds()).isEqualTo(75);
		assertThat(result.blockedReasons()).isEmpty();
		assertThat(result.steps()).hasSize(1);
		assertThat(result.steps().getFirst().edgeId()).isEqualTo("edge-sangnoksu-elevator-to-faregate");
		assertThat(result.steps().getFirst().fromNodeName()).isEqualTo("1번 출구 엘리베이터");
		assertThat(result.steps().getFirst().toNodeName()).isEqualTo("개찰구");
		assertThat(result.steps().getFirst().edgeType()).isEqualTo(RouteEdgeType.WALK);
		assertThat(result.steps().getFirst().requiresElevator()).isTrue();
		assertThat(result.steps().getFirst().includesStairs()).isFalse();
	}

	@Test
	@DisplayName("backend planner는 allowlist 없는 역외 환승 간선을 내부 경로 후보로 승격하지 않는다")
	void searchInternalRouteIgnoresOutOfStationTransferEdges() {
		var repository = new InMemoryRouteSearchRepository();
		var routeEdgeService = new RouteSearchService(
			repository,
			repository,
			new OutOfStationTransferOnlyTransitMasterPort(),
			CLOCK
		);

		assertThatThrownBy(() -> routeEdgeService.searchInternalRoute(new SearchInternalRouteCommand(
			"station-a",
			"node-station-a-entrance",
			"node-station-a-platform",
			MobilityType.STROLLER
		)))
			.isInstanceOf(RouteNotFoundException.class)
			.hasMessage("연결 가능한 경로를 찾을 수 없습니다.");
	}

	@Test
	@DisplayName("휠체어 역 내부 이동 경로는 계단만 있으면 차단한다")
	void wheelchairInternalRouteBlocksStairOnlyInternalPath() {
		var repository = new InMemoryRouteSearchRepository();
		var routeEdgeService = new RouteSearchService(
			repository,
			repository,
			new InternalStairEdgeTransitMasterPort(),
			CLOCK
		);

		var result = routeEdgeService.searchInternalRoute(new SearchInternalRouteCommand(
			"station-a",
			"node-station-a-entrance",
			"node-station-a-platform",
			MobilityType.WHEELCHAIR
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.BLOCKED);
		assertThat(result.steps()).isEmpty();
		assertThat(result.blockedReasons())
			.containsExactly("계단 없는 내부 이동 경로를 찾을 수 없습니다.");
		assertThat(result.warnings())
			.extracting("code")
			.containsExactly(RouteWarningCode.STAIR_ONLY_ACCESS);
	}

	@Test
	@DisplayName("휠체어 역 내부 이동 경로는 해당 간선의 엘리베이터가 고장나면 다른 정상 시설이 있어도 차단한다")
	void wheelchairInternalRouteBlocksBrokenEdgeElevatorEvenWithOtherNormalFacility() {
		var repository = new InMemoryRouteSearchRepository();
		var routeEdgeService = new RouteSearchService(
			repository,
			repository,
			new BrokenElevatorWithOtherNormalFacilityInternalEdgeTransitMasterPort(),
			CLOCK
		);

		var result = routeEdgeService.searchInternalRoute(new SearchInternalRouteCommand(
			"station-a",
			"node-station-a-entrance",
			"node-station-a-platform",
			MobilityType.WHEELCHAIR
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.BLOCKED);
		assertThat(result.steps()).isEmpty();
		assertThat(result.blockedReasons())
			.containsExactly("계단 없는 내부 이동 경로를 찾을 수 없습니다.");
	}

	@Test
	@DisplayName("유모차 역 내부 이동 경로는 계단 포함 구간을 경고로 표시한다")
	void strollerInternalRouteWarnsStairIncludedInternalPath() {
		var repository = new InMemoryRouteSearchRepository();
		var routeEdgeService = new RouteSearchService(
			repository,
			repository,
			new InternalStairEdgeTransitMasterPort(),
			CLOCK
		);

		var result = routeEdgeService.searchInternalRoute(new SearchInternalRouteCommand(
			"station-a",
			"node-station-a-entrance",
			"node-station-a-platform",
			MobilityType.STROLLER
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.FOUND);
		assertThat(result.steps()).hasSize(1);
		assertThat(result.steps().getFirst().includesStairs()).isTrue();
		assertThat(result.warnings())
			.extracting("code")
			.containsExactly(RouteWarningCode.STAIR_ONLY_ACCESS);
	}

	@Test
	@DisplayName("역 내부 ETA는 burden 점수보다 먼저 비교하고 표시 초는 시간 항만 합산한다")
	void internalRouteRanksEtaBeforeBurdenScore() {
		var repository = new InMemoryRouteSearchRepository();
		var routeEdgeService = new RouteSearchService(
			repository,
			repository,
			new EtaBurdenSplitInternalTransitMasterPort(),
			CLOCK
		);

		var result = routeEdgeService.searchInternalRoute(new SearchInternalRouteCommand(
			"station-a",
			"node-station-a-entrance",
			"node-station-a-platform",
			MobilityType.STROLLER
		));

		assertThat(result.status()).isEqualTo(RouteSearchStatus.FOUND);
		assertThat(result.totalEstimatedSeconds()).isEqualTo(90);
		assertThat(result.totalDistanceMeters()).isEqualTo(400);
		assertThat(result.steps())
			.extracting("edgeId")
			.containsExactly("edge-fast-stair");
		assertThat(result.warnings())
			.extracting("code")
			.containsExactly(RouteWarningCode.STAIR_ONLY_ACCESS);
	}

	@Test
	@DisplayName("역 내부 이동 경로는 같은 역에 속한 노드를 요구한다")
	void searchInternalRouteRequiresNodesInStation() {
		assertThatThrownBy(() -> service.searchInternalRoute(new SearchInternalRouteCommand(
			"station-sangnoksu",
			"node-sangnoksu-elevator-1",
			"missing-node",
			MobilityType.SENIOR
		)))
			.isInstanceOf(RouteNotFoundException.class)
			.hasMessage("연결 가능한 경로를 찾을 수 없습니다.");
	}

	@Test
	@DisplayName("경로 검색은 존재하는 역과 공통 노선을 요구한다")
	void searchRouteRequiresExistingStationsAndSharedLine() {
		assertThatThrownBy(() -> service.searchRoute(new SearchRouteCommand(
			"missing",
			"station-sadang",
			MobilityType.SENIOR
		)))
			.isInstanceOf(StationNotFoundException.class)
			.hasMessage("역 정보를 찾을 수 없습니다.");

		var repository = new InMemoryRouteSearchRepository();
		var disconnectedService = new RouteSearchService(
			repository,
			repository,
			new DisconnectedTransitMasterPort(),
			CLOCK
		);

		assertThatThrownBy(() -> disconnectedService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.SENIOR
		)))
			.isInstanceOf(RouteNotFoundException.class)
			.hasMessage("연결 가능한 경로를 찾을 수 없습니다.");
	}

	@Test
	@DisplayName("노선 코드가 없으면 노선명을 경로 단계 제목에 사용한다")
	void searchRouteUsesLineNameWhenLineCodeIsMissing() {
		var repository = new InMemoryRouteSearchRepository();
		var missingLineCodeService = new RouteSearchService(
			repository,
			repository,
			new MissingLineCodeTransitMasterPort(),
			CLOCK
		);

		var result = missingLineCodeService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.SENIOR
		));

		assertThat(result.steps())
			.extracting("title")
			.first()
			.isEqualTo("출발역역에서 테스트 노선 승강장으로 이동");
	}

	@Test
	@DisplayName("노선 코드가 빈 값이면 노선명을 경로 단계 제목에 사용한다")
	void searchRouteUsesLineNameWhenLineCodeIsBlank() {
		var repository = new InMemoryRouteSearchRepository();
		var blankLineCodeService = new RouteSearchService(
			repository,
			repository,
			new BlankLineCodeTransitMasterPort(),
			CLOCK
		);

		var result = blankLineCodeService.searchRoute(new SearchRouteCommand(
			"station-a",
			"station-b",
			MobilityType.SENIOR
		));

		assertThat(result.steps())
			.extracting("title")
			.first()
			.isEqualTo("출발역역에서 테스트 노선 승강장으로 이동");
	}

	@Test
	@DisplayName("알 수 없는 경로 검색 식별자는 조회할 수 없다")
	void getRouteSearchRequiresKnownRouteSearchId() {
		assertThatThrownBy(() -> service.getRouteSearch("route-missing"))
			.isInstanceOf(RouteSearchNotFoundException.class)
			.hasMessage("경로 검색 결과를 찾을 수 없습니다.");
	}

	private static Map<MobilityType, Integer> stairOnlyScoreByMobilityType() {
		return Map.of(
			MobilityType.SENIOR, scoreFor(MobilityType.SENIOR, new StairOnlyTransitMasterPort()),
			MobilityType.STROLLER, scoreFor(MobilityType.STROLLER, new StairOnlyTransitMasterPort()),
			MobilityType.PREGNANT, scoreFor(MobilityType.PREGNANT, new StairOnlyTransitMasterPort()),
			MobilityType.TEMPORARY_INJURY, scoreFor(MobilityType.TEMPORARY_INJURY, new StairOnlyTransitMasterPort()),
			MobilityType.LUGGAGE, scoreFor(MobilityType.LUGGAGE, new StairOnlyTransitMasterPort())
		);
	}

	private static int scoreFor(MobilityType mobilityType, LoadTransitMasterPort transitMasterPort) {
		var repository = new InMemoryRouteSearchRepository();
		var routeSearchService = new RouteSearchService(repository, repository, transitMasterPort, CLOCK);
		return routeSearchService.searchRoute(new SearchRouteCommand("station-a", "station-b", mobilityType)).score();
	}

	private static RouteV2Planner routeV2Planner(LoadTransitMasterPort transitMasterPort) {
		var repository = new InMemoryRouteSearchRepository();
		return new RouteV2Planner(new RouteSearchService(repository, repository, transitMasterPort, CLOCK));
	}

	private static RouteSearchUseCase legacySearchMustNotBeCalled() {
		return new RouteSearchUseCase() {
			@Override
			public RouteSearchResult searchRoute(SearchRouteCommand command) {
				throw new AssertionError("RouteV2Planner must not delegate timetable-backed search to legacy graph search");
			}

			@Override
			public List<RouteSearchResult> searchRouteAlternatives(SearchRouteCommand command, int alternativeCount) {
				throw new AssertionError("RouteV2Planner must not delegate timetable-backed search to legacy graph search");
			}

			@Override
			public InternalRouteResult searchInternalRoute(SearchInternalRouteCommand command) {
				throw new AssertionError("RouteV2Planner must not call internal route search");
			}

			@Override
			public RouteSearchResult getRouteSearch(String routeSearchId) {
				throw new AssertionError("RouteV2Planner must not load legacy route search results");
			}

			@Override
			public RouteRefreshResult refreshRoute(String routeSearchId) {
				throw new AssertionError("RouteV2Planner must not refresh legacy route search results");
			}

			@Override
			public RouteFeedback submitRouteFeedback(SubmitRouteFeedbackCommand command) {
				throw new AssertionError("RouteV2Planner must not submit feedback during search");
			}
		};
	}

	private static RouteSearchUseCase stabilizingRouteSearchUseCase(List<RouteSearchResult> stabilizedResults) {
		return new RouteSearchUseCase() {
			@Override
			public RouteSearchResult searchRoute(SearchRouteCommand command) {
				throw new AssertionError("RouteV2Planner must use timetable scan for this test");
			}

			@Override
			public List<RouteSearchResult> searchRouteAlternatives(SearchRouteCommand command, int alternativeCount) {
				throw new AssertionError("RouteV2Planner must use timetable scan for this test");
			}

			@Override
			public List<RouteSearchResult> stabilizeTimetableRouteCandidates(
				SearchRouteCommand command,
				int candidateCount,
				int alternativeCount,
				List<RouteSearchResult> timetableResults,
				java.util.function.UnaryOperator<List<RouteSearchResult>> selectCandidates
			) {
				return selectCandidates.apply(stabilizedResults.stream()
					.limit(candidateCount)
					.toList());
			}

			@Override
			public InternalRouteResult searchInternalRoute(SearchInternalRouteCommand command) {
				throw new AssertionError("RouteV2Planner must not call internal route search");
			}

			@Override
			public RouteSearchResult getRouteSearch(String routeSearchId) {
				throw new AssertionError("RouteV2Planner must not load legacy route search results");
			}

			@Override
			public RouteRefreshResult refreshRoute(String routeSearchId) {
				throw new AssertionError("RouteV2Planner must not refresh legacy route search results");
			}

			@Override
			public RouteFeedback submitRouteFeedback(SubmitRouteFeedbackCommand command) {
				throw new AssertionError("RouteV2Planner must not submit feedback during search");
			}
		};
	}

	private static RouteSearchResult routeSearchResultWithAccessState(
		String routeSearchId,
		String stairAccessState,
		boolean requiresAccessibilityCheck
	) {
		return new RouteSearchResult(
			routeSearchId,
			"station-a",
			"출발역",
			"station-b",
			"도착역",
			MobilityType.SENIOR,
			RouteSearchStatus.FOUND,
			"seoul-4",
			"4호선",
			0,
			List.of(
				timetableStep(1, "entry", stairAccessState, requiresAccessibilityCheck),
				timetableStep(2, "ride", "AVAILABLE", false),
				timetableStep(3, "exit", stairAccessState, requiresAccessibilityCheck)
			),
			List.of(),
			List.of(),
			LocalDate.of(2026, 7, 1).atStartOfDay()
		);
	}

	private static RouteSearchResult transferRouteSearchResultWithAccessState(
		String routeSearchId,
		String stairAccessState,
		boolean requiresAccessibilityCheck
	) {
		return new RouteSearchResult(
			routeSearchId,
			"station-a",
			"출발역",
			"station-b",
			"도착역",
			MobilityType.SENIOR,
			RouteSearchStatus.FOUND,
			"seoul-4",
			"4호선",
			0,
			List.of(
				timetableStep(1, "entry", stairAccessState, requiresAccessibilityCheck, 3),
				timetableStep(2, "ride", "AVAILABLE", false, 3),
				timetableStep(3, "transfer", stairAccessState, requiresAccessibilityCheck, 3),
				timetableStep(4, "ride", "AVAILABLE", false, 3),
				timetableStep(5, "exit", stairAccessState, requiresAccessibilityCheck, 3)
			),
			List.of(),
			List.of(),
			LocalDate.of(2026, 7, 1).atStartOfDay()
		);
	}

	private static RouteStep timetableStep(
		int sequence,
		String stepType,
		String stairAccessState,
		boolean requiresAccessibilityCheck
	) {
		return timetableStep(sequence, stepType, stairAccessState, requiresAccessibilityCheck, 5);
	}

	private static RouteStep timetableStep(
		int sequence,
		String stepType,
		String stairAccessState,
		boolean requiresAccessibilityCheck,
		int estimatedMinutes
	) {
		boolean includesStairs = "STAIR_ONLY".equals(stairAccessState);
		return new RouteStep(
			sequence,
			stepType,
			stepType,
			"시간표 경로",
			"seoul-4",
			"4호선",
			"station-a",
			"station-b",
			estimatedMinutes,
			100,
			includesStairs,
			stairAccessState,
			requiresAccessibilityCheck,
			EtaSource.PLANNED.name(),
			"TIMETABLE",
			"시간표"
		);
	}

	private static LoadRouteTimetablePort routeTimetablePort() {
		return () -> new LoadRouteTimetablePort.RouteTimetable(
			List.of(new LoadRouteTimetablePort.ServiceCalendar(
				"weekday-2026",
				true,
				true,
				true,
				true,
				true,
				false,
				false,
				LocalDate.parse("2026-07-01"),
				LocalDate.parse("2026-12-31"),
				"Asia/Seoul"
			)),
			List.of(),
			List.of(new LoadRouteTimetablePort.TransitRoute(
				"route-seoul-4",
				"seoul-4",
				"4",
				"수도권 4호선",
				"사당 방면",
				"Asia/Seoul"
			)),
			List.of(new LoadRouteTimetablePort.TransitTrip(
				"trip-seoul-4-0900",
				"route-seoul-4",
				"weekday-2026",
				"사당",
				"0",
				"LOCAL",
				0
			)),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-0900", 1, "station-a", "seoul-4", 32820, 32820, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-0900", 2, "station-b", "seoul-4", 33420, 33420, 0, 0)
			),
			List.of()
		);
	}

	private static LoadRouteTimetablePort preScanRealtimeRouteTimetablePort() {
		return () -> new LoadRouteTimetablePort.RouteTimetable(
			List.of(new LoadRouteTimetablePort.ServiceCalendar(
				"weekday-2026", true, true, true, true, true, false, false,
				LocalDate.parse("2026-07-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul")),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitRoute(
					"route-local", "seoul-4", "4", "수도권 4호선", "사당 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute(
					"route-express", "seoul-4", "4", "수도권 4호선", "사당 방면", "Asia/Seoul")),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"trip-local", "route-local", "weekday-2026", "station-b", "0",
					"SUBWAY", "LOCAL", "train-local", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"trip-express", "route-express", "weekday-2026", "station-b", "0",
					"SUBWAY", "EXPRESS", "train-express", 0)),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-local", 1, "station-a", "seoul-4", 32_820, 32_820, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-local", 2, "station-b", "seoul-4", 34_020, 34_020, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-express", 1, "station-a", "seoul-4", 32_940, 32_940, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-express", 2, "station-b", "seoul-4", 33_540, 33_540, 0, 0)),
			List.of());
	}

	private static LoadRouteTimetablePort dualOriginRealtimeRouteTimetablePort() {
		return () -> new LoadRouteTimetablePort.RouteTimetable(
			List.of(new LoadRouteTimetablePort.ServiceCalendar(
				"weekday-2026", true, true, true, true, true, false, false,
				LocalDate.parse("2026-07-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul")),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitRoute(
					"route-a", "seoul-4", "4", "수도권 4호선", "도착 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute(
					"route-c", "seoul-4", "4", "수도권 4호선", "도착 방면", "Asia/Seoul")),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"trip-a", "route-a", "weekday-2026", "station-b", "0",
					"SUBWAY", "LOCAL", "train-a", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"trip-c", "route-c", "weekday-2026", "station-b", "0",
					"SUBWAY", "LOCAL", "train-c", 0)),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-a", 1, "station-a", "seoul-4", 32_940, 32_940, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-a", 2, "station-b", "seoul-4", 33_540, 33_540, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-c", 1, "station-c", "seoul-4", 32_940, 32_940, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-c", 2, "station-b", "seoul-4", 33_540, 33_540, 0, 0)),
			List.of());
	}

	// 시간표가 비어있지 않지만(hasRouteTimetable()==true) station-a·station-b는 커버하지 않는 포트.
	// 커버리지 게이트가 비커버 O/D를 graph 폴백으로 보내는지 검증하는 데 쓴다.
	private static LoadRouteTimetablePort uncoveredRouteTimetablePort() {
		return () -> new LoadRouteTimetablePort.RouteTimetable(
			List.of(new LoadRouteTimetablePort.ServiceCalendar(
				"weekday-2026",
				true,
				true,
				true,
				true,
				true,
				false,
				false,
				LocalDate.parse("2026-07-01"),
				LocalDate.parse("2026-12-31"),
				"Asia/Seoul"
			)),
			List.of(),
			List.of(new LoadRouteTimetablePort.TransitRoute(
				"route-seoul-4",
				"seoul-4",
				"4",
				"수도권 4호선",
				"사당 방면",
				"Asia/Seoul"
			)),
			List.of(new LoadRouteTimetablePort.TransitTrip(
				"trip-seoul-4-0900",
				"route-seoul-4",
				"weekday-2026",
				"사당",
				"0",
				"LOCAL",
				0
			)),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-0900", 1, "station-x", "seoul-4", 32820, 32820, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-0900", 2, "station-y", "seoul-4", 33420, 33420, 0, 0)
			),
			List.of()
		);
	}

	private static LoadRouteTimetablePort feedValidityRouteTimetablePort(LocalDate feedEndDate) {
		var base = routeTimetablePort().loadRouteTimetable();
		return () -> new LoadRouteTimetablePort.RouteTimetable(
			base.serviceCalendars(),
			base.serviceCalendarDates(),
			base.transitRoutes(),
			base.transitTrips(),
			base.transitStopTimes(),
			base.transitFrequencies(),
			feedEndDate
		);
	}

	private static LoadRouteTimetablePort frequencyRouteTimetablePort() {
		return () -> routeTimetable(
			List.of(
				new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-frequency", 1, "station-a", "seoul-4", 32400, 32400, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-frequency", 2, "station-b", "seoul-4", 33300, 33300, 0, 0)
			),
			List.of(new LoadRouteTimetablePort.TransitFrequency("trip-seoul-4-frequency", 32400, 36000, 600, false))
		);
	}

	private static LoadRouteTimetablePort lateNightRouteTimetablePort() {
		return () -> {
			var timetable = routeTimetable(
				List.of(
					new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-late", 1, "station-a", "seoul-4", 87000, 87000, 0, 0),
					new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-late", 2, "station-b", "seoul-4", 87900, 87900, 0, 0)
				),
				List.of()
			);
			return new LoadRouteTimetablePort.RouteTimetable(
				List.of(new LoadRouteTimetablePort.ServiceCalendar(
					"weekday-2026",
					true,
					true,
					true,
					true,
					true,
					false,
					false,
					LocalDate.parse("2026-07-01"),
					LocalDate.parse("2026-07-01"),
					"Asia/Seoul"
				)),
				timetable.serviceCalendarDates(),
				timetable.transitRoutes(),
				timetable.transitTrips(),
				timetable.transitStopTimes(),
				timetable.transitFrequencies()
			);
		};
	}

	private static LoadRouteTimetablePort lateNightMissedLastTrainRouteTimetablePort() {
		return () -> new LoadRouteTimetablePort.RouteTimetable(
			List.of(new LoadRouteTimetablePort.ServiceCalendar(
				"weekday-2026",
				true,
				true,
				true,
				true,
				true,
				false,
				false,
				LocalDate.parse("2026-07-01"),
				LocalDate.parse("2026-07-01"),
				"Asia/Seoul"
			)),
			List.of(),
			List.of(new LoadRouteTimetablePort.TransitRoute(
				"route-seoul-4",
				"seoul-4",
				"4",
				"수도권 4호선",
				"사당 방면",
				"Asia/Seoul"
			)),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"trip-seoul-4-2350",
					"route-seoul-4",
					"weekday-2026",
					"사당",
					"0",
					"LOCAL",
					0
				),
				new LoadRouteTimetablePort.TransitTrip(
					"trip-seoul-4-2410",
					"route-seoul-4",
					"weekday-2026",
					"사당",
					"0",
					"LOCAL",
					0
				)
			),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-2350", 1, "station-a", "seoul-4", 85800, 85800, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-2350", 2, "station-b", "seoul-4", 86700, 86700, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-2410", 1, "station-a", "seoul-4", 87000, 87000, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-2410", 2, "station-b", "seoul-4", 87900, 87900, 0, 0)
			),
			List.of()
		);
	}

	private static LoadRouteTimetablePort shortTurnRouteTimetablePort() {
		return () -> new LoadRouteTimetablePort.RouteTimetable(
			List.of(new LoadRouteTimetablePort.ServiceCalendar(
				"weekday-2026",
				true,
				true,
				true,
				true,
				true,
				false,
				false,
				LocalDate.parse("2026-07-01"),
				LocalDate.parse("2026-12-31"),
				"Asia/Seoul"
			)),
			List.of(),
			List.of(new LoadRouteTimetablePort.TransitRoute(
				"route-short-turn",
				"line-short",
				"S",
				"테스트 단축운행",
				"연장운행",
				"Asia/Seoul"
			)),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"short-terminal-0908",
					"route-short-turn",
					"weekday-2026",
					"단축종착",
					"0",
					"LOCAL",
					0
				),
				new LoadRouteTimetablePort.TransitTrip(
					"fullrun-0912",
					"route-short-turn",
					"weekday-2026",
					"연장운행",
					"0",
					"LOCAL",
					0
				)
			),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime("short-terminal-0908", 1, "station-a", "line-short", 32880, 32880, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("short-terminal-0908", 2, "station-terminal", "line-short", 33720, 33720, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("fullrun-0912", 1, "station-a", "line-short", 33120, 33120, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("fullrun-0912", 2, "station-terminal", "line-short", 33960, 33960, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("fullrun-0912", 3, "station-c", "line-short", 34140, 34140, 0, 0)
			),
			List.of()
		);
	}

	private static LoadRouteTimetablePort entrySlackRouteTimetablePort() {
		return () -> new LoadRouteTimetablePort.RouteTimetable(
			List.of(new LoadRouteTimetablePort.ServiceCalendar(
				"weekday-2026",
				true,
				true,
				true,
				true,
				true,
				false,
				false,
				LocalDate.parse("2026-07-01"),
				LocalDate.parse("2026-12-31"),
				"Asia/Seoul"
			)),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitRoute(
					"route-express",
					"line-express",
					"X",
					"테스트 급행",
					"도착 방면",
					"Asia/Seoul"
				),
				new LoadRouteTimetablePort.TransitRoute(
					"route-local",
					"line-local",
					"L",
					"테스트 완행",
					"도착 방면",
					"Asia/Seoul"
				)
			),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"express-0904",
					"route-express",
					"weekday-2026",
					"도착",
					"0",
					"EXPRESS",
					0
				),
				new LoadRouteTimetablePort.TransitTrip(
					"local-0905",
					"route-local",
					"weekday-2026",
					"도착",
					"0",
					"LOCAL",
					0
				)
			),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime("express-0904", 1, "station-a", "line-express", 32640, 32640, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("express-0904", 2, "station-b", "line-express", 33180, 33180, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("local-0905", 1, "station-a", "line-local", 32760, 32760, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("local-0905", 2, "station-b", "line-local", 33960, 33960, 0, 0)
			),
			List.of()
		);
	}

	private static LoadRouteTimetablePort expressSkipRouteTimetablePort() {
		return () -> new LoadRouteTimetablePort.RouteTimetable(
			List.of(new LoadRouteTimetablePort.ServiceCalendar(
				"weekday-2026", true, true, true, true, true, false, false,
				LocalDate.parse("2026-07-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"
			)),
			List.of(),
			List.of(new LoadRouteTimetablePort.TransitRoute(
				"route-line", "line-test", "T", "테스트 노선", "도착 방면", "Asia/Seoul"
			)),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"express-0904", "route-line", "weekday-2026", "도착", "0", "EXPRESS", 0
				),
				new LoadRouteTimetablePort.TransitTrip(
					"local-0906", "route-line", "weekday-2026", "도착", "0", "LOCAL", 0
				)
			),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime("express-0904", 1, "station-a", "line-test", 32640, 32640, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("express-0904", 2, "station-c", "line-test", 33240, 33240, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("local-0906", 1, "station-a", "line-test", 32760, 32760, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("local-0906", 2, "station-b", "line-test", 33060, 33060, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("local-0906", 3, "station-c", "line-test", 33660, 33660, 0, 0)
			),
			List.of()
		);
	}

	private static LoadRouteTimetablePort removedCalendarDateRouteTimetablePort() {
		return () -> {
			var timetable = routeTimetable(
				List.of(
					new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-removed", 1, "station-a", "seoul-4", 32760, 32760, 0, 0),
					new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-removed", 2, "station-b", "seoul-4", 33360, 33360, 0, 0)
				),
				List.of()
			);
			return new LoadRouteTimetablePort.RouteTimetable(
				timetable.serviceCalendars(),
				List.of(new LoadRouteTimetablePort.ServiceCalendarDate("weekday-2026", LocalDate.parse("2026-07-01"), 2)),
				timetable.transitRoutes(),
				timetable.transitTrips(),
				timetable.transitStopTimes(),
				timetable.transitFrequencies()
			);
		};
	}

	private static LoadRouteTimetablePort addedCalendarDateRouteTimetablePort(LocalDate serviceDate) {
		return () -> new LoadRouteTimetablePort.RouteTimetable(
			List.of(),
			List.of(new LoadRouteTimetablePort.ServiceCalendarDate("special-2026", serviceDate, 1)),
			List.of(new LoadRouteTimetablePort.TransitRoute(
				"route-seoul-4-special",
				"seoul-4",
				"4",
				"수도권 4호선 임시",
				"사당 방면",
				"Asia/Seoul"
			)),
			List.of(new LoadRouteTimetablePort.TransitTrip(
				"trip-seoul-4-special-0907",
				"route-seoul-4-special",
				"special-2026",
				"사당",
				"0",
				"LOCAL",
				0
			)),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-special-0907", 1, "station-a", "seoul-4", 32820, 32820, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-special-0907", 2, "station-b", "seoul-4", 33420, 33420, 0, 0)
			),
			List.of()
		);
	}

	private static LoadRouteTimetablePort restrictedStopRouteTimetablePort(int pickupType, int dropOffType) {
		return () -> routeTimetable(
			List.of(
				new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-0900", 1, "station-a", "seoul-4", 32760, 32760, pickupType, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-0900", 2, "station-b", "seoul-4", 33360, 33360, 0, dropOffType)
			),
			List.of()
		);
	}

	private static class CountingRouteTimetablePort implements LoadRouteTimetablePort {
		private int loadCount;
		private String cacheKey = "a".repeat(64) + "2999-01-01T00:00:00Z";

		@Override
		public boolean hasRouteTimetable() {
			return true;
		}

		@Override
		public LoadRouteTimetablePort.RouteTimetable loadRouteTimetable() {
			loadCount += 1;
			return routeTimetablePort().loadRouteTimetable();
		}

		@Override
		public String timetableCacheKey() {
			return cacheKey;
		}

		void replaceSnapshotAtSameFreshness() {
			cacheKey = "b".repeat(64) + "2999-01-01T00:00:00Z";
		}

		int loadCount() {
			return loadCount;
		}
	}

	private static class AbaSwitchingRouteTimetablePort implements LoadRouteTimetablePort {
		private int atomicLoadCount;
		private int legacyLoadCount;

		@Override
		public boolean hasRouteTimetable() {
			return true;
		}

		@Override
		public RouteTimetableSnapshot loadRouteTimetableSnapshot() {
			atomicLoadCount += 1;
			return new RouteTimetableSnapshot(
				"b".repeat(64) + "2999-01-01T00:00:00Z",
				"snapshot-b",
				routeTimetablePort().loadRouteTimetable()
			);
		}

		@Override
		public LoadRouteTimetablePort.RouteTimetable loadRouteTimetable() {
			legacyLoadCount += 1;
			return routeTimetablePort().loadRouteTimetable();
		}

		@Override
		public String timetableCacheKey() {
			return "a".repeat(64) + "2999-01-01T00:00:00Z";
		}

		@Override
		public Optional<String> activeItxTimetableArtifactId() {
			return Optional.of("snapshot-a");
		}

		int atomicLoadCount() {
			return atomicLoadCount;
		}

		int legacyLoadCount() {
			return legacyLoadCount;
		}
	}

	private static class ConcurrentSwitchingRouteTimetablePort implements LoadRouteTimetablePort {

		private volatile RouteTimetableSnapshot active = snapshot("a");
		private final CountDownLatch oldLoadStarted = new CountDownLatch(1);
		private final CountDownLatch releaseOldLoad = new CountDownLatch(1);
		private final AtomicInteger oldLoadBlocksRemaining = new AtomicInteger(1);

		@Override
		public boolean hasRouteTimetable() {
			return true;
		}

		@Override
		public RouteTimetable loadRouteTimetable() {
			return active.timetable();
		}

		@Override
		public RouteTimetableSnapshot loadRouteTimetableSnapshot() {
			RouteTimetableSnapshot loaded = active;
			if ("artifact-a".equals(loaded.timetableArtifactId())
				&& oldLoadBlocksRemaining.compareAndSet(1, 0)) {
				oldLoadStarted.countDown();
				try {
					if (!releaseOldLoad.await(5, TimeUnit.SECONDS)) {
						throw new AssertionError("old timetable load release timed out");
					}
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new AssertionError("old timetable load interrupted", exception);
				}
			}
			return loaded;
		}

		@Override
		public String timetableCacheKey() {
			return active.cacheKey();
		}

		void use(String version) {
			active = snapshot(version);
		}

		boolean awaitOldLoadStarted() throws InterruptedException {
			return oldLoadStarted.await(5, TimeUnit.SECONDS);
		}

		void releaseOldLoad() {
			releaseOldLoad.countDown();
		}

		private static RouteTimetableSnapshot snapshot(String version) {
			String tripId = "trip-" + version;
			return new RouteTimetableSnapshot(
				"cache-" + version,
				"artifact-" + version,
				routeTimetable(
					List.of(
						new LoadRouteTimetablePort.TransitStopTime(
							tripId, 1, "station-a", "seoul-4", 32820, 32820, 0, 0),
						new LoadRouteTimetablePort.TransitStopTime(
							tripId, 2, "station-b", "seoul-4", 33420, 33420, 0, 0)
					),
					List.of()
				)
			);
		}
	}

	private static LoadRouteTimetablePort transferRouteTimetablePort() {
		return transferRouteTimetablePort(33780);
	}

	private static LoadRouteTimetablePort preScanTransferRouteTimetablePort() {
		return () -> new LoadRouteTimetablePort.RouteTimetable(
			List.of(new LoadRouteTimetablePort.ServiceCalendar(
				"weekday-2026", true, true, true, true, true, false, false,
				LocalDate.parse("2026-07-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul")),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitRoute(
					"route-line-a", "line-a", "A", "A 노선", "환승 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute(
					"route-line-b", "line-b", "B", "B 노선", "도착 방면", "Asia/Seoul")),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"trip-line-a", "route-line-a", "weekday-2026", "환승", "0",
					"SUBWAY", "LOCAL", "train-a", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"trip-line-b", "route-line-b", "weekday-2026", "도착", "0",
					"SUBWAY", "LOCAL", "train-b", 0)),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-line-a", 1, "station-a", "line-a", 32_940, 32_940, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-line-a", 2, "station-transfer", "line-a", 33_300, 33_300, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-line-b", 1, "station-transfer", "line-b", 34_200, 34_200, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-line-b", 2, "station-b", "line-b", 34_620, 34_620, 0, 0)),
			List.of());
	}

	private static LoadRouteTimetablePort objectiveRouteTimetablePort() {
		return () -> new LoadRouteTimetablePort.RouteTimetable(
			List.of(new LoadRouteTimetablePort.ServiceCalendar(
				"weekday-2026", true, true, true, true, true, false, false,
				LocalDate.parse("2026-07-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul"
			)),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitRoute("route-direct", "line-direct", "D", "직통", "도착 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute("route-a", "line-a", "A", "A", "환승 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute("route-b", "line-b", "B", "B", "도착 방면", "Asia/Seoul")
			),
			List.of(
				new LoadRouteTimetablePort.TransitTrip("trip-direct", "route-direct", "weekday-2026", "도착", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip("trip-a", "route-a", "weekday-2026", "환승", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"trip-b", "route-b", "weekday-2026", "도착", "0",
					"ITX_CHEONGCHUN", "EXPRESS", "2001", 0
				)
			),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime("trip-direct", 1, "station-a", "line-direct", 32820, 32820, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-direct", 2, "station-b", "line-direct", 34200, 34200, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-a", 1, "station-a", "line-a", 32820, 32820, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-a", 2, "station-transfer", "line-a", 33000, 33000, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-b", 1, "station-transfer", "line-b", 33600, 33600, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-b", 2, "station-b", "line-b", 33900, 33900, 0, 0)
			),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.OfficialFare("trip-direct", "station-a", "station-b", 2_000, "KRW", "official", "snapshot"),
				new LoadRouteTimetablePort.OfficialFare("trip-a", "station-a", "station-transfer", 1_000, "KRW", "official", "snapshot"),
				new LoadRouteTimetablePort.OfficialFare("trip-b", "station-transfer", "station-b", 1_500, "KRW", "official", "snapshot")
			),
			null
		);
	}

	private static LoadRouteTimetablePort objectiveOverflowRouteTimetablePort() {
		List<LoadRouteTimetablePort.TransitRoute> routes = new ArrayList<>();
		List<LoadRouteTimetablePort.TransitTrip> trips = new ArrayList<>();
		List<LoadRouteTimetablePort.TransitStopTime> stopTimes = new ArrayList<>();
		List<LoadRouteTimetablePort.OfficialFare> fares = new ArrayList<>();
		int[] arrivals = {40_000, 39_000, 38_000, 37_000};
		for (int transfers = 0; transfers <= 3; transfers += 1) {
			int legs = transfers + 1;
			int departure = 33_000;
			for (int leg = 0; leg < legs; leg += 1) {
				String id = transfers + "-" + leg;
				String routeId = "route-" + id;
				String tripId = "trip-" + id;
				String from = leg == 0 ? "station-a" : "station-" + transfers + "-" + leg;
				String to = leg == legs - 1 ? "station-b" : "station-" + transfers + "-" + (leg + 1);
				int arrival = leg == legs - 1 ? arrivals[transfers] : departure + 60;
				routes.add(new LoadRouteTimetablePort.TransitRoute(
					routeId, "line-" + id, id, id, "도착 방면", "Asia/Seoul"));
				trips.add(new LoadRouteTimetablePort.TransitTrip(
					tripId, routeId, "weekday-2026", "도착", "0", "SUBWAY", "LOCAL", null, 0));
				stopTimes.add(new LoadRouteTimetablePort.TransitStopTime(
					tripId, 1, from, "line-" + id, departure, departure, 0, 0));
				stopTimes.add(new LoadRouteTimetablePort.TransitStopTime(
					tripId, 2, to, "line-" + id, arrival, arrival, 0, 0));
				fares.add(new LoadRouteTimetablePort.OfficialFare(
					tripId, from, to, 1_000, "KRW", "official", "snapshot"));
				departure = arrival + 600;
			}
		}
		return () -> new LoadRouteTimetablePort.RouteTimetable(
			List.of(new LoadRouteTimetablePort.ServiceCalendar(
				"weekday-2026", true, true, true, true, true, false, false,
				LocalDate.parse("2026-07-01"), LocalDate.parse("2026-12-31"), "Asia/Seoul")),
			List.of(), routes, trips, stopTimes, List.of(), fares, null
		);
	}

	private static LoadRouteTimetablePort overtakenNextServiceRouteTimetablePort() {
		return () -> new LoadRouteTimetablePort.RouteTimetable(
			List.of(new LoadRouteTimetablePort.ServiceCalendar(
				"weekday-2026",
				true,
				true,
				true,
				true,
				true,
				false,
				false,
				LocalDate.parse("2026-07-01"),
				LocalDate.parse("2026-12-31"),
				"Asia/Seoul"
			)),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitRoute("route-local", "line-local", "L", "완행", "도착 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute("route-express", "line-express", "X", "급행", "도착 방면", "Asia/Seoul")
			),
			List.of(
				new LoadRouteTimetablePort.TransitTrip("local-0907", "route-local", "weekday-2026", "도착", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip("express-0909", "route-express", "weekday-2026", "도착", "0", "EXPRESS", 0)
			),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime("local-0907", 1, "station-a", "line-local", 32820, 32820, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("local-0907", 2, "station-b", "line-local", 34200, 34200, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("express-0909", 1, "station-a", "line-express", 32940, 32940, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("express-0909", 2, "station-b", "line-express", 33600, 33600, 0, 0)
			),
			List.of()
		);
	}

	private static LoadRouteTimetablePort earlyMorningNextServiceRouteTimetablePort() {
		return () -> new LoadRouteTimetablePort.RouteTimetable(
			List.of(new LoadRouteTimetablePort.ServiceCalendar(
				"weekday-2026",
				true,
				true,
				true,
				true,
				true,
				false,
				false,
				LocalDate.parse("2026-07-01"),
				LocalDate.parse("2026-12-31"),
				"Asia/Seoul"
			)),
			List.of(),
			List.of(new LoadRouteTimetablePort.TransitRoute(
				"route-seoul-4",
				"seoul-4",
				"4",
				"수도권 4호선",
				"사당 방면",
				"Asia/Seoul"
			)),
			List.of(
				new LoadRouteTimetablePort.TransitTrip("trip-seoul-4-0010", "route-seoul-4", "weekday-2026", "사당", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip("trip-seoul-4-0907", "route-seoul-4", "weekday-2026", "사당", "0", "LOCAL", 0)
			),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-0010", 1, "station-a", "seoul-4", 600, 600, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-0010", 2, "station-b", "seoul-4", 1500, 1500, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-0907", 1, "station-a", "seoul-4", 32820, 32820, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-0907", 2, "station-b", "seoul-4", 33720, 33720, 0, 0)
			),
			List.of()
		);
	}

	private static LoadRouteTimetablePort futureMidnightNextServiceRouteTimetablePort() {
		return () -> new LoadRouteTimetablePort.RouteTimetable(
			List.of(new LoadRouteTimetablePort.ServiceCalendar(
				"weekday-2026",
				true,
				true,
				true,
				true,
				true,
				false,
				false,
				LocalDate.parse("2026-07-01"),
				LocalDate.parse("2026-12-31"),
				"Asia/Seoul"
			)),
			List.of(),
			List.of(new LoadRouteTimetablePort.TransitRoute(
				"route-seoul-4",
				"seoul-4",
				"4",
				"수도권 4호선",
				"사당 방면",
				"Asia/Seoul"
			)),
			List.of(
				new LoadRouteTimetablePort.TransitTrip("trip-seoul-4-0003", "route-seoul-4", "weekday-2026", "사당", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip("trip-seoul-4-0907", "route-seoul-4", "weekday-2026", "사당", "0", "LOCAL", 0)
			),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-0003", 1, "station-a", "seoul-4", 180, 180, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-0003", 2, "station-b", "seoul-4", 1080, 1080, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-0907", 1, "station-a", "seoul-4", 32820, 32820, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-seoul-4-0907", 2, "station-b", "seoul-4", 33720, 33720, 0, 0)
			),
			List.of()
		);
	}

	private static LoadRouteTimetablePort sameArrivalRouteTimetablePort() {
		return () -> new LoadRouteTimetablePort.RouteTimetable(
			List.of(new LoadRouteTimetablePort.ServiceCalendar(
				"weekday-2026",
				true,
				true,
				true,
				true,
				true,
				false,
				false,
				LocalDate.parse("2026-07-01"),
				LocalDate.parse("2026-12-31"),
				"Asia/Seoul"
			)),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitRoute("route-direct", "line-direct", "D", "직통 노선", "도착 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute("route-line-a", "line-a", "A", "A 노선", "환승 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute("route-line-b", "line-b", "B", "B 노선", "도착 방면", "Asia/Seoul")
			),
			List.of(
				new LoadRouteTimetablePort.TransitTrip("trip-direct", "route-direct", "weekday-2026", "도착", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip("trip-a", "route-line-a", "weekday-2026", "환승", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip("trip-b", "route-line-b", "weekday-2026", "도착", "0", "LOCAL", 0)
			),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime("trip-direct", 1, "station-a", "line-direct", 32820, 32820, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-direct", 2, "station-b", "line-direct", 34200, 34200, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-a", 1, "station-a", "line-a", 32820, 32820, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-a", 2, "station-transfer", "line-a", 33180, 33180, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-b", 1, "station-transfer", "line-b", 33780, 33780, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-b", 2, "station-b", "line-b", 34200, 34200, 0, 0)
			),
			List.of()
		);
	}

	private static LoadRouteTimetablePort transferRouteTimetablePort(int secondDepartureSeconds) {
		return () -> new LoadRouteTimetablePort.RouteTimetable(
			List.of(new LoadRouteTimetablePort.ServiceCalendar(
				"weekday-2026",
				true,
				true,
				true,
				true,
				true,
				false,
				false,
				LocalDate.parse("2026-07-01"),
				LocalDate.parse("2026-12-31"),
				"Asia/Seoul"
			)),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitRoute(
					"route-line-a",
					"line-a",
					"A",
					"A 노선",
					"환승 방면",
					"Asia/Seoul"
				),
				new LoadRouteTimetablePort.TransitRoute(
					"route-line-b",
					"line-b",
					"B",
					"B 노선",
					"도착 방면",
					"Asia/Seoul"
				)
			),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"trip-line-a-0900",
					"route-line-a",
					"weekday-2026",
					"환승",
					"0",
					"LOCAL",
					0
				),
				new LoadRouteTimetablePort.TransitTrip(
					"trip-line-b-0915",
					"route-line-b",
					"weekday-2026",
					"도착",
					"0",
					"LOCAL",
					0
				)
			),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime("trip-line-a-0900", 1, "station-a", "line-a", 32820, 32820, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-line-a-0900", 2, "station-transfer", "line-a", 33180, 33180, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-line-b-0915", 1, "station-transfer", "line-b", secondDepartureSeconds, secondDepartureSeconds, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-line-b-0915", 2, "station-b", "line-b", secondDepartureSeconds + 420, secondDepartureSeconds + 420, 0, 0)
			),
			List.of()
		);
	}

	private static LoadRouteTimetablePort.RouteTimetable routeTimetable(
		List<LoadRouteTimetablePort.TransitStopTime> stopTimes,
		List<LoadRouteTimetablePort.TransitFrequency> frequencies
	) {
		return new LoadRouteTimetablePort.RouteTimetable(
			List.of(new LoadRouteTimetablePort.ServiceCalendar(
				"weekday-2026",
				true,
				true,
				true,
				true,
				true,
				false,
				false,
				LocalDate.parse("2026-07-01"),
				LocalDate.parse("2026-12-31"),
				"Asia/Seoul"
			)),
			List.of(),
			List.of(new LoadRouteTimetablePort.TransitRoute(
				"route-seoul-4",
				"seoul-4",
				"4",
				"수도권 4호선",
				"사당 방면",
				"Asia/Seoul"
			)),
			List.of(new LoadRouteTimetablePort.TransitTrip(
				stopTimes.getFirst().tripId(),
				"route-seoul-4",
				"weekday-2026",
				"사당",
				"0",
				"LOCAL",
				0
			)),
			stopTimes,
			frequencies
		);
	}

	private static RouteV2SearchUseCase.SearchRouteV2Command routeV2Command(
		ConstraintMode constraintMode,
		MobilityType mobilityType,
		int maxTransfers,
		int alternativeCount
	) {
		return routeV2Command(constraintMode, mobilityType, null, maxTransfers, alternativeCount);
	}

	private static RouteV2SearchUseCase.SearchRouteV2Command routeV2Command(
		ConstraintMode constraintMode,
		MobilityType mobilityType,
		MobilityPreset mobilityPreset,
		int maxTransfers,
		int alternativeCount
	) {
		return new RouteV2SearchUseCase.SearchRouteV2Command(
			"station-a",
			"station-b",
			OffsetDateTime.parse("2026-07-01T09:00:00+09:00"),
			mobilityType,
			mobilityPreset,
			constraintMode,
			false,
			maxTransfers,
			alternativeCount
		);
	}

	private static String firstStepDescription(MobilityType mobilityType) {
		var repository = new InMemoryRouteSearchRepository();
		var routeSearchService = new RouteSearchService(
			repository,
			repository,
			new RampAccessibleTransitMasterPort(),
			CLOCK
		);
		return routeSearchService.searchRoute(new SearchRouteCommand("station-a", "station-b", mobilityType))
			.steps()
			.getFirst()
			.description();
	}

	private static class CountingRealtimeArrivalResolver implements RealtimeArrivalResolver {

		private final AtomicInteger callCount = new AtomicInteger();
		private final List<Query> queries = new ArrayList<>();
		private final List<ArrivalFreshness> statuses;
		private Query lastQuery;

		CountingRealtimeArrivalResolver(ArrivalFreshness... statuses) {
			this.statuses = statuses.length == 0
				? List.of(ArrivalFreshness.FRESH_REALTIME)
				: List.of(statuses);
		}

		@Override
		public Resolution resolve(Query query) {
			int callIndex = callCount.getAndIncrement();
			queries.add(query);
			lastQuery = query;
			ArrivalFreshness status = statuses.get(Math.min(callIndex, statuses.size() - 1));
			Instant expectedArrivalAt = query.readyAt().plusSeconds(120);
			List<ArrivalCandidate> candidates = status == ArrivalFreshness.FRESH_REALTIME
				? List.of(new ArrivalCandidate(
					"train-test",
					query.lineId(),
					query.direction(),
					"도착역",
					120,
					expectedArrivalAt,
					query.readyAt().minusSeconds(30),
					ArrivalFreshness.FRESH_REALTIME,
					EtaConfidence.HIGH
				))
				: List.of();
			return new Resolution(
				status,
				status == ArrivalFreshness.FRESH_REALTIME ? null : "PROVIDER_UNAVAILABLE",
				status == ArrivalFreshness.FRESH_REALTIME ? "test-realtime-snapshot" : null,
				query.readyAt().minusSeconds(30),
				candidates
			);
		}

		int callCount() {
			return callCount.get();
		}

		Query lastQuery() {
			return lastQuery;
		}

		List<Query> queries() {
			return List.copyOf(queries);
		}
	}

	private static class StairOnlyTransitMasterPort implements LoadTransitMasterPort {

		@Override
		public List<TransitOperator> loadOperators() {
			return List.of(operator());
		}

		@Override
		public List<SubwayLine> loadLines() {
			return List.of(line("line-a"));
		}

		@Override
		public List<Station> loadStations() {
			return List.of(station("station-a", "출발역"), station("station-b", "도착역"));
		}

		@Override
		public List<StationLine> loadStationLines() {
			return List.of(
				new StationLine("station-a", "line-a", "101", 1, "상행 / 하행"),
				new StationLine("station-b", "line-a", "102", 2, "상행 / 하행")
			);
		}

		@Override
		public List<StationExit> loadStationExits() {
			return List.of(
				new StationExit("exit-a-1", "station-a", "1", "1번 출구", BigDecimal.ONE, BigDecimal.ONE, false, true, DataConfidenceLevel.HIGH, DataSourceType.OFFICIAL_FILE),
				new StationExit("exit-b-1", "station-b", "1", "1번 출구", BigDecimal.ONE, BigDecimal.ONE, false, true, DataConfidenceLevel.HIGH, DataSourceType.OFFICIAL_FILE)
			);
		}

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of();
		}

		@Override
		public List<RouteEdge> loadRouteEdges() {
			return List.of();
		}
	}

	// #2292 2라운드 리뷰: 이 PR을 유발한 실제 시나리오 — ITX pilot 역처럼 출구 데이터 자체가
	// 없는 역(StairOnlyTransitMasterPort처럼 "출구는 있지만 계단뿐"이 아니라 출구 레코드가
	// 아예 없음). hasStairOnlyAccess()는 exits.isEmpty()면 근거 없이 stairs-only로 단정하지
	// 않고 false를 반환하며, hasLowAccessibilityData()는 exits.isEmpty()면 true를 반환한다.
	private static class NoExitDataTransitMasterPort extends StairOnlyTransitMasterPort {

		@Override
		public List<StationExit> loadStationExits() {
			return List.of();
		}
	}

	private static class DisconnectedTransitMasterPort extends StairOnlyTransitMasterPort {

		@Override
		public List<SubwayLine> loadLines() {
			return List.of(line("line-a"), line("line-b"));
		}

		@Override
		public List<StationLine> loadStationLines() {
			return List.of(
				new StationLine("station-a", "line-a", "101", 1, "상행 / 하행"),
				new StationLine("station-b", "line-b", "202", 2, "상행 / 하행")
			);
		}
	}

	private static class OneTransferTransitMasterPort extends StairOnlyTransitMasterPort {

		@Override
		public List<SubwayLine> loadLines() {
			return List.of(
				new SubwayLine("line-a", "operator-a", "A 노선", "#0052A4", "수도권", null, true),
				new SubwayLine("line-b", "operator-a", "B 노선", "#00A84D", "수도권", null, true)
			);
		}

		@Override
		public List<Station> loadStations() {
			return List.of(
				station("station-a", "출발역"),
				station("station-transfer", "환승역"),
				station("station-b", "도착역")
			);
		}

		@Override
		public List<StationLine> loadStationLines() {
			return List.of(
				new StationLine("station-a", "line-a", "101", 1, "상행 / 하행"),
				new StationLine("station-transfer", "line-a", "103", 3, "상행 / 하행"),
				new StationLine("station-transfer", "line-b", "201", 1, "상행 / 하행"),
				new StationLine("station-b", "line-b", "203", 3, "상행 / 하행")
			);
		}

		@Override
		public List<StationExit> loadStationExits() {
			return List.of(
				stepFreeExit("exit-a-1", "station-a"),
				stepFreeExit("exit-transfer-1", "station-transfer"),
				stepFreeExit("exit-b-1", "station-b")
			);
		}

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of(
				facility("facility-a-elevator", "station-a", "exit-a-1", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL),
				facility("facility-transfer-elevator", "station-transfer", "exit-transfer-1", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL),
				facility("facility-b-elevator", "station-b", "exit-b-1", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL)
			);
		}
	}

	private static class TwoTransferTransitMasterPort extends StairOnlyTransitMasterPort {

		@Override
		public List<SubwayLine> loadLines() {
			return List.of(
				new SubwayLine("line-a", "operator-a", "A 노선", "#0052A4", "수도권", null, true),
				new SubwayLine("line-b", "operator-a", "B 노선", "#00A84D", "수도권", null, true),
				new SubwayLine("line-c", "operator-a", "C 노선", "#F5A200", "수도권", null, true)
			);
		}

		@Override
		public List<Station> loadStations() {
			return List.of(
				station("station-a", "출발역"),
				station("station-transfer-1", "첫환승역"),
				station("station-transfer-2", "둘째환승역"),
				station("station-b", "도착역")
			);
		}

		@Override
		public List<StationLine> loadStationLines() {
			return List.of(
				new StationLine("station-a", "line-a", "101", 1, "상행 / 하행"),
				new StationLine("station-transfer-1", "line-a", "102", 2, "상행 / 하행"),
				new StationLine("station-transfer-1", "line-b", "201", 1, "상행 / 하행"),
				new StationLine("station-transfer-2", "line-b", "202", 2, "상행 / 하행"),
				new StationLine("station-transfer-2", "line-c", "301", 1, "상행 / 하행"),
				new StationLine("station-b", "line-c", "302", 2, "상행 / 하행")
			);
		}

		@Override
		public List<StationExit> loadStationExits() {
			return List.of(
				stepFreeExit("exit-a-1", "station-a"),
				stepFreeExit("exit-transfer-1", "station-transfer-1"),
				stepFreeExit("exit-transfer-2", "station-transfer-2"),
				stepFreeExit("exit-b-1", "station-b")
			);
		}

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of(
				facility("facility-a-elevator", "station-a", "exit-a-1", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL),
				facility("facility-transfer-1-elevator", "station-transfer-1", "exit-transfer-1", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL),
				facility("facility-transfer-2-elevator", "station-transfer-2", "exit-transfer-2", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL),
				facility("facility-b-elevator", "station-b", "exit-b-1", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL)
			);
		}
	}

	private static class ThreeTransferTransitMasterPort extends TwoTransferTransitMasterPort {

		@Override
		public List<SubwayLine> loadLines() {
			return List.of(
				new SubwayLine("line-a", "operator-a", "A 노선", "#0052A4", "수도권", null, true),
				new SubwayLine("line-b", "operator-a", "B 노선", "#00A84D", "수도권", null, true),
				new SubwayLine("line-c", "operator-a", "C 노선", "#F5A200", "수도권", null, true),
				new SubwayLine("line-d", "operator-a", "D 노선", "#8A2BE2", "수도권", null, true)
			);
		}

		@Override
		public List<Station> loadStations() {
			return List.of(
				station("station-a", "출발역"),
				station("station-transfer-1", "첫환승역"),
				station("station-transfer-2", "둘째환승역"),
				station("station-transfer-3", "셋째환승역"),
				station("station-b", "도착역")
			);
		}

		@Override
		public List<StationLine> loadStationLines() {
			return List.of(
				new StationLine("station-a", "line-a", "101", 1, "상행 / 하행"),
				new StationLine("station-transfer-1", "line-a", "102", 2, "상행 / 하행"),
				new StationLine("station-transfer-1", "line-b", "201", 1, "상행 / 하행"),
				new StationLine("station-transfer-2", "line-b", "202", 2, "상행 / 하행"),
				new StationLine("station-transfer-2", "line-c", "301", 1, "상행 / 하행"),
				new StationLine("station-transfer-3", "line-c", "302", 2, "상행 / 하행"),
				new StationLine("station-transfer-3", "line-d", "401", 1, "상행 / 하행"),
				new StationLine("station-b", "line-d", "402", 2, "상행 / 하행")
			);
		}

		@Override
		public List<StationExit> loadStationExits() {
			return List.of(
				stepFreeExit("exit-a-1", "station-a"),
				stepFreeExit("exit-transfer-1", "station-transfer-1"),
				stepFreeExit("exit-transfer-2", "station-transfer-2"),
				stepFreeExit("exit-transfer-3", "station-transfer-3"),
				stepFreeExit("exit-b-1", "station-b")
			);
		}

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of(
				facility("facility-a-elevator", "station-a", "exit-a-1", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL),
				facility("facility-transfer-1-elevator", "station-transfer-1", "exit-transfer-1", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL),
				facility("facility-transfer-2-elevator", "station-transfer-2", "exit-transfer-2", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL),
				facility("facility-transfer-3-elevator", "station-transfer-3", "exit-transfer-3", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL),
				facility("facility-b-elevator", "station-b", "exit-b-1", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL)
			);
		}
	}

	private static class StairOnlyOneTransferWithStepFreeTwoTransferTransitMasterPort extends TwoTransferTransitMasterPort {

		@Override
		public List<SubwayLine> loadLines() {
			return List.of(
				new SubwayLine("line-a", "operator-a", "A 노선", "#0052A4", "수도권", null, true),
				new SubwayLine("line-b", "operator-a", "B 노선", "#00A84D", "수도권", null, true),
				new SubwayLine("line-c", "operator-a", "C 노선", "#F5A200", "수도권", null, true),
				new SubwayLine("line-d", "operator-a", "D 노선", "#8A2BE2", "수도권", null, true)
			);
		}

		@Override
		public List<Station> loadStations() {
			return List.of(
				station("station-a", "출발역"),
				station("station-stair-transfer", "계단환승역"),
				station("station-transfer-1", "첫환승역"),
				station("station-transfer-2", "둘째환승역"),
				station("station-b", "도착역")
			);
		}

		@Override
		public List<StationLine> loadStationLines() {
			return List.of(
				new StationLine("station-a", "line-a", "101", 1, "상행 / 하행"),
				new StationLine("station-stair-transfer", "line-a", "102", 2, "상행 / 하행"),
				new StationLine("station-stair-transfer", "line-d", "401", 1, "상행 / 하행"),
				new StationLine("station-transfer-1", "line-a", "103", 3, "상행 / 하행"),
				new StationLine("station-transfer-1", "line-b", "201", 1, "상행 / 하행"),
				new StationLine("station-transfer-2", "line-b", "202", 2, "상행 / 하행"),
				new StationLine("station-transfer-2", "line-c", "301", 1, "상행 / 하행"),
				new StationLine("station-b", "line-c", "302", 2, "상행 / 하행"),
				new StationLine("station-b", "line-d", "402", 2, "상행 / 하행")
			);
		}

		@Override
		public List<StationExit> loadStationExits() {
			return List.of(
				stepFreeExit("exit-a-1", "station-a"),
				stairOnlyExit("exit-stair-transfer-1", "station-stair-transfer"),
				stepFreeExit("exit-transfer-1", "station-transfer-1"),
				stepFreeExit("exit-transfer-2", "station-transfer-2"),
				stepFreeExit("exit-b-1", "station-b")
			);
		}

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of(
				facility("facility-a-elevator", "station-a", "exit-a-1", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL),
				facility("facility-transfer-1-elevator", "station-transfer-1", "exit-transfer-1", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL),
				facility("facility-transfer-2-elevator", "station-transfer-2", "exit-transfer-2", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL),
				facility("facility-b-elevator", "station-b", "exit-b-1", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL)
			);
		}
	}

	private static class MixedMultiTransferAccessibilityTransitMasterPort extends TwoTransferTransitMasterPort {

		@Override
		public List<SubwayLine> loadLines() {
			return List.of(
				new SubwayLine("line-a", "operator-a", "A 노선", "#0052A4", "수도권", null, true),
				new SubwayLine("line-stair-mid", "operator-a", "계단 중간 노선", "#00A84D", "수도권", null, true),
				new SubwayLine("line-stair-end", "operator-a", "계단 도착 노선", "#F5A200", "수도권", null, true),
				new SubwayLine("line-step-free-mid", "operator-a", "무단차 중간 노선", "#8A2BE2", "수도권", null, true),
				new SubwayLine("line-step-free-end", "operator-a", "무단차 도착 노선", "#00FFFF", "수도권", null, true)
			);
		}

		@Override
		public List<Station> loadStations() {
			return List.of(
				station("station-a", "출발역"),
				station("station-stair-transfer-1", "첫계단환승역"),
				station("station-stair-transfer-2", "둘째계단환승역"),
				station("station-step-free-transfer-1", "첫무단차환승역"),
				station("station-step-free-transfer-2", "둘째무단차환승역"),
				station("station-b", "도착역")
			);
		}

		@Override
		public List<StationLine> loadStationLines() {
			return List.of(
				new StationLine("station-a", "line-a", "101", 1, "상행 / 하행"),
				new StationLine("station-stair-transfer-1", "line-a", "102", 2, "상행 / 하행"),
				new StationLine("station-stair-transfer-1", "line-stair-mid", "201", 1, "상행 / 하행"),
				new StationLine("station-stair-transfer-2", "line-stair-mid", "202", 2, "상행 / 하행"),
				new StationLine("station-stair-transfer-2", "line-stair-end", "301", 1, "상행 / 하행"),
				new StationLine("station-b", "line-stair-end", "302", 2, "상행 / 하행"),
				new StationLine("station-step-free-transfer-1", "line-a", "110", 10, "상행 / 하행"),
				new StationLine("station-step-free-transfer-1", "line-step-free-mid", "401", 1, "상행 / 하행"),
				new StationLine("station-step-free-transfer-2", "line-step-free-mid", "402", 2, "상행 / 하행"),
				new StationLine("station-step-free-transfer-2", "line-step-free-end", "501", 1, "상행 / 하행"),
				new StationLine("station-b", "line-step-free-end", "502", 2, "상행 / 하행")
			);
		}

		@Override
		public List<StationExit> loadStationExits() {
			return List.of(
				stepFreeExit("exit-a-1", "station-a"),
				stairOnlyExit("exit-stair-transfer-1", "station-stair-transfer-1"),
				stairOnlyExit("exit-stair-transfer-2", "station-stair-transfer-2"),
				stepFreeExit("exit-step-free-transfer-1", "station-step-free-transfer-1"),
				stepFreeExit("exit-step-free-transfer-2", "station-step-free-transfer-2"),
				stepFreeExit("exit-b-1", "station-b")
			);
		}

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of(
				facility("facility-a-elevator", "station-a", "exit-a-1", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL),
				facility("facility-step-free-transfer-1-elevator", "station-step-free-transfer-1", "exit-step-free-transfer-1", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL),
				facility("facility-step-free-transfer-2-elevator", "station-step-free-transfer-2", "exit-step-free-transfer-2", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL),
				facility("facility-b-elevator", "station-b", "exit-b-1", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL)
			);
		}
	}

	private static class DirectComparableTransitMasterPort extends OneTransferTransitMasterPort {

		@Override
		public List<SubwayLine> loadLines() {
			return List.of(new SubwayLine("line-direct", "operator-a", "테스트 직통", "#0052A4", "수도권", null, true));
		}

		@Override
		public List<Station> loadStations() {
			return List.of(station("station-a", "출발역"), station("station-b", "도착역"));
		}

		@Override
		public List<StationLine> loadStationLines() {
			return List.of(
				new StationLine("station-a", "line-direct", "101", 1, "상행 / 하행"),
				new StationLine("station-b", "line-direct", "105", 5, "상행 / 하행")
			);
		}

		@Override
		public List<StationExit> loadStationExits() {
			return List.of(
				stepFreeExit("exit-a-1", "station-a"),
				stepFreeExit("exit-b-1", "station-b")
			);
		}

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of(
				facility("facility-a-elevator", "station-a", "exit-a-1", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL),
				facility("facility-b-elevator", "station-b", "exit-b-1", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL)
			);
		}
	}

	private static class DirectAndShorterTransferTransitMasterPort extends OneTransferTransitMasterPort {

		@Override
		public List<SubwayLine> loadLines() {
			return List.of(
				new SubwayLine("line-a", "operator-a", "A 노선", "#0052A4", "수도권", null, true),
				new SubwayLine("line-b", "operator-a", "B 노선", "#00A84D", "수도권", null, true),
				new SubwayLine("line-direct", "operator-a", "테스트 직통", "#F5A200", "수도권", null, true)
			);
		}

		@Override
		public List<StationLine> loadStationLines() {
			return List.of(
				new StationLine("station-a", "line-a", "101", 1, "상행 / 하행"),
				new StationLine("station-transfer", "line-a", "103", 3, "상행 / 하행"),
				new StationLine("station-transfer", "line-b", "201", 1, "상행 / 하행"),
				new StationLine("station-b", "line-b", "203", 3, "상행 / 하행"),
				new StationLine("station-a", "line-direct", "101", 1, "상행 / 하행"),
				new StationLine("station-b", "line-direct", "150", 50, "상행 / 하행")
			);
		}
	}

	private static class StairOnlyTransferTransitMasterPort extends OneTransferTransitMasterPort {

		@Override
		public List<StationExit> loadStationExits() {
			return List.of(
				stepFreeExit("exit-a-1", "station-a"),
				stairOnlyExit("exit-transfer-1", "station-transfer"),
				stepFreeExit("exit-b-1", "station-b")
			);
		}

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of(
				facility("facility-a-elevator", "station-a", "exit-a-1", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL),
				facility("facility-b-elevator", "station-b", "exit-b-1", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL)
			);
		}
	}

	private static class MixedTransferAccessibilityTransitMasterPort extends OneTransferTransitMasterPort {

		@Override
		public List<Station> loadStations() {
			return List.of(
				station("station-a", "출발역"),
				station("station-stair-transfer", "계단환승역"),
				station("station-step-free-transfer", "무단차환승역"),
				station("station-b", "도착역")
			);
		}

		@Override
		public List<StationLine> loadStationLines() {
			return List.of(
				new StationLine("station-a", "line-a", "101", 1, "상행 / 하행"),
				new StationLine("station-stair-transfer", "line-a", "102", 2, "상행 / 하행"),
				new StationLine("station-step-free-transfer", "line-a", "103", 3, "상행 / 하행"),
				new StationLine("station-stair-transfer", "line-b", "201", 1, "상행 / 하행"),
				new StationLine("station-step-free-transfer", "line-b", "202", 2, "상행 / 하행"),
				new StationLine("station-b", "line-b", "204", 4, "상행 / 하행")
			);
		}

		@Override
		public List<StationExit> loadStationExits() {
			return List.of(
				stepFreeExit("exit-a-1", "station-a"),
				stairOnlyExit("exit-stair-transfer-1", "station-stair-transfer"),
				stepFreeExit("exit-step-free-transfer-1", "station-step-free-transfer"),
				stepFreeExit("exit-b-1", "station-b")
			);
		}

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of(
				facility("facility-a-elevator", "station-a", "exit-a-1", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL),
				facility("facility-step-free-transfer-elevator", "station-step-free-transfer", "exit-step-free-transfer-1", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL),
				facility("facility-b-elevator", "station-b", "exit-b-1", AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL)
			);
		}
	}

	private static class LongDetourTransferAccessibilityTransitMasterPort extends MixedTransferAccessibilityTransitMasterPort {

		@Override
		public List<StationLine> loadStationLines() {
			return List.of(
				new StationLine("station-a", "line-a", "101", 1, "상행 / 하행"),
				new StationLine("station-stair-transfer", "line-a", "102", 2, "상행 / 하행"),
				new StationLine("station-stair-transfer", "line-b", "201", 1, "상행 / 하행"),
				new StationLine("station-step-free-transfer", "line-a", "6000", 6_000, "상행 / 하행"),
				new StationLine("station-step-free-transfer", "line-b", "7000", 7_000, "상행 / 하행"),
				new StationLine("station-b", "line-b", "13000", 13_000, "상행 / 하행")
			);
		}
	}

	private static class LowConfidenceStairOnlyTransitMasterPort extends StairOnlyTransitMasterPort {

		@Override
		public List<StationExit> loadStationExits() {
			return List.of(
				new StationExit("exit-a-1", "station-a", "1", "1번 출구", BigDecimal.ONE, BigDecimal.ONE, false, true, DataConfidenceLevel.LOW, DataSourceType.OFFICIAL_FILE),
				new StationExit("exit-b-1", "station-b", "1", "1번 출구", BigDecimal.ONE, BigDecimal.ONE, false, true, DataConfidenceLevel.LOW, DataSourceType.OFFICIAL_FILE)
			);
		}
	}

	private static class MissingLineCodeTransitMasterPort extends StairOnlyTransitMasterPort {

		@Override
		public List<SubwayLine> loadLines() {
			return List.of(new SubwayLine("line-a", "operator-a", "테스트 노선", "#0052A4", "수도권", null, true));
		}
	}

	private static class BlankLineCodeTransitMasterPort extends StairOnlyTransitMasterPort {

		@Override
		public List<SubwayLine> loadLines() {
			return List.of(new SubwayLine("line-a", "operator-a", "테스트 노선", "#0052A4", "수도권", "", true));
		}
	}

	private static class RampAccessibleTransitMasterPort extends StairOnlyTransitMasterPort {

		@Override
		public List<StationExit> loadStationExits() {
			return List.of(
				stairOnlyExit("exit-a-1", "station-a"),
				stepFreeExit("exit-a-2", "station-a"),
				stepFreeExit("exit-b-1", "station-b")
			);
		}

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of(
				facility(
					"facility-a-ramp",
					"station-a",
					"exit-a-2",
					AccessibilityFacilityType.RAMP,
					AccessibilityFacilityStatus.NORMAL
				),
				facility(
					"facility-b-elevator",
					"station-b",
					"exit-b-1",
					AccessibilityFacilityType.ELEVATOR,
					AccessibilityFacilityStatus.NORMAL
				)
			);
		}
	}

	private static class TimetableAlignedRampAccessibleTransitMasterPort extends RampAccessibleTransitMasterPort {

		@Override
		public List<SubwayLine> loadLines() {
			return List.of(line("seoul-4"));
		}

		@Override
		public List<StationLine> loadStationLines() {
			return List.of(
				new StationLine("station-a", "seoul-4", "101", 1, "상행 / 하행"),
				new StationLine("station-b", "seoul-4", "102", 2, "상행 / 하행")
			);
		}
	}

	private static class DualOriginRealtimeTransitMasterPort extends TimetableAlignedRampAccessibleTransitMasterPort {

		@Override
		public List<Station> loadStations() {
			return List.of(
				station("station-a", "출발역 A"),
				station("station-c", "출발역 C"),
				station("station-b", "도착역"));
		}

		@Override
		public List<StationLine> loadStationLines() {
			return List.of(
				new StationLine("station-a", "seoul-4", "101", 1, "상행 / 하행"),
				new StationLine("station-c", "seoul-4", "103", 3, "상행 / 하행"),
				new StationLine("station-b", "seoul-4", "102", 2, "상행 / 하행"));
		}

		@Override
		public List<StationExit> loadStationExits() {
			return List.of(
				stepFreeExit("exit-a-1", "station-a"),
				stepFreeExit("exit-c-1", "station-c"),
				stepFreeExit("exit-b-1", "station-b"));
		}

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of(
				facility("facility-a-elevator", "station-a", "exit-a-1",
					AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL),
				facility("facility-c-elevator", "station-c", "exit-c-1",
					AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL),
				facility("facility-b-elevator", "station-b", "exit-b-1",
					AccessibilityFacilityType.ELEVATOR, AccessibilityFacilityStatus.NORMAL));
		}
	}

	private static class ExitSummaryAccessibleTransitMasterPort extends StairOnlyTransitMasterPort {

		@Override
		public List<StationExit> loadStationExits() {
			return List.of(
				stairOnlyExit("exit-a-1", "station-a"),
				stepFreeExit("exit-a-2", "station-a"),
				stepFreeExit("exit-b-1", "station-b")
			);
		}
	}

	private static class UnlinkedStepFreeFacilityTransitMasterPort extends ExitSummaryAccessibleTransitMasterPort {

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of(
				facility(
					"facility-a-elevator",
					"station-a",
					"exit-a-missing",
					AccessibilityFacilityType.ELEVATOR,
					AccessibilityFacilityStatus.NORMAL
				),
				facility(
					"facility-b-elevator",
					"station-b",
					"exit-b-missing",
					AccessibilityFacilityType.ELEVATOR,
					AccessibilityFacilityStatus.NORMAL
				)
			);
		}
	}

	private static class LowConfidenceStepFreeFacilityTransitMasterPort extends ExitSummaryAccessibleTransitMasterPort {

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of(
				facility(
					"facility-a-elevator",
					"station-a",
					"exit-a-2",
					AccessibilityFacilityType.ELEVATOR,
					AccessibilityFacilityStatus.NORMAL,
					DataConfidenceLevel.MEDIUM
				),
				facility(
					"facility-b-elevator",
					"station-b",
					"exit-b-1",
					AccessibilityFacilityType.ELEVATOR,
					AccessibilityFacilityStatus.NORMAL,
					DataConfidenceLevel.MEDIUM
				)
			);
		}
	}

	private static class StaleAccessibilityFacilityTransitMasterPort extends ExitSummaryAccessibleTransitMasterPort {

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of(
				facility(
					"facility-a-elevator",
					"station-a",
					"exit-a-2",
					AccessibilityFacilityType.ELEVATOR,
					AccessibilityFacilityStatus.NORMAL,
					DataConfidenceLevel.HIGH,
					LocalDate.of(2026, 6, 13)
				),
				facility(
					"facility-b-elevator",
					"station-b",
					"exit-b-1",
					AccessibilityFacilityType.ELEVATOR,
					AccessibilityFacilityStatus.NORMAL,
					DataConfidenceLevel.HIGH,
					LocalDate.of(2026, 5, 1)
				)
			);
		}
	}

	private static class BrokenElevatorTransitMasterPort extends StairOnlyTransitMasterPort {

		@Override
		public List<StationExit> loadStationExits() {
			return List.of(
				stairOnlyExit("exit-a-1", "station-a"),
				stepFreeExit("exit-a-2", "station-a"),
				stepFreeExit("exit-b-1", "station-b")
			);
		}

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of(
				facility(
					"facility-a-elevator",
					"station-a",
					"exit-a-2",
					AccessibilityFacilityType.ELEVATOR,
					AccessibilityFacilityStatus.BROKEN
				),
				facility(
					"facility-b-elevator",
					"station-b",
					"exit-b-1",
					AccessibilityFacilityType.ELEVATOR,
					AccessibilityFacilityStatus.NORMAL
				)
			);
		}
	}

	private static class BrokenElevatorOnlyTransitMasterPort extends StairOnlyTransitMasterPort {

		@Override
		public List<StationExit> loadStationExits() {
			return List.of(
				stepFreeExit("exit-a-1", "station-a"),
				stepFreeExit("exit-b-1", "station-b")
			);
		}

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of(
				facility(
					"facility-a-elevator",
					"station-a",
					"exit-a-1",
					AccessibilityFacilityType.ELEVATOR,
					AccessibilityFacilityStatus.BROKEN
				),
				facility(
					"facility-b-elevator",
					"station-b",
					"exit-b-1",
					AccessibilityFacilityType.ELEVATOR,
					AccessibilityFacilityStatus.NORMAL
				)
			);
		}
	}

	private static class InternalStairEdgeTransitMasterPort extends ExitSummaryAccessibleTransitMasterPort {

		@Override
		public List<RouteEdge> loadRouteEdges() {
			return List.of(
				stairEdge("edge-a-stair", "station-a"),
				stairEdge("edge-b-stair", "station-b")
			);
		}

		@Override
		public List<RouteNode> loadRouteNodes() {
			return List.of(
				routeNode("node-station-a-entrance", "station-a", RouteNodeType.ENTRANCE, "출입구"),
				routeNode("node-station-a-platform", "station-a", RouteNodeType.PLATFORM, "승강장"),
				routeNode("node-station-b-entrance", "station-b", RouteNodeType.ENTRANCE, "출입구"),
				routeNode("node-station-b-platform", "station-b", RouteNodeType.PLATFORM, "승강장")
			);
		}
	}

	private static class EtaBurdenSplitInternalTransitMasterPort extends ExitSummaryAccessibleTransitMasterPort {

		@Override
		public List<RouteEdge> loadRouteEdges() {
			return List.of(
				internalEdge(
					"edge-fast-stair",
					"node-station-a-entrance",
					"node-station-a-platform",
					RouteEdgeType.STAIR,
					400,
					90,
					true
				),
				internalEdge(
					"edge-slower-step-free-a",
					"node-station-a-entrance",
					"node-station-a-landing",
					RouteEdgeType.WALKWAY,
					5,
					60,
					false
				),
				internalEdge(
					"edge-slower-step-free-b",
					"node-station-a-landing",
					"node-station-a-platform",
					RouteEdgeType.WALKWAY,
					5,
					60,
					false
				)
			);
		}

		@Override
		public List<RouteNode> loadRouteNodes() {
			return List.of(
				routeNode("node-station-a-entrance", "station-a", RouteNodeType.ENTRANCE, "출입구"),
				routeNode("node-station-a-landing", "station-a", RouteNodeType.CONCOURSE, "중간 지점"),
				routeNode("node-station-a-platform", "station-a", RouteNodeType.PLATFORM, "승강장")
			);
		}
	}

	private static class LaterShorterInternalTransitMasterPort extends ExitSummaryAccessibleTransitMasterPort {

		@Override
		public List<RouteEdge> loadRouteEdges() {
			return List.of(
				internalEdge(
					"edge-long-to-merge", "node-station-a-entrance", "node-station-a-merge",
					RouteEdgeType.WALKWAY, 100, 100, false),
				internalEdge(
					"edge-shortcut", "node-station-a-entrance", "node-station-a-shortcut",
					RouteEdgeType.WALKWAY, 10, 10, false),
				internalEdge(
					"edge-shortcut-to-merge", "node-station-a-shortcut", "node-station-a-merge",
					RouteEdgeType.WALKWAY, 10, 10, false),
				internalEdge(
					"edge-merge-to-platform", "node-station-a-merge", "node-station-a-platform",
					RouteEdgeType.WALKWAY, 10, 10, false)
			);
		}

		@Override
		public List<RouteNode> loadRouteNodes() {
			return List.of(
				routeNode("node-station-a-entrance", "station-a", RouteNodeType.ENTRANCE, "출입구"),
				routeNode("node-station-a-shortcut", "station-a", RouteNodeType.CONCOURSE, "지름길"),
				routeNode("node-station-a-merge", "station-a", RouteNodeType.CONCOURSE, "합류점"),
				routeNode("node-station-a-platform", "station-a", RouteNodeType.PLATFORM, "승강장")
			);
		}
	}

	private static class OutOfStationTransferOnlyTransitMasterPort extends ExitSummaryAccessibleTransitMasterPort {

		@Override
		public List<RouteEdge> loadRouteEdges() {
			return List.of(internalEdge(
				"edge-a-out-of-station-transfer",
				"node-station-a-entrance",
				"node-station-a-platform",
				RouteEdgeType.OUT_OF_STATION_TRANSFER,
				120,
				180,
				false
			));
		}

		@Override
		public List<RouteNode> loadRouteNodes() {
			return List.of(
				routeNode("node-station-a-entrance", "station-a", RouteNodeType.ENTRANCE, "외부 출입구"),
				routeNode("node-station-a-platform", "station-a", RouteNodeType.PLATFORM, "승강장")
			);
		}
	}

	private static class BrokenElevatorInternalEdgeTransitMasterPort extends ExitSummaryAccessibleTransitMasterPort {

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of(facility(
				"facility-a-elevator",
				"station-a",
				"exit-a-2",
				AccessibilityFacilityType.ELEVATOR,
				AccessibilityFacilityStatus.BROKEN
			));
		}

		@Override
		public List<RouteEdge> loadRouteEdges() {
			return List.of(elevatorEdge("edge-a-elevator", "station-a"));
		}
	}

	private static class BrokenElevatorWithOtherNormalFacilityInternalEdgeTransitMasterPort
		extends BrokenElevatorInternalEdgeTransitMasterPort {

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of(
				facility(
					"facility-a-elevator",
					"station-a",
					"exit-a-1",
					AccessibilityFacilityType.ELEVATOR,
					AccessibilityFacilityStatus.BROKEN
				),
				facility(
					"facility-a-other-elevator",
					"station-a",
					"exit-a-2",
					AccessibilityFacilityType.ELEVATOR,
					AccessibilityFacilityStatus.NORMAL
				)
			);
		}

		@Override
		public List<RouteNode> loadRouteNodes() {
			return List.of(
				routeNode(
					"node-station-a-entrance",
					"station-a",
					RouteNodeType.ENTRANCE,
					"출입구",
					"facility-a-elevator"
				),
				routeNode("node-station-a-platform", "station-a", RouteNodeType.PLATFORM, "승강장")
			);
		}
	}

	private static class MixedInternalAndTrainEdgeTransitMasterPort extends ExitSummaryAccessibleTransitMasterPort {

		@Override
		public List<RouteEdge> loadRouteEdges() {
			return List.of(
				stairEdge("edge-a-stair", "station-a"),
				trainEdge("edge-a-train", "station-a"),
				stairEdge("edge-b-stair", "station-b"),
				trainEdge("edge-b-train", "station-b")
			);
		}

		@Override
		public List<RouteNode> loadRouteNodes() {
			return List.of(
				routeNode("node-station-a-entrance", "station-a", RouteNodeType.ENTRANCE, "출입구"),
				routeNode("node-station-a-platform", "station-a", RouteNodeType.PLATFORM, "승강장"),
				routeNode("node-station-a-next-platform", "station-a", RouteNodeType.PLATFORM, "다음 승강장"),
				routeNode("node-station-b-entrance", "station-b", RouteNodeType.ENTRANCE, "출입구"),
				routeNode("node-station-b-platform", "station-b", RouteNodeType.PLATFORM, "승강장"),
				routeNode("node-station-b-next-platform", "station-b", RouteNodeType.PLATFORM, "다음 승강장")
			);
		}
	}

	private static TransitOperator operator() {
		return new TransitOperator(
			"operator-a",
			"운영사",
			"수도권",
			"https://example.com",
			"https://example.com/contact",
			DataSourceType.OFFICIAL_FILE,
			true
		);
	}

	private static AccessibilityFacility facility(
		String id,
		String stationId,
		String exitId,
		AccessibilityFacilityType type,
		AccessibilityFacilityStatus status
	) {
		return facility(id, stationId, exitId, type, status, DataConfidenceLevel.HIGH);
	}

	private static AccessibilityFacility facility(
		String id,
		String stationId,
		String exitId,
		AccessibilityFacilityType type,
		AccessibilityFacilityStatus status,
		DataConfidenceLevel dataConfidence
	) {
		return facility(id, stationId, exitId, type, status, dataConfidence, LocalDate.of(2026, 6, 13));
	}

	private static AccessibilityFacility facility(
		String id,
		String stationId,
		String exitId,
		AccessibilityFacilityType type,
		AccessibilityFacilityStatus status,
		DataConfidenceLevel dataConfidence,
		LocalDate lastUpdatedAt
	) {
		return new AccessibilityFacility(
			id,
			stationId,
			exitId,
			type,
			"테스트 접근성 시설",
			"지상",
			"대합실",
			BigDecimal.ONE,
			BigDecimal.ONE,
			"테스트용 접근성 시설입니다.",
			status,
			dataConfidence,
			DataSourceType.OFFICIAL_FILE,
			lastUpdatedAt
		);
	}

	private static AccessibilityFacility facility(
		String id,
		String stationId,
		AccessibilityFacilityType type,
		AccessibilityFacilityStatus status
	) {
		return facility(id, stationId, null, type, status);
	}

	private static SubwayLine line(String id) {
		return new SubwayLine(id, "operator-a", "테스트 노선", "#0052A4", "수도권", "T", true);
	}

	private static Station station(String id, String name) {
		return new Station(
			id,
			name,
			name,
			"수도권",
			BigDecimal.ONE,
			BigDecimal.ONE,
			DataQualityLevel.LEVEL_1,
			DataSourceType.OFFICIAL_FILE,
			LocalDate.of(2026, 6, 13),
			true
		);
	}

	private static StationExit stairOnlyExit(String id, String stationId) {
		return new StationExit(
			id,
			stationId,
			"1",
			"1번 출구",
			BigDecimal.ONE,
			BigDecimal.ONE,
			false,
			true,
			DataConfidenceLevel.HIGH,
			DataSourceType.OFFICIAL_FILE
		);
	}

	private static StationExit stepFreeExit(String id, String stationId) {
		return new StationExit(
			id,
			stationId,
			"2",
			"2번 출구",
			BigDecimal.ONE,
			BigDecimal.ONE,
			true,
			false,
			DataConfidenceLevel.HIGH,
			DataSourceType.OFFICIAL_FILE
		);
	}

	private static RouteEdge stairEdge(String id, String stationId) {
		return new RouteEdge(
			id,
			stationId,
			"node-" + stationId + "-entrance",
			"node-" + stationId + "-platform",
			RouteEdgeType.STAIR,
			30,
			90,
			true,
			false,
			false,
			3,
			2,
			95,
			true
		);
	}

	private static RouteNode routeNode(String id, String stationId, RouteNodeType type, String name) {
		return routeNode(id, stationId, type, name, null);
	}

	private static RouteNode routeNode(String id, String stationId, RouteNodeType type, String name, String facilityId) {
		return new RouteNode(
			id,
			stationId,
			type,
			name,
			"B1",
			null,
			null,
			facilityId,
			"layout-" + stationId,
			10,
			20,
			name,
			null
		);
	}

	private static RouteEdge elevatorEdge(String id, String stationId) {
		return new RouteEdge(
			id,
			stationId,
			"node-" + stationId + "-entrance",
			"node-" + stationId + "-platform",
			RouteEdgeType.ELEVATOR,
			30,
			90,
			false,
			true,
			false,
			1,
			2,
			95,
			true
		);
	}

	private static RouteEdge trainEdge(String id, String stationId) {
		return new RouteEdge(
			id,
			stationId,
			"node-" + stationId + "-platform",
			"node-" + stationId + "-next-platform",
			RouteEdgeType.TRAIN,
			900,
			120,
			false,
			false,
			false,
			1,
			3,
			95,
			true
		);
	}

	private static RouteEdge internalEdge(
		String id,
		String fromNodeId,
		String toNodeId,
		RouteEdgeType type,
		int distanceMeters,
		int estimatedSeconds,
		boolean hasStairs
	) {
		return new RouteEdge(
			id,
			"station-a",
			fromNodeId,
			toNodeId,
			type,
			distanceMeters,
			estimatedSeconds,
			hasStairs,
			false,
			false,
			1,
			5,
			95,
			true
		);
	}
}
