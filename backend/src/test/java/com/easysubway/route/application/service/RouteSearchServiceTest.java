package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.adapter.out.persistence.InMemoryRouteSearchRepository;
import com.easysubway.route.application.model.PlannerIdentity;
import com.easysubway.route.application.port.in.RouteSearchUseCase;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableCandidateSource;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeQuery;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableTripDeparture;
import com.easysubway.route.application.port.in.SearchInternalRouteCommand;
import com.easysubway.route.application.port.in.SearchRouteCommand;
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
import java.util.concurrent.atomic.AtomicReference;
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

	/**
	 * objective 대표 두 건(최속 ITX 환승·최소 환승 직통)에 더해, #2560의 무단차 대안까지 나오는 시각표다.
	 * 두 대표는 각각 계단 환승(station-transfer)과 계단 진입(station-a:line-direct)을 지나고,
	 * station-step-free를 경유하는 세 번째 경로만 검증된 무단차다. planner canary가 이 세 후보를 그대로
	 * 산출하므로 릴리스 증거 게이트가 무단차 대안 편입 경로를 실제로 관측한다.
	 */
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
				new LoadRouteTimetablePort.TransitRoute("route-b", "line-b", "B", "B", "도착 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute("route-c", "line-c", "C", "C", "도착 방면", "Asia/Seoul")
			),
			List.of(
				new LoadRouteTimetablePort.TransitTrip("trip-direct", "route-direct", "weekday-2026", "도착", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip("trip-a", "route-a", "weekday-2026", "환승", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"trip-b", "route-b", "weekday-2026", "도착", "0",
					"ITX_CHEONGCHUN", "EXPRESS", "2001", 0
				),
				new LoadRouteTimetablePort.TransitTrip("trip-c", "route-c", "weekday-2026", "도착", "0", "LOCAL", 0)
			),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime("trip-direct", 1, "station-a", "line-direct", 32820, 32820, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-direct", 2, "station-b", "line-direct", 34200, 34200, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-a", 1, "station-a", "line-a", 32820, 32820, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-a", 2, "station-transfer", "line-a", 33000, 33000, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-a", 3, "station-step-free", "line-a", 33180, 33180, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-b", 1, "station-transfer", "line-b", 33600, 33600, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-b", 2, "station-b", "line-b", 33900, 33900, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-c", 1, "station-step-free", "line-c", 33900, 33900, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("trip-c", 2, "station-b", "line-c", 34080, 34080, 0, 0)
			),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.OfficialFare("trip-direct", "station-a", "station-b", 2_000, "KRW", "official", "snapshot"),
				new LoadRouteTimetablePort.OfficialFare("trip-a", "station-a", "station-transfer", 1_000, "KRW", "official", "snapshot"),
				new LoadRouteTimetablePort.OfficialFare("trip-a", "station-a", "station-step-free", 1_200, "KRW", "official", "snapshot"),
				new LoadRouteTimetablePort.OfficialFare("trip-b", "station-transfer", "station-b", 1_500, "KRW", "official", "snapshot"),
				new LoadRouteTimetablePort.OfficialFare("trip-c", "station-step-free", "station-b", 1_100, "KRW", "official", "snapshot")
			),
			null,
			objectiveRouteAccessData()
		);
	}

	private static LoadRouteTimetablePort.RouteAccessData objectiveRouteAccessData() {
		List<LoadRouteTimetablePort.PathwayNode> nodes = new ArrayList<>();
		List<LoadRouteTimetablePort.PathwayEdge> edges = new ArrayList<>();
		List<LoadRouteTimetablePort.RouteEdgeEvidence> evidence = new ArrayList<>();
		for (String stationLine : List.of(
			"station-a:line-a", "station-transfer:line-a", "station-transfer:line-b",
			"station-step-free:line-a", "station-step-free:line-c",
			"station-b:line-direct", "station-b:line-b", "station-b:line-c")) {
			String[] parts = stationLine.split(":");
			addObjectiveAccess(nodes, edges, evidence, parts[0], parts[1], false);
		}
		// 최소 환승 대표(직통)는 출발역 진입이 계단이다.
		addObjectiveAccess(nodes, edges, evidence, "station-a", "line-direct", true);
		List<LoadRouteTimetablePort.TransferRule> transfers = new ArrayList<>();
		// 최속 대표(ITX 환승)는 계단 환승, 세 번째 후보만 검증된 무단차 환승이다.
		addObjectiveTransfer(nodes, edges, evidence, transfers, "station-transfer", "line-a", "line-b", 120, true);
		addObjectiveTransfer(nodes, edges, evidence, transfers, "station-step-free", "line-a", "line-c", 360, false);
		return new LoadRouteTimetablePort.RouteAccessData(nodes, edges, transfers, evidence);
	}

	private static void addObjectiveAccess(
		List<LoadRouteTimetablePort.PathwayNode> nodes,
		List<LoadRouteTimetablePort.PathwayEdge> edges,
		List<LoadRouteTimetablePort.RouteEdgeEvidence> evidence,
		String station,
		String line,
		boolean entryIncludesStairs
	) {
		String key = station + "-" + line;
		var entry = objectiveEdge(key + "-entry", 240, 180, entryIncludesStairs);
		var exit = objectiveEdge(key + "-exit", 180, 120, false);
		edges.add(entry);
		edges.add(exit);
		nodes.add(new LoadRouteTimetablePort.PathwayNode(entry.fromNodeId(), station, null, "ENTRANCE"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode(entry.toNodeId(), station, line, "PLATFORM"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode(exit.fromNodeId(), station, line, "PLATFORM"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode(exit.toNodeId(), station, null, "EXIT"));
		evidence.add(objectiveEvidence(key + "-entry-evidence", station, line, entry.id(), "ENTRY"));
		evidence.add(objectiveEvidence(key + "-exit-evidence", station, line, exit.id(), "EXIT"));
	}

	private static void addObjectiveTransfer(
		List<LoadRouteTimetablePort.PathwayNode> nodes,
		List<LoadRouteTimetablePort.PathwayEdge> edges,
		List<LoadRouteTimetablePort.RouteEdgeEvidence> evidence,
		List<LoadRouteTimetablePort.TransferRule> transfers,
		String station,
		String fromLine,
		String toLine,
		int durationSeconds,
		boolean includesStairs
	) {
		String key = station + "-" + fromLine + "-" + toLine;
		var edge = objectiveEdge(key + "-transfer", durationSeconds, durationSeconds, includesStairs);
		edges.add(edge);
		nodes.add(new LoadRouteTimetablePort.PathwayNode(edge.fromNodeId(), station, fromLine, "PLATFORM"));
		nodes.add(new LoadRouteTimetablePort.PathwayNode(edge.toNodeId(), station, toLine, "PLATFORM"));
		evidence.add(objectiveEvidence(key + "-transfer-evidence", station, toLine, edge.id(), "TRANSFER"));
		transfers.add(new LoadRouteTimetablePort.TransferRule(
			key + "-rule", station, fromLine, station, toLine, "IN_STATION", durationSeconds,
			edge.id(), includesStairs ? null : edge.id(), "VERIFIED"));
	}

	private static LoadRouteTimetablePort.PathwayEdge objectiveEdge(
		String id, int duration, int distance, boolean includesStairs
	) {
		return new LoadRouteTimetablePort.PathwayEdge(
			id, id + "-from", id + "-to", duration, distance, false, includesStairs, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED");
	}

	private static LoadRouteTimetablePort.RouteEdgeEvidence objectiveEvidence(
		String id, String station, String line, String edgeId, String edgeType
	) {
		return new LoadRouteTimetablePort.RouteEdgeEvidence(
			id, station, line, edgeId, edgeType, "OFFICIAL_SOURCE", "VERIFIED", true, null);
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
