package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.adapter.out.persistence.InMemoryRouteSearchRepository;
import com.easysubway.route.application.port.in.SearchRouteCommand;
import com.easysubway.route.domain.RouteNotFoundException;
import com.easysubway.route.domain.RouteSearchNotFoundException;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteWarningCode;
import com.easysubway.transit.adapter.out.persistence.InMemoryTransitMasterRepository;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.StationNotFoundException;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
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
		assertThat(result.steps())
			.extracting("title")
			.containsExactly(
				"상록수역에서 4호선 승강장으로 이동",
				"수도권 4호선으로 사당역까지 이동",
				"사당역에서 출구 접근성 정보를 확인"
			);
		assertThat(result.warnings())
			.extracting("code")
			.contains(RouteWarningCode.LOW_DATA_CONFIDENCE);
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
	}

	@Test
	@DisplayName("환승 경로는 같은 이동 거리의 직접 경로보다 점수가 높다")
	void transferRouteScoreIncludesTransferCost() {
		int transferScore = scoreFor(MobilityType.SENIOR, new OneTransferTransitMasterPort());
		int directScore = scoreFor(MobilityType.SENIOR, new DirectComparableTransitMasterPort());

		assertThat(transferScore).isGreaterThan(directScore);
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
		assertThat(stairOnlyResult.score()).isGreaterThan(accessibleResult.score());
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
	@DisplayName("휠체어 이동 유형은 출구 요약에 엘리베이터 연결이 있으면 별도 시설 행이 없어도 경로를 제공한다")
	void wheelchairRouteAllowsElevatorConnectedExitWithoutFacilityRow() {
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

		assertThat(result.status()).isEqualTo(RouteSearchStatus.FOUND);
		assertThat(result.blockedReasons()).isEmpty();
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
				stepFreeExit("exit-b-1", "station-b")
			);
		}

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of(
				facility(
					"facility-a-ramp",
					"station-a",
					AccessibilityFacilityType.RAMP,
					AccessibilityFacilityStatus.NORMAL
				),
				facility(
					"facility-b-elevator",
					"station-b",
					AccessibilityFacilityType.ELEVATOR,
					AccessibilityFacilityStatus.NORMAL
				)
			);
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
			LocalDate.of(2026, 6, 13)
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
}
