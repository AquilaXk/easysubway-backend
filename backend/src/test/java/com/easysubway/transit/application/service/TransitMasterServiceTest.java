package com.easysubway.transit.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.transit.adapter.out.persistence.InMemoryTransitMasterRepository;
import com.easysubway.transit.application.port.in.CreateAccessibilityFacilityCommand;
import com.easysubway.transit.application.port.in.NearbyStationSearchCommand;
import com.easysubway.transit.application.port.in.StationSearchCommand;
import com.easysubway.transit.application.port.in.UpdateAccessibilityFacilityStatusCommand;
import com.easysubway.transit.application.port.in.UpdateAccessibilityFacilityCommand;
import com.easysubway.transit.application.port.in.UpdateSimplifiedStationLayoutStatusCommand;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.notification.application.port.in.FacilityStatusAlertUseCase;
import com.easysubway.notification.application.port.in.FacilityStatusChangedAlertCommand;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityNotFoundException;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.InvalidAccessibilityFacilityException;
import com.easysubway.transit.domain.InvalidSimplifiedStationLayoutException;
import com.easysubway.transit.domain.RouteEdge;
import com.easysubway.transit.domain.RouteEdgeType;
import com.easysubway.transit.domain.RouteNode;
import com.easysubway.transit.domain.RouteNodeType;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.StationLayoutSource;
import com.easysubway.transit.domain.StationLayoutSourceType;
import com.easysubway.transit.domain.StationNotFoundException;
import com.easysubway.transit.domain.SimplifiedStationLayout;
import com.easysubway.transit.domain.SimplifiedStationLayoutConfidence;
import com.easysubway.transit.domain.SimplifiedStationLayoutNotFoundException;
import com.easysubway.transit.domain.SimplifiedStationLayoutStatus;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("도시철도 마스터데이터 서비스")
class TransitMasterServiceTest {

	private final InMemoryTransitMasterRepository transitRepository = new InMemoryTransitMasterRepository();
	private final TransitMasterService service = new TransitMasterService(transitRepository, transitRepository);

	@Test
	@DisplayName("활성 운영기관 마스터데이터를 반환한다")
	void listOperatorsReturnsActiveMasterData() {
		var operators = service.listOperators();

		assertThat(operators)
			.extracting("id")
			.contains("seoul-metro", "korail");
	}

	@Test
	@DisplayName("지역 목록은 활성 운영기관과 노선과 역 수를 집계한다")
	void listRegionsSummarizesActiveMasterDataCounts() {
		var regions = service.listRegions();

		assertThat(regions)
			.extracting("name")
			.containsExactly("수도권");
		var region = regions.getFirst();
		assertThat(region.operatorCount()).isEqualTo(2);
		assertThat(region.lineCount()).isEqualTo(2);
		assertThat(region.stationCount()).isEqualTo(2);
		assertThat(region.dataQualityCounts())
			.containsEntry(DataQualityLevel.LEVEL_1, 2L);
	}

	@Test
	@DisplayName("운영기관 식별자로 노선을 필터링한다")
	void listLinesCanFilterByOperatorId() {
		var lines = service.listLines("korail");

		assertThat(lines)
			.extracting("id")
			.containsExactly("suin-bundang");
	}

	@Test
	@DisplayName("역 검색은 한글 이름과 영문 이름을 모두 찾는다")
	void searchStationsMatchesKoreanAndEnglishNames() {
		var koreanMatches = service.searchStations(new StationSearchCommand("상록수", null));
		var englishMatches = service.searchStations(new StationSearchCommand("sang", null));

		assertThat(koreanMatches).hasSize(1);
		assertThat(englishMatches).hasSize(1);
		assertThat(koreanMatches.getFirst().station().dataQualityLevel()).isEqualTo(DataQualityLevel.LEVEL_1);
	}

	@Test
	@DisplayName("역 검색은 접근성 정보 품질이 높은 역을 먼저 반환한다")
	void searchStationsPrioritizesHighQualityAccessibilityData() {
		var qualityService = new TransitMasterService(
			new QualityPriorityTransitMasterPort(),
			(facilityId, status, updatedAt) -> {
			}
		);

		var stations = qualityService.searchStations(new StationSearchCommand("중앙", null));

		assertThat(stations)
			.extracting(station -> station.station().id())
			.containsExactly("station-central-level-4", "station-central-level-3", "station-central-level-2", "station-central-level-1");
	}

	@Test
	@DisplayName("노선 필터 역 검색도 접근성 정보 품질이 높은 역을 먼저 반환한다")
	void searchStationsOnLinePrioritizesHighQualityAccessibilityData() {
		var qualityService = new TransitMasterService(
			new QualityPriorityTransitMasterPort(),
			(facilityId, status, updatedAt) -> {
			}
		);

		var stations = qualityService.searchStations(new StationSearchCommand("중앙", "quality-line-a"));

		assertThat(stations)
			.extracting(station -> station.station().id())
			.containsExactly("station-central-level-4", "station-central-level-2");
	}

	@Test
	@DisplayName("역 검색은 한글 초성만 입력해도 역을 찾는다")
	void searchStationsMatchesKoreanInitialConsonants() {
		var stations = service.searchStations(new StationSearchCommand("ㅅㄹㅅ", null));

		assertThat(stations)
			.extracting(station -> station.station().id())
			.containsExactly("station-sangnoksu");
	}

	@Test
	@DisplayName("역 검색 응답에서 비활성 노선은 제외한다")
	void searchStationsExcludesInactiveLinesFromStationResponses() {
		var serviceWithInactiveLine = new TransitMasterService(
			new TransitMasterPortWithInactiveLine(),
			(facilityId, status, updatedAt) -> {
			}
		);

		var stations = serviceWithInactiveLine.searchStations(new StationSearchCommand("상록수", null));
		var inactiveLineMatches = serviceWithInactiveLine.searchStations(new StationSearchCommand("상록수", "closed-line"));

		assertThat(stations).hasSize(1);
		assertThat(stations.getFirst().lines())
			.extracting("id")
			.containsExactly("seoul-4");
		assertThat(inactiveLineMatches).isEmpty();
	}

	@Test
	@DisplayName("가까운 역 조회는 대척점 부동소수점 오차로 먼 역을 포함하지 않는다")
	void searchNearbyStationsDoesNotIncludeAntipodalStationAsZeroDistance() {
		var nearbyStations = service.searchNearbyStations(NearbyStationSearchCommand.of(
			new BigDecimal("-37.302795319689004"),
			new BigDecimal("-53.13351073508813"),
			500,
			10
		));

		assertThat(nearbyStations).isEmpty();
	}

	@Test
	@DisplayName("가까운 역 조회는 역-노선 연결을 한 번만 불러온다")
	void searchNearbyStationsLoadsStationLinesOnce() {
		var repository = new CountingTransitMasterRepository();
		var service = new TransitMasterService(repository, repository);

		service.searchNearbyStations(NearbyStationSearchCommand.of(
			new BigDecimal("37.302795"),
			new BigDecimal("126.866489"),
			50_000,
			10
		));

		assertThat(repository.stationLineLoadCount).isEqualTo(1);
	}

	@Test
	@DisplayName("역 운영 데이터 수 집계는 하위 컬렉션을 한 번씩만 불러온다")
	void countStationMasterDataLoadsEachCollectionOnce() {
		var repository = new CountingTransitMasterRepository();
		var service = new TransitMasterService(repository, repository);

		var counts = service.countStationMasterDataByStationId();

		assertThat(counts.get("station-sangnoksu").exitCount()).isEqualTo(2);
		assertThat(counts.get("station-sangnoksu").facilityCount()).isEqualTo(3);
		assertThat(counts.get("station-sangnoksu").layoutSourceCount()).isEqualTo(1);
		assertThat(counts.get("station-sangnoksu").simplifiedLayoutCount()).isEqualTo(1);
		assertThat(counts.get("station-sangnoksu").routeNodeCount()).isEqualTo(2);
		assertThat(counts.get("station-sangnoksu").routeEdgeCount()).isEqualTo(1);
		assertThat(repository.stationExitLoadCount).isEqualTo(1);
		assertThat(repository.facilityLoadCount).isEqualTo(1);
		assertThat(repository.layoutSourceLoadCount).isEqualTo(1);
		assertThat(repository.simplifiedLayoutLoadCount).isEqualTo(1);
		assertThat(repository.routeNodeLoadCount).isEqualTo(1);
		assertThat(repository.routeEdgeLoadCount).isEqualTo(1);
	}

	@Test
	@DisplayName("존재하지 않는 역 상세 조회는 도메인 예외를 던진다")
	void getStationThrowsDomainExceptionForUnknownStation() {
		assertThatThrownBy(() -> service.getStation("missing"))
			.isInstanceOf(StationNotFoundException.class)
			.hasMessage("역 정보를 찾을 수 없습니다.");
	}

	@Test
	@DisplayName("역 출구 목록은 접근성 신호를 함께 반환한다")
	void listStationExitsReturnsExitAccessibilitySignals() {
		var exits = service.listStationExits("station-sangnoksu");

		assertThat(exits)
			.extracting("id")
			.containsExactly("exit-sangnoksu-1", "exit-sangnoksu-2");
		assertThat(exits.getFirst().exitNumber()).isEqualTo("1");
		assertThat(exits.getFirst().hasElevatorConnection()).isTrue();
		assertThat(exits.getFirst().hasStairOnlyPath()).isFalse();
		assertThat(exits.getFirst().dataConfidence()).isEqualTo(DataConfidenceLevel.HIGH);
	}

	@Test
	@DisplayName("역 시설 목록은 상태와 데이터 신뢰도를 함께 반환한다")
	void listStationFacilitiesReturnsStatusAndConfidence() {
		var facilities = service.listStationFacilities("station-sangnoksu");

		assertThat(facilities)
			.extracting("id")
			.containsExactly("facility-sangnoksu-elevator-1", "facility-sangnoksu-escalator-1", "facility-sangnoksu-accessible-toilet");
		assertThat(facilities.getFirst().type()).isEqualTo(AccessibilityFacilityType.ELEVATOR);
		assertThat(facilities.getFirst().status()).isEqualTo(AccessibilityFacilityStatus.NORMAL);
		assertThat(facilities.getFirst().exitId()).isEqualTo("exit-sangnoksu-1");
		assertThat(facilities.getFirst().dataConfidence()).isEqualTo(DataConfidenceLevel.HIGH);
	}

	@Test
	@DisplayName("역 내부 구조도 기준 자료는 출처와 검수 정보를 함께 반환한다")
	void listStationLayoutSourcesReturnsLicenseAndReviewMetadata() {
		var sources = service.listStationLayoutSources("station-sangnoksu");

		assertThat(sources)
			.extracting("id")
			.containsExactly("layout-source-sangnoksu-station-map");
		assertThat(sources.getFirst().sourceType()).isEqualTo(StationLayoutSourceType.OPERATOR_DIAGRAM);
		assertThat(sources.getFirst().sourceName()).isEqualTo("상록수역 역사 안내도");
		assertThat(sources.getFirst().commercialUseAllowed()).isFalse();
		assertThat(sources.getFirst().attributionRequired()).isTrue();
		assertThat(sources.getFirst().reviewedAt()).isEqualTo(LocalDate.of(2026, 6, 12));
	}

	@Test
	@DisplayName("쉬운 내부 구조도 초안은 상태와 신뢰도와 기준 자료를 함께 반환한다")
	void listSimplifiedStationLayoutsReturnsStatusConfidenceAndSources() {
		var layouts = service.listSimplifiedStationLayouts("station-sangnoksu");

		assertThat(layouts)
			.extracting("id")
			.containsExactly("layout-sangnoksu-draft");
		assertThat(layouts.getFirst().version()).isEqualTo(1);
		assertThat(layouts.getFirst().status()).isEqualTo(SimplifiedStationLayoutStatus.DRAFT);
		assertThat(layouts.getFirst().confidenceLevel())
			.isEqualTo(SimplifiedStationLayoutConfidence.OFFICIAL_DIAGRAM_REFERENCED);
		assertThat(layouts.getFirst().sourceIds())
			.containsExactly("layout-source-sangnoksu-station-map");
		assertThat(layouts.getFirst().layoutJson()).contains("\"nodes\"");
		assertThat(layouts.getFirst().lastVerifiedAt()).isEqualTo(LocalDate.of(2026, 6, 12));
	}

	@Test
	@DisplayName("내부 이동 노드는 구조도와 현장 표시 정보를 함께 반환한다")
	void listRouteNodesReturnsLayoutAndDisplayMetadata() {
		var nodes = service.listRouteNodes("station-sangnoksu");

		assertThat(nodes)
			.extracting("id")
			.containsExactly("node-sangnoksu-elevator-1", "node-sangnoksu-faregate");
		assertThat(nodes.getFirst().type()).isEqualTo(RouteNodeType.ELEVATOR);
		assertThat(nodes.getFirst().layoutId()).isEqualTo("layout-sangnoksu-draft");
		assertThat(nodes.getFirst().facilityId()).isEqualTo("facility-sangnoksu-elevator-1");
		assertThat(nodes.getFirst().displayLabel()).isEqualTo("엘리베이터");
		assertThat(nodes.getFirst().displayX()).isEqualTo(120);
		assertThat(nodes.getFirst().displayY()).isEqualTo(240);
	}

	@Test
	@DisplayName("내부 이동 간선은 이동 난이도와 접근성 제약을 함께 반환한다")
	void listRouteEdgesReturnsDifficultyAndAccessibilityMetadata() {
		var edges = service.listRouteEdges("station-sangnoksu");

		assertThat(edges)
			.extracting("id")
			.containsExactly("edge-sangnoksu-elevator-to-faregate");
		assertThat(edges.getFirst().type()).isEqualTo(RouteEdgeType.WALK);
		assertThat(edges.getFirst().fromNodeId()).isEqualTo("node-sangnoksu-elevator-1");
		assertThat(edges.getFirst().toNodeId()).isEqualTo("node-sangnoksu-faregate");
		assertThat(edges.getFirst().distanceMeters()).isEqualTo(28);
		assertThat(edges.getFirst().estimatedSeconds()).isEqualTo(75);
		assertThat(edges.getFirst().hasStairs()).isFalse();
		assertThat(edges.getFirst().requiresElevator()).isTrue();
		assertThat(edges.getFirst().slopeLevel()).isEqualTo(1);
		assertThat(edges.getFirst().widthLevel()).isEqualTo(2);
		assertThat(edges.getFirst().reliabilityScore()).isEqualTo(92);
		assertThat(edges.getFirst().active()).isTrue();
	}

	@Test
	@DisplayName("역 출구와 시설과 구조도와 노드와 간선 목록은 존재하는 역을 요구한다")
	void stationExitsFacilitiesLayoutsNodesAndEdgesRequireExistingStation() {
		assertThatThrownBy(() -> service.listStationExits("missing"))
			.isInstanceOf(StationNotFoundException.class)
			.hasMessage("역 정보를 찾을 수 없습니다.");
		assertThatThrownBy(() -> service.listStationFacilities("missing"))
			.isInstanceOf(StationNotFoundException.class)
			.hasMessage("역 정보를 찾을 수 없습니다.");
		assertThatThrownBy(() -> service.listStationLayoutSources("missing"))
			.isInstanceOf(StationNotFoundException.class)
			.hasMessage("역 정보를 찾을 수 없습니다.");
		assertThatThrownBy(() -> service.listSimplifiedStationLayouts("missing"))
			.isInstanceOf(StationNotFoundException.class)
			.hasMessage("역 정보를 찾을 수 없습니다.");
		assertThatThrownBy(() -> service.listRouteNodes("missing"))
			.isInstanceOf(StationNotFoundException.class)
			.hasMessage("역 정보를 찾을 수 없습니다.");
		assertThatThrownBy(() -> service.listRouteEdges("missing"))
			.isInstanceOf(StationNotFoundException.class)
			.hasMessage("역 정보를 찾을 수 없습니다.");
	}

	@Test
	@DisplayName("관리자는 시설 상태를 수정하고 갱신일을 기록한다")
	void updateFacilityStatusStoresStatusAndUpdatedDate() {
		var repository = new InMemoryTransitMasterRepository();
		var service = new TransitMasterService(
			repository,
			repository,
			Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);

		var updated = service.updateFacilityStatus(new UpdateAccessibilityFacilityStatusCommand(
			"facility-sangnoksu-elevator-1",
			AccessibilityFacilityStatus.BROKEN,
			"admin-user"
		));

		assertThat(updated.status()).isEqualTo(AccessibilityFacilityStatus.BROKEN);
		assertThat(updated.lastUpdatedAt()).isEqualTo(LocalDate.of(2026, 6, 14));
		assertThat(service.listStationFacilities("station-sangnoksu").getFirst().status())
			.isEqualTo(AccessibilityFacilityStatus.BROKEN);
	}

	@Test
	@DisplayName("관리자 시설 상태 수정은 즐겨찾기 알림을 요청한다")
	void updateFacilityStatusRequestsFavoriteAlert() {
		var repository = new InMemoryTransitMasterRepository();
		var alertUseCase = new RecordingFacilityStatusAlertUseCase();
		var service = new TransitMasterService(
			repository,
			repository,
			alertUseCase,
			Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);

		service.updateFacilityStatus(new UpdateAccessibilityFacilityStatusCommand(
			"facility-sangnoksu-elevator-1",
			AccessibilityFacilityStatus.BROKEN,
			"admin-user"
		));

		assertThat(alertUseCase.commands)
			.extracting(FacilityStatusChangedAlertCommand::facilityId)
			.containsExactly("facility-sangnoksu-elevator-1");
		assertThat(alertUseCase.commands)
			.extracting(FacilityStatusChangedAlertCommand::status)
			.containsExactly(AccessibilityFacilityStatus.BROKEN);
	}

	@Test
	@DisplayName("관리자 시설 상태 수정은 값이 같으면 즐겨찾기 알림을 요청하지 않는다")
	void updateFacilityStatusDoesNotRequestFavoriteAlertWhenStatusIsSame() {
		var repository = new InMemoryTransitMasterRepository();
		var alertUseCase = new RecordingFacilityStatusAlertUseCase();
		var service = new TransitMasterService(
			repository,
			repository,
			alertUseCase,
			Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);

		service.updateFacilityStatus(new UpdateAccessibilityFacilityStatusCommand(
			"facility-sangnoksu-elevator-1",
			AccessibilityFacilityStatus.NORMAL,
			"admin-user"
		));

		assertThat(alertUseCase.commands).isEmpty();
	}

	@Test
	@DisplayName("시설 상태 수정은 상태값과 관리자 식별자를 요구한다")
	void updateFacilityStatusRequiresStatusAndReviewer() {
		var repository = new InMemoryTransitMasterRepository();
		var service = new TransitMasterService(
			repository,
			repository,
			Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);

		assertThatThrownBy(() -> service.updateFacilityStatus(new UpdateAccessibilityFacilityStatusCommand(
			"facility-sangnoksu-elevator-1",
			null,
			"admin-user"
		)))
			.isInstanceOf(InvalidAccessibilityFacilityException.class)
			.hasMessage("시설 상태를 선택해야 합니다.");

		assertThatThrownBy(() -> service.updateFacilityStatus(new UpdateAccessibilityFacilityStatusCommand(
			"facility-sangnoksu-elevator-1",
			AccessibilityFacilityStatus.BROKEN,
			""
		)))
			.isInstanceOf(InvalidAccessibilityFacilityException.class)
			.hasMessage("수정자 식별자가 필요합니다.");
	}

	@Test
	@DisplayName("존재하지 않는 시설 상태는 수정할 수 없다")
	void updateFacilityStatusRequiresExistingFacility() {
		var repository = new InMemoryTransitMasterRepository();
		var service = new TransitMasterService(
			repository,
			repository,
			Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);

		assertThatThrownBy(() -> service.updateFacilityStatus(new UpdateAccessibilityFacilityStatusCommand(
			"missing-facility",
			AccessibilityFacilityStatus.BROKEN,
			"admin-user"
		)))
			.isInstanceOf(AccessibilityFacilityNotFoundException.class)
			.hasMessage("시설 정보를 찾을 수 없습니다.");
	}

	@Test
	@DisplayName("관리자는 쉬운 내부 구조도 검수 상태를 수정하고 검수자를 기록한다")
	void updateSimplifiedStationLayoutStatusStoresStatusAndReviewer() {
		var repository = new InMemoryTransitMasterRepository();
		var service = new TransitMasterService(
			repository,
			repository,
			Clock.fixed(Instant.parse("2026-06-16T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);

		var updated = service.updateSimplifiedStationLayoutStatus(new UpdateSimplifiedStationLayoutStatusCommand(
			"layout-sangnoksu-draft",
			SimplifiedStationLayoutStatus.READY_FOR_REVIEW,
			"admin-user"
		));

		assertThat(updated.status()).isEqualTo(SimplifiedStationLayoutStatus.READY_FOR_REVIEW);
		assertThat(updated.reviewedBy()).isEqualTo("admin-user");
		assertThat(updated.lastVerifiedAt()).isEqualTo(LocalDate.of(2026, 6, 16));
		assertThat(service.listSimplifiedStationLayouts("station-sangnoksu").getFirst().status())
			.isEqualTo(SimplifiedStationLayoutStatus.READY_FOR_REVIEW);
	}

	@Test
	@DisplayName("쉬운 내부 구조도 검수 상태 수정은 상태값과 관리자 식별자를 요구한다")
	void updateSimplifiedStationLayoutStatusRequiresStatusAndReviewer() {
		var repository = new InMemoryTransitMasterRepository();
		var service = new TransitMasterService(repository, repository);

		assertThatThrownBy(() -> service.updateSimplifiedStationLayoutStatus(new UpdateSimplifiedStationLayoutStatusCommand(
			"layout-sangnoksu-draft",
			null,
			"admin-user"
		)))
			.isInstanceOf(InvalidSimplifiedStationLayoutException.class)
			.hasMessage("구조도 상태를 선택해야 합니다.");

		assertThatThrownBy(() -> service.updateSimplifiedStationLayoutStatus(new UpdateSimplifiedStationLayoutStatusCommand(
			"layout-sangnoksu-draft",
			SimplifiedStationLayoutStatus.PUBLISHED,
			""
		)))
			.isInstanceOf(InvalidSimplifiedStationLayoutException.class)
			.hasMessage("검수자 식별자가 필요합니다.");
	}

	@Test
	@DisplayName("존재하지 않는 쉬운 내부 구조도 상태는 수정할 수 없다")
	void updateSimplifiedStationLayoutStatusRequiresExistingLayout() {
		var repository = new InMemoryTransitMasterRepository();
		var service = new TransitMasterService(repository, repository);

		assertThatThrownBy(() -> service.updateSimplifiedStationLayoutStatus(new UpdateSimplifiedStationLayoutStatusCommand(
			"missing-layout",
			SimplifiedStationLayoutStatus.PUBLISHED,
			"admin-user"
		)))
			.isInstanceOf(SimplifiedStationLayoutNotFoundException.class)
			.hasMessage("역 구조도 정보를 찾을 수 없습니다.");
	}

	@Test
	@DisplayName("관리자는 접근성 시설을 등록하고 역 시설 목록에서 확인한다")
	void createAccessibilityFacilityStoresFacility() {
		var repository = new InMemoryTransitMasterRepository();
		var service = new TransitMasterService(
			repository,
			repository,
			Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);

		var created = service.createAccessibilityFacility(new CreateAccessibilityFacilityCommand(
			"facility-sangnoksu-ramp-1",
			"station-sangnoksu",
			"exit-sangnoksu-2",
			AccessibilityFacilityType.RAMP,
			"2번 출구 경사로",
			"지상",
			"대합실",
			new BigDecimal("37.303041"),
			new BigDecimal("126.866768"),
			"2번 출구와 대합실 사이 경사로입니다.",
			AccessibilityFacilityStatus.NORMAL,
			DataConfidenceLevel.MEDIUM,
			DataSourceType.ADMIN_VERIFIED,
			"admin-user"
		));

		assertThat(created.id()).isEqualTo("facility-sangnoksu-ramp-1");
		assertThat(created.lastUpdatedAt()).isEqualTo(LocalDate.of(2026, 6, 15));
		assertThat(service.listStationFacilities("station-sangnoksu"))
			.extracting(AccessibilityFacility::id)
			.contains("facility-sangnoksu-ramp-1");
	}

	@Test
	@DisplayName("관리자는 접근성 시설 전체 정보를 수정하고 상태 변경 알림을 요청한다")
	void updateAccessibilityFacilityReplacesFacilityAndRequestsAlertWhenStatusChanges() {
		var repository = new InMemoryTransitMasterRepository();
		var alertUseCase = new RecordingFacilityStatusAlertUseCase();
		var service = new TransitMasterService(
			repository,
			repository,
			alertUseCase,
			Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneId.of("Asia/Seoul"))
		);

		var updated = service.updateAccessibilityFacility(new UpdateAccessibilityFacilityCommand(
			"facility-sangnoksu-elevator-1",
			"station-sangnoksu",
			"exit-sangnoksu-1",
			AccessibilityFacilityType.ELEVATOR,
			"1번 출구 엘리베이터 점검 반영",
			"지상",
			"대합실",
			new BigDecimal("37.302430"),
			new BigDecimal("126.866230"),
			"관리자 검수 후 위치와 설명을 보정했습니다.",
			AccessibilityFacilityStatus.UNDER_CONSTRUCTION,
			DataConfidenceLevel.HIGH,
			DataSourceType.ADMIN_VERIFIED,
			"admin-user"
		));

		assertThat(updated.name()).isEqualTo("1번 출구 엘리베이터 점검 반영");
		assertThat(updated.status()).isEqualTo(AccessibilityFacilityStatus.UNDER_CONSTRUCTION);
		assertThat(updated.lastUpdatedAt()).isEqualTo(LocalDate.of(2026, 6, 15));
		assertThat(service.listStationFacilities("station-sangnoksu").getFirst().description())
			.isEqualTo("관리자 검수 후 위치와 설명을 보정했습니다.");
		assertThat(alertUseCase.commands)
			.extracting(FacilityStatusChangedAlertCommand::facilityId)
			.containsExactly("facility-sangnoksu-elevator-1");
	}

	@Test
	@DisplayName("시설 등록은 중복 식별자와 다른 역의 출구를 거부한다")
	void createAccessibilityFacilityRejectsDuplicateIdAndExitFromAnotherStation() {
		var repository = new InMemoryTransitMasterRepository();
		var service = new TransitMasterService(repository, repository);

		assertThatThrownBy(() -> service.createAccessibilityFacility(new CreateAccessibilityFacilityCommand(
			"facility-sangnoksu-elevator-1",
			"station-sangnoksu",
			"exit-sangnoksu-1",
			AccessibilityFacilityType.ELEVATOR,
			"중복 시설",
			"지상",
			"대합실",
			null,
			null,
			null,
			AccessibilityFacilityStatus.NORMAL,
			DataConfidenceLevel.HIGH,
			DataSourceType.ADMIN_VERIFIED,
			"admin-user"
		)))
			.isInstanceOf(InvalidAccessibilityFacilityException.class)
			.hasMessage("이미 등록된 시설입니다.");

		assertThatThrownBy(() -> service.createAccessibilityFacility(new CreateAccessibilityFacilityCommand(
			"facility-sangnoksu-ramp-1",
			"station-sangnoksu",
			"exit-sadang-2",
			AccessibilityFacilityType.RAMP,
			"2번 출구 경사로",
			"지상",
			"대합실",
			null,
			null,
			null,
			AccessibilityFacilityStatus.NORMAL,
			DataConfidenceLevel.MEDIUM,
			DataSourceType.ADMIN_VERIFIED,
			"admin-user"
		)))
			.isInstanceOf(InvalidAccessibilityFacilityException.class)
			.hasMessage("시설 출구가 역에 포함되어 있지 않습니다.");
	}

	@Test
	@DisplayName("시설 전체 수정은 기존 시설과 필수값을 요구한다")
	void updateAccessibilityFacilityRequiresExistingFacilityAndRequiredFields() {
		var repository = new InMemoryTransitMasterRepository();
		var service = new TransitMasterService(repository, repository);

		assertThatThrownBy(() -> service.updateAccessibilityFacility(new UpdateAccessibilityFacilityCommand(
			"missing-facility",
			"station-sangnoksu",
			null,
			AccessibilityFacilityType.TOILET,
			"화장실",
			"대합실",
			"대합실",
			null,
			null,
			null,
			AccessibilityFacilityStatus.NORMAL,
			DataConfidenceLevel.HIGH,
			DataSourceType.ADMIN_VERIFIED,
			"admin-user"
		)))
			.isInstanceOf(AccessibilityFacilityNotFoundException.class)
			.hasMessage("시설 정보를 찾을 수 없습니다.");

		assertThatThrownBy(() -> service.updateAccessibilityFacility(new UpdateAccessibilityFacilityCommand(
			"facility-sangnoksu-elevator-1",
			"station-sangnoksu",
			null,
			null,
			"",
			"지상",
			"대합실",
			null,
			null,
			null,
			AccessibilityFacilityStatus.NORMAL,
			DataConfidenceLevel.HIGH,
			DataSourceType.ADMIN_VERIFIED,
			"admin-user"
		)))
			.isInstanceOf(InvalidAccessibilityFacilityException.class)
			.hasMessage("시설 유형을 선택해야 합니다.");
	}

	private static class TransitMasterPortWithInactiveLine implements LoadTransitMasterPort {

		@Override
		public List<TransitOperator> loadOperators() {
			return List.of(
				new TransitOperator(
					"seoul-metro",
					"서울교통공사",
					"수도권",
					"https://www.seoulmetro.co.kr",
					"https://www.seoulmetro.co.kr/kr/customerMain.do",
					DataSourceType.OFFICIAL_FILE,
					true
				)
			);
		}

		@Override
		public List<SubwayLine> loadLines() {
			return List.of(
				new SubwayLine("seoul-4", "seoul-metro", "수도권 4호선", "#00A5DE", "수도권", "4", true),
				new SubwayLine("closed-line", "seoul-metro", "운영 종료 노선", "#999999", "수도권", "C", false)
			);
		}

		@Override
		public List<Station> loadStations() {
			return List.of(
				new Station(
					"station-sangnoksu",
					"상록수",
					"Sangnoksu",
					"수도권",
					new BigDecimal("37.302795"),
					new BigDecimal("126.866489"),
					DataQualityLevel.LEVEL_1,
					DataSourceType.OFFICIAL_FILE,
					LocalDate.of(2026, 6, 12),
					true
				)
			);
		}

		@Override
		public List<StationLine> loadStationLines() {
			return List.of(
				new StationLine("station-sangnoksu", "seoul-4", "448", 48, "당고개 방면 / 오이도 방면"),
				new StationLine("station-sangnoksu", "closed-line", "999", 99, "운영 종료")
			);
		}

		@Override
		public List<StationExit> loadStationExits() {
			return List.of();
		}

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of();
		}
	}

	private static class QualityPriorityTransitMasterPort implements LoadTransitMasterPort {

		@Override
		public List<TransitOperator> loadOperators() {
			return List.of(
				new TransitOperator(
					"quality-operator",
					"품질 운영기관",
					"수도권",
					"https://operator.easysubway.example",
					"https://operator.easysubway.example/help",
					DataSourceType.OFFICIAL_FILE,
					true
				)
			);
		}

		@Override
		public List<SubwayLine> loadLines() {
			return List.of(
				new SubwayLine("quality-line-a", "quality-operator", "품질 A선", "#006D77", "수도권", "A", true),
				new SubwayLine("quality-line-b", "quality-operator", "품질 B선", "#83C5BE", "수도권", "B", true)
			);
		}

		@Override
		public List<Station> loadStations() {
			return List.of(
				station("station-central-level-1", "중앙역", DataQualityLevel.LEVEL_1),
				station("station-central-level-3", "중앙역", DataQualityLevel.LEVEL_3),
				station("station-central-level-2", "중앙역", DataQualityLevel.LEVEL_2),
				station("station-central-level-4", "중앙역", DataQualityLevel.LEVEL_4)
			);
		}

		@Override
		public List<StationLine> loadStationLines() {
			return List.of(
				new StationLine("station-central-level-1", "quality-line-b", "101", 101, "상행 / 하행"),
				new StationLine("station-central-level-2", "quality-line-a", "102", 102, "상행 / 하행"),
				new StationLine("station-central-level-3", "quality-line-b", "103", 103, "상행 / 하행"),
				new StationLine("station-central-level-4", "quality-line-a", "104", 104, "상행 / 하행")
			);
		}

		@Override
		public List<StationExit> loadStationExits() {
			return List.of();
		}

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of();
		}

		private Station station(String id, String nameKo, DataQualityLevel dataQualityLevel) {
			return new Station(
				id,
				nameKo,
				"Central",
				"수도권",
				new BigDecimal("37.300000"),
				new BigDecimal("126.800000"),
				dataQualityLevel,
				DataSourceType.OFFICIAL_FILE,
				LocalDate.of(2026, 6, 12),
				true
			);
		}
	}

	private static class RecordingFacilityStatusAlertUseCase implements FacilityStatusAlertUseCase {

		private final java.util.List<FacilityStatusChangedAlertCommand> commands = new java.util.ArrayList<>();

		@Override
		public void alertFacilityStatusChanged(FacilityStatusChangedAlertCommand command) {
			commands.add(command);
		}
	}

	private static class CountingTransitMasterRepository extends InMemoryTransitMasterRepository {

		private int stationLineLoadCount;
		private int stationExitLoadCount;
		private int facilityLoadCount;
		private int layoutSourceLoadCount;
		private int simplifiedLayoutLoadCount;
		private int routeNodeLoadCount;
		private int routeEdgeLoadCount;

		@Override
		public List<StationLine> loadStationLines() {
			stationLineLoadCount++;
			return super.loadStationLines();
		}

		@Override
		public List<StationExit> loadStationExits() {
			stationExitLoadCount++;
			return super.loadStationExits();
		}

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			facilityLoadCount++;
			return super.loadAccessibilityFacilities();
		}

		@Override
		public List<StationLayoutSource> loadStationLayoutSources() {
			layoutSourceLoadCount++;
			return super.loadStationLayoutSources();
		}

		@Override
		public List<SimplifiedStationLayout> loadSimplifiedStationLayouts() {
			simplifiedLayoutLoadCount++;
			return super.loadSimplifiedStationLayouts();
		}

		@Override
		public List<RouteNode> loadRouteNodes() {
			routeNodeLoadCount++;
			return super.loadRouteNodes();
		}

		@Override
		public List<RouteEdge> loadRouteEdges() {
			routeEdgeLoadCount++;
			return super.loadRouteEdges();
		}
	}
}
