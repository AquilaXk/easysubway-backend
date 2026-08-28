package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.SearchRouteV2Command;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.domain.ConstraintMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#2249 timetable compiled snapshot")
class RouteTimetableRaptorPlannerCompiledSnapshotTest {

	private static final LocalDate WEDNESDAY = LocalDate.of(2026, 7, 1);
	private final RouteTimetableRaptorPlanner planner = new RouteTimetableRaptorPlanner();

	@Test
	@DisplayName("feed grouping·정렬·frequency 전개 결과를 한 번 compile한다")
	void compilesDenseIndexesPatternsAndFrequencyTrips() {
		var compiled = planner.compile(frequencyTimetable());

		assertThat(compiled.stationCount()).isEqualTo(2);
		assertThat(compiled.routeCount()).isOne();
		assertThat(compiled.tripCount()).isOne();
		assertThat(compiled.routePatternCount()).isOne();
		assertThat(compiled.scheduledTripCount()).isEqualTo(3);
		assertThat(compiled.primitiveTimeArrayCount()).isEqualTo(3);
	}

	@Test
	@DisplayName("mobility×constraint 18개 조합을 서로 다른 profile bit로 compile한다")
	void compilesUniqueAccessProfileBits() {
		var bits = new HashSet<Integer>();
		for (MobilityType mobilityType : MobilityType.values()) {
			for (ConstraintMode constraintMode : ConstraintMode.values()) {
				bits.add(RouteTimetableRaptorPlanner.profileBit(mobilityType, constraintMode));
			}
		}
		assertThat(bits).hasSize(18).allMatch(bit -> Integer.bitCount(bit) == 1);
	}
	@Test
	@DisplayName("verified transition과 non-strict 기본값을 primitive access table로 compile한다")
	void compilesVerifiedTransitionsAndNonStrictDefaults() {
		var accessData = new LoadRouteTimetablePort.RouteAccessData(
			List.of(
				new LoadRouteTimetablePort.PathwayNode("entry", "station-a", "line", "ENTRANCE"),
				new LoadRouteTimetablePort.PathwayNode("platform", "station-a", "line", "PLATFORM")
			),
			List.of(new LoadRouteTimetablePort.PathwayEdge(
				"canonical-entry", "entry", "platform", 360, 60, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED", "verified-entry"), new LoadRouteTimetablePort.PathwayEdge(
				"fast-stairs", "entry", "platform", 62, 20, false, true, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED")),
			List.of(new LoadRouteTimetablePort.TransferRule(
				"outside", "station-a", "line", "station-b", "line", "OUT_OF_STATION",
				120, "verified-entry", null, "VERIFIED"
			), new LoadRouteTimetablePort.TransferRule(
				"rule-only", "station-a", "line", "station-a", "line", "IN_STATION",
				178, null, null, "VERIFIED"
			)),
			List.of(new LoadRouteTimetablePort.RouteEdgeEvidence(
				"entry-evidence", "station-a", "line", "verified-entry", "ENTRY",
				"OFFICIAL_SOURCE", "VERIFIED", true, null), new LoadRouteTimetablePort.RouteEdgeEvidence(
				"stairs-evidence", "station-a", "line", "fast-stairs", "ENTRY",
				"OFFICIAL_SOURCE", "VERIFIED", false, "STAIRS"), new LoadRouteTimetablePort.RouteEdgeEvidence(
				"station-wide", "station-a", null, "fast-stairs", "GENERATED_CONNECTOR",
				"GENERATED", "GENERATED", false, "NO_LINE"))
		);
		var compiled = planner.compile(withAccess(everyDayTimetable(), accessData));
		int stationA = compiled.stationIndex("station-a"), stationB = compiled.stationIndex("station-b"), line = compiled.lineIndex("line");
		int strict = RouteTimetableRaptorPlanner.profileBit(MobilityType.WHEELCHAIR, ConstraintMode.STRICT_STEP_FREE);
		int allow = RouteTimetableRaptorPlanner.profileBit(MobilityType.SENIOR, ConstraintMode.ALLOW_WITH_WARNINGS);
		int verifiedEntry = compiled.entryTransition(stationA, line, strict, false);
		int defaultExit = compiled.exitTransition(stationB, line, allow, false);
		int ruleOnlyTransfer = compiled.transferTransition(stationA, line, line, allow, false);
		assertThat(compiled.transitionDurationSeconds(verifiedEntry)).isEqualTo(360);
		assertThat(compiled.transitionDistanceMeters(verifiedEntry)).isEqualTo(60);
		assertThat(compiled.transitionDurationSeconds(compiled.entryTransition(stationA, line, allow, false))).isEqualTo(62);
		assertThat(compiled.transitionDurationSeconds(defaultExit)).isEqualTo(180);
		assertThat(compiled.transitionDistanceMeters(defaultExit)).isEqualTo(120);
		assertThat(compiled.transitionDurationSeconds(ruleOnlyTransfer)).isEqualTo(178);
		assertThat(compiled.transferTransition(stationA, line, line, strict, false)).isEqualTo(-1);
		assertThat(compiled.exitTransition(stationB, line, strict, false)).isEqualTo(-1);
		assertThat(compiled.unsupportedTransferCount()).isOne();
	}

	@Test
	@DisplayName("unsafe·unverified·중복 evidence는 strict에서, 운영 불가는 모든 profile에서 차단한다")
	void strictBlocksUnsafeAndUnverifiedTransitions() {
		int strict = RouteTimetableRaptorPlanner.profileBit(MobilityType.WHEELCHAIR, ConstraintMode.STRICT_STEP_FREE);
		int allow = RouteTimetableRaptorPlanner.profileBit(MobilityType.SENIOR, ConstraintMode.ALLOW_WITH_WARNINGS);
		int prefer = RouteTimetableRaptorPlanner.profileBit(MobilityType.SENIOR, ConstraintMode.PREFER_STEP_FREE);
		List<LoadRouteTimetablePort.RouteAccessData> unsafe = List.of(
			entryAccess("VERIFIED", "OFFICIAL_SOURCE", true, true),
			entryAccess("GENERATED", "GENERATED", false, false),
			entryAccess("UNKNOWN", "UNKNOWN", false, false, "NO_OFFICIAL_FEED", 100),
			entryAccess("VERIFIED", "OFFICIAL_SOURCE", false, false, "NO_OFFICIAL_FEED", 100),
			entryAccess("STALE", "OFFICIAL_SOURCE", false, false),
			entryAccess(null, null, false, false),
			entryAccess("VERIFIED", "OFFICIAL_SOURCE", true, false, "AVAILABLE", 79),
			entryAccess("VERIFIED", "OFFICIAL_SOURCE", true, false, "UNDER_MAINTENANCE", 100)
		);
		for (int index = 0; index < unsafe.size(); index += 1) {
			var compiled = planner.compile(withAccess(everyDayTimetable(), unsafe.get(index)));
			int station = compiled.stationIndex("station-a"), line = compiled.lineIndex("line");
			assertThat(compiled.entryTransition(station, line, strict, false)).isEqualTo(-1);
			assertThat(List.of(prefer, allow).stream().allMatch(profile -> compiled.entryTransition(station, line, profile, false) < 0))
				.isEqualTo(index == unsafe.size() - 1);
			if (index == 3) {
				assertThat(compiled.transitionVerified(compiled.entryTransition(station, line, allow, false))).isFalse();
			}
		}
		var stale = planner.compile(withAccess(everyDayTimetable(), entryAccess("STALE", "OFFICIAL_SOURCE", false, false)));
		assertThat(stale.transitionVerificationStatus(stale.entryTransition(stale.stationIndex("station-a"),
			stale.lineIndex("line"), allow, false))).isEqualTo("STALE");
		var staleEdge = planner.compile(withAccess(everyDayTimetable(), entryAccessWithStatuses("STALE", "VERIFIED")));
		assertThat(staleEdge.transitionVerificationStatus(staleEdge.entryTransition(staleEdge.stationIndex("station-a"),
			staleEdge.lineIndex("line"), allow, false))).isEqualTo("STALE");
		var access = entryAccess("VERIFIED", "OFFICIAL_SOURCE", true, false);
		var compiled = planner.compile(withAccess(everyDayTimetable(), new LoadRouteTimetablePort.RouteAccessData(
			access.pathwayNodes(), access.pathwayEdges(),
			access.transferRules(), List.of(access.routeEdgeEvidence().get(0), new LoadRouteTimetablePort.RouteEdgeEvidence(
				"newer-stale", "station-a", "line", "entry-edge", "ENTRY", "OFFICIAL_SOURCE", "STALE", false, "STALE")))));
		assertThat(compiled.entryTransition(compiled.stationIndex("station-a"), compiled.lineIndex("line"),
			RouteTimetableRaptorPlanner.profileBit(MobilityType.WHEELCHAIR, ConstraintMode.STRICT_STEP_FREE), false))
			.isEqualTo(-1);
	}

	@Test
	@DisplayName("transfer evidence는 같은 edge의 ENTRY가 아니라 station·line·TRANSFER 식별자로 선택한다")
	void selectsTransferEvidenceByFullIdentity() {
		var compiled = planner.compile(withAccess(oneTransferTimetable(), ambiguousTransferEvidenceAccess()));
		int strict = RouteTimetableRaptorPlanner.profileBit(MobilityType.WHEELCHAIR, ConstraintMode.STRICT_STEP_FREE);
		assertThat(compiled.transferTransition(compiled.stationIndex("station-x"), compiled.lineIndex("line-1"),
			compiled.lineIndex("line-2"), strict, false)).isEqualTo(-1);
	}

	@Test
	@DisplayName("다른 역 pathway를 가리키는 evidence는 strict 후보로 사용하지 않는다")
	void rejectsEvidenceOwnedByAnotherStation() {
		var access = entryAccess("VERIFIED", "OFFICIAL_SOURCE", true, false);
		var foreignNodes = access.pathwayNodes().stream()
			.map(node -> new LoadRouteTimetablePort.PathwayNode(node.id(), "station-b", node.lineId(), node.nodeType()))
			.toList();
		var compiled = planner.compile(withAccess(everyDayTimetable(), new LoadRouteTimetablePort.RouteAccessData(
			foreignNodes, access.pathwayEdges(), access.transferRules(), access.routeEdgeEvidence())));
		assertThat(compiled.entryTransition(compiled.stationIndex("station-a"), compiled.lineIndex("line"),
			RouteTimetableRaptorPlanner.profileBit(MobilityType.WHEELCHAIR, ConstraintMode.STRICT_STEP_FREE), false))
			.isEqualTo(-1);
	}

	@Test
	@DisplayName("scan은 선택한 verified entry·exit의 시간과 거리만 경로에 반영한다")
	void scanUsesSelectedVerifiedEntryAndExitTransitions() {
		var command = new SearchRouteV2Command(
			"station-a",
			"station-b",
			OffsetDateTime.parse("2026-07-01T08:50:00+09:00"),
			MobilityType.WHEELCHAIR,
			ConstraintMode.STRICT_STEP_FREE,
			false,
			0,
			1
		);
		var results = planner.search(command, planner.compile(withAccess(lineChangingTimetable(), verifiedDirectAccess())));
		assertThat(results).singleElement().satisfies(result -> {
			assertThat(result.warnings()).isEmpty();
			assertThat(result.steps())
				.filteredOn(step -> "entry".equals(step.stepType()) || "exit".equals(step.stepType()))
				.extracting("walkSeconds", "distanceMeters", "includesStairs", "requiresAccessibilityCheck")
				.containsExactly(
					org.assertj.core.groups.Tuple.tuple(180, 70, false, false),
					org.assertj.core.groups.Tuple.tuple(135, 40, false, false)
				);
		});
	}

	@Test
	@DisplayName("strict scan은 검증되지 않은 access transition만 있으면 경로를 반환하지 않는다")
	void strictScanRejectsUnverifiedAccessTransitions() {
		var command = new SearchRouteV2Command(
			"station-a",
			"station-b",
			OffsetDateTime.parse("2026-07-01T08:50:00+09:00"),
			MobilityType.WHEELCHAIR,
			ConstraintMode.STRICT_STEP_FREE,
			false,
			0,
			1
		);
		assertThat(planner.search(command, planner.compile(everyDayTimetable()))).isEmpty();
	}
	@Test
	@DisplayName("scan은 이전 ride line에서 다음 ride line으로 가는 transfer transition을 사용한다")
	void scanUsesLineToLineTransferTransition() {
		var command = new SearchRouteV2Command(
			"station-a",
			"station-b",
			OffsetDateTime.parse("2026-07-01T08:40:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.ALLOW_WITH_WARNINGS,
			false,
			1,
			1
		);
		var results = planner.search(command, planner.compile(withAccess(oneTransferTimetable(), verifiedTransferAccess())));
		assertThat(results).singleElement().satisfies(result ->
			assertThat(result.steps()).filteredOn(step -> "transfer".equals(step.stepType()))
				.extracting("walkSeconds", "distanceMeters")
				.containsExactly(org.assertj.core.groups.Tuple.tuple(81, 25)));
	}
	@Test
	@DisplayName("scan은 빠른 unknown 상태보다 같은 열차를 타는 verified 유입 노선을 보존한다")
	void strictScanPreservesIncomingLineLabels() {
		for (ConstraintMode mode : List.of(ConstraintMode.STRICT_STEP_FREE, ConstraintMode.ALLOW_WITH_WARNINGS)) {
			var command = new SearchRouteV2Command(
				"station-origin", "station-destination", OffsetDateTime.parse("2026-07-01T08:50:00+09:00"),
				MobilityType.WHEELCHAIR, mode, false, 1, 1);
			var results = planner.search(command, planner.compile(withAccess(
				incomingLineDominanceTimetable(), incomingLineDominanceAccess())));
			assertThat(results).singleElement().satisfies(result -> {
				assertThat(result.warnings()).isEmpty();
				assertThat(result.steps()).filteredOn(step -> "ride".equals(step.stepType()))
					.extracting("tripId").containsExactly("trip-b", "trip-c");
			});
		}
	}
	@Test
	@DisplayName("같은 route의 branch·short-turn station sequence를 distinct pattern으로 compile한다")
	void compilesBranchAndShortTurnTripsAsDistinctPatterns() {
		var compiled = planner.compile(branchAndShortTurnTimetable());

		assertThat(compiled.routePatternCount()).isEqualTo(3);
		assertThat(compiled.scheduledTripCount()).isEqualTo(4);
		assertThat(compiled.routePatternTripLinkCount()).isEqualTo(compiled.scheduledTripCount());
	}

	@Test
	@DisplayName("compiled frequency 출발은 반복 검색에서도 기존 시각을 유지한다")
	void preservesFrequencyDeparturesAcrossRepeatedSearches() {
		var compiled = planner.compile(frequencyTimetable());
		var command = command(WEDNESDAY, 9, 5);

		var first = planner.search(command, compiled);
		var second = planner.search(command, compiled);

		assertThat(first).hasSize(1);
		assertThat(second).extracting("estimatedDurationSeconds")
			.containsExactly(first.getFirst().estimatedDurationSeconds());
		assertThat(first.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("plannedDepartureTime")
			.containsExactly("2026-07-01T09:20:00+09:00");
	}

	@Test
	@DisplayName("search의 비교와 결과 시각은 compiled primitive 배열을 사용한다")
	void usesPrimitiveTimesForSearchAndResultTimes() throws Exception {
		var compiled = planner.compile(everyDayTimetable());
		primitiveIntArray(compiled, "departureSeconds")[0] = 32520;
		primitiveIntArray(compiled, "arrivalSeconds")[1] = 33120;

		var results = planner.search(command(WEDNESDAY, 8, 50), compiled);

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("plannedDepartureTime", "plannedArrivalTime")
			.containsExactly(org.assertj.core.groups.Tuple.tuple(
				"2026-07-01T09:02:00+09:00",
				"2026-07-01T09:12:00+09:00"));
	}

	@Test
	@DisplayName("search의 승하차 허용 판단은 compiled primitive 배열을 사용한다")
	void usesPrimitivePickupAndDropOffTypesForSearch() throws Exception {
		var pickupBlocked = planner.compile(everyDayTimetable());
		primitiveByteArray(pickupBlocked, "pickupTypes")[0] = 1;
		var dropOffBlocked = planner.compile(everyDayTimetable());
		primitiveByteArray(dropOffBlocked, "dropOffTypes")[1] = 1;

		assertThat(planner.search(command(WEDNESDAY, 8, 50), pickupBlocked)).isEmpty();
		assertThat(planner.search(command(WEDNESDAY, 8, 50), dropOffBlocked)).isEmpty();
	}

	@Test
	@DisplayName("nextServiceTime 비교는 compiled primitive 출발 시각을 사용한다")
	void usesPrimitiveDepartureTimeForNextServiceTime() throws Exception {
		var compiled = planner.compile(everyDayTimetable());
		primitiveIntArray(compiled, "departureSeconds")[0] = 32700;

		assertThat(planner.nextServiceTime(command(WEDNESDAY, 8, 50), compiled))
			.contains(OffsetDateTime.parse("2026-07-01T09:05:00+09:00"));
	}

	@Test
	@DisplayName("strict nextServiceTime은 차단된 entry·exit transition의 운행을 안내하지 않는다")
	void strictNextServiceTimeSkipsAccessBlockedService() {
		var command = new SearchRouteV2Command(
			"station-a",
			"station-b",
			OffsetDateTime.parse("2026-07-01T08:50:00+09:00"),
			MobilityType.WHEELCHAIR,
			ConstraintMode.STRICT_STEP_FREE,
			false,
			0,
			1
		);
		assertThat(planner.nextServiceTime(command, planner.compile(everyDayTimetable()))).isEmpty();
	}
	@Test
	@DisplayName("같은 service day의 immutable active snapshot을 재사용한다")
	void reusesSameActiveServiceDay() {
		var compiled = planner.compile(frequencyTimetable());

		var first = compiled.activeServiceDay(WEDNESDAY);
		var second = compiled.activeServiceDay(WEDNESDAY);

		assertThat(second).isSameAs(first);
		assertThat(compiled.activeServiceDayCacheSize()).isOne();
	}

	@Test
	@DisplayName("active service day는 pattern trip을 stop마다 복제하지 않는다")
	void storesEachActivePatternTripOnce() {
		var compiled = planner.compile(frequencyTimetable());

		var activeDay = compiled.activeServiceDay(WEDNESDAY);

		assertThat(activeDay.routePatternTripLinkCount()).isEqualTo(compiled.scheduledTripCount());
	}

	@Test
	@DisplayName("일반 search는 next-service 전용 boarding index를 생성하지 않는다")
	void regularSearchDoesNotAllocateBoardingIndex() {
		var compiled = planner.compile(frequencyTimetable());

		assertThat(planner.search(command(WEDNESDAY, 9, 5), compiled)).hasSize(1);

		assertThat(compiled.activeServiceDay(WEDNESDAY).boardingIndexInitialized()).isFalse();
	}

	@Test
	@DisplayName("search는 marked stop이 속한 route pattern만 스캔한다")
	void scansOnlyMarkedRoutePatterns() {
		var compiled = planner.compile(disconnectedRoutesTimetable());

		assertThat(planner.search(command(WEDNESDAY, 8, 50), compiled)).hasSize(1);

		var metrics = planner.lastScanMetrics();
		assertThat(metrics.expandedRoutes()).isOne();
		assertThat(metrics.expandedTrips()).isOne();
		assertThat(metrics.expandedTransfers()).isZero();

		planner.search(command(WEDNESDAY, 8, 50), compiled);
		assertThat(planner.lastScanMetrics().workspaceIdentity()).isEqualTo(metrics.workspaceIdentity());
	}

	@Test
	@DisplayName("scan은 valid round-one transfer 검사만 계수하고 workspace 재사용 시 reset한다")
	void countsAndResetsExpandedTransfers() {
		var command = new SearchRouteV2Command(
			"station-a", "station-b", OffsetDateTime.parse("2026-07-01T08:40:00+09:00"),
			MobilityType.SENIOR, ConstraintMode.ALLOW_WITH_WARNINGS, false, 1, 1);
		var compiled = planner.compile(withAccess(oneTransferTimetable(), verifiedTransferAccess()));

		assertThat(planner.search(command, compiled)).isNotEmpty();
		var firstMetrics = planner.lastScanMetrics();
		assertThat(firstMetrics.expandedTransfers()).isPositive();

		assertThat(planner.search(command, compiled)).isNotEmpty();
		var repeatedMetrics = planner.lastScanMetrics();
		assertThat(repeatedMetrics.expandedTransfers()).isEqualTo(firstMetrics.expandedTransfers());
		assertThat(repeatedMetrics.workspaceIdentity()).isEqualTo(firstMetrics.workspaceIdentity());

		assertThat(planner.search(command(WEDNESDAY, 8, 50), planner.compile(disconnectedRoutesTimetable()))).hasSize(1);
		assertThat(planner.lastScanMetrics().expandedTransfers()).isZero();
	}

	@Test
	@DisplayName("route 메타데이터가 없어도 stop line fallback으로 경로를 검색한다")
	void searchesTripWithoutRouteMetadata() {
		var results = planner.search(
			command(WEDNESDAY, 8, 50),
			planner.compile(missingRouteMetadataTimetable())
		);

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("lineId", "lineName")
			.containsExactly(org.assertj.core.groups.Tuple.tuple("line-fallback", "line-fallback"));
	}

	@Test
	@DisplayName("중간 stop에서 추월한 trip도 해당 stop 출발 시각 순서로 이진 탐색한다")
	void binarySearchesTripsInDepartureOrderAtEachStop() {
		var command = new SearchRouteV2Command(
			"station-b",
			"station-c",
			OffsetDateTime.parse("2026-07-01T09:10:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.ALLOW_WITH_WARNINGS,
			false,
			0,
			1
		);

		var results = planner.search(command, planner.compile(overtakingTimetable()));

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("tripId", "plannedDepartureTime")
			.containsExactly(org.assertj.core.groups.Tuple.tuple(
				"trip-fast", "2026-07-01T09:20:00+09:00"));
	}

	@Test
	@DisplayName("출발 stop에서 함께 탑승 가능한 추월 trip의 더 빠른 downstream 도착을 보존한다")
	void preservesFasterDownstreamArrivalFromOvertakingTrip() {
		var results = planner.search(
			command("station-a", "station-c", "2026-07-01T08:50:00+09:00"),
			planner.compile(overtakingTimetable())
		);

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("tripId", "plannedArrivalTime")
			.containsExactly(org.assertj.core.groups.Tuple.tuple(
				"trip-fast", "2026-07-01T09:35:00+09:00"));
	}

	@Test
	@DisplayName("동률 도착은 기존 exhaustive 전역 trip-id 순서를 유지한다")
	void preservesLegacyTripOrderForEqualArrival() {
		var results = planner.search(
			command("station-a", "station-c", "2026-07-01T08:50:00+09:00"),
			planner.compile(equalArrivalOvertakingTimetable())
		);

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("tripId", "plannedArrivalTime")
			.containsExactly(org.assertj.core.groups.Tuple.tuple(
				"a-fast", "2026-07-01T09:35:00+09:00"));
	}

	@Test
	@DisplayName("후행 warning-free 비지배 label을 보존한다")
	void preservesLaterWarningFreeLabel() throws Exception {
		Class<?> workspaceType = Class.forName(
			"com.easysubway.route.application.service.RouteTimetableRaptorPlanner$ScanWorkspace");
		var constructor = workspaceType.getDeclaredConstructor();
		constructor.setAccessible(true);
		Object workspace = constructor.newInstance();
		var prepare = workspaceType.getDeclaredMethod("prepare", int.class, int.class, int.class);
		prepare.setAccessible(true);
		prepare.invoke(workspace, 1, 1, 0);
		var relax = workspaceType.getDeclaredMethod(
			"relax", int.class, int.class, int.class, int.class, int.class,
			int.class, int.class, int.class, int.class, byte.class);
		relax.setAccessible(true);
		relax.invoke(workspace, 0, 1, 0, 100, 0, 0, 1, 0, 0, (byte) 1);
		relax.invoke(workspace, 0, 1, 0, 110, 1, 0, 1, 0, 0, (byte) 0);
		var arrivals = workspaceType.getDeclaredField("arrivalSeconds");
		arrivals.setAccessible(true);
		assertThat((int[]) arrivals.get(workspace)).contains(100, 110);
	}
	@Test
	@DisplayName("후행 trip이 downstream에서 합류해도 기존 trip-id 동률 순서를 유지한다")
	void preservesLegacyTripOrderWhenLaterTripMergesDownstream() {
		var results = planner.search(
			command("station-a", "station-c", "2026-07-01T08:50:00+09:00"),
			planner.compile(mergingTimetable())
		);

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("tripId", "plannedArrivalTime")
			.containsExactly(org.assertj.core.groups.Tuple.tuple(
				"a-late", "2026-07-01T09:20:00+09:00"));
	}

	@Test
	@DisplayName("같은 trip의 후속 stop에 더 일찍 도착한 predecessor로 환승 위치를 갱신한다")
	void preservesEarlierPredecessorAtLaterBoardingStop() {
		var command = new SearchRouteV2Command(
			"station-a",
			"station-d",
			OffsetDateTime.parse("2026-07-01T08:50:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.ALLOW_WITH_WARNINGS,
			false,
			1,
			1
		);

		var results = planner.search(command, planner.compile(laterBoardingPredecessorTimetable()));

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("tripId")
			.containsExactly("feeder-e", "connector");
	}

	@Test
	@DisplayName("후속 stop의 동일 출발 trip이 더 빨리 도착하면 해당 trip으로 전환한다")
	void switchesToFasterTripWithSameDepartureAtLaterStop() {
		var command = new SearchRouteV2Command(
			"station-origin",
			"station-d",
			OffsetDateTime.parse("2026-07-01T08:40:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.ALLOW_WITH_WARNINGS,
			false,
			1,
			1
		);

		var results = planner.search(command, planner.compile(sameDepartureDownstreamTimetable()));

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("tripId")
			.containsExactly("feeder-b", "a-fast");
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("plannedArrivalTime")
			.containsExactly(
				"2026-07-01T09:05:00+09:00",
				"2026-07-01T09:25:00+09:00"
			);
	}

	@Test
	@DisplayName("후속 stop의 동일 출발 후보가 더 늦게 도착하면 현재 trip을 유지한다")
	void keepsCurrentTripWhenSameDepartureCandidateArrivesLater() {
		var command = new SearchRouteV2Command(
			"station-origin",
			"station-d",
			OffsetDateTime.parse("2026-07-01T08:40:00+09:00"),
			MobilityType.SENIOR,
			ConstraintMode.ALLOW_WITH_WARNINGS,
			false,
			1,
			1
		);

		var results = planner.search(command, planner.compile(sameDepartureSlowerCandidateTimetable()));

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("tripId")
			.containsExactly("feeder-b", "z-fast");
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("plannedArrivalTime")
			.containsExactly(
				"2026-07-01T09:00:00+09:00",
				"2026-07-01T09:35:00+09:00"
			);
	}

	@Test
	@DisplayName("출발 stop의 동일 출발 trip은 non-overtaking pattern 우위 순서를 유지한다")
	void preservesDominantPatternOrderForSameDepartureAtOrigin() {
		var results = planner.search(
			command("station-b", "station-d", "2026-07-01T09:20:00+09:00"),
			planner.compile(sameDepartureSlowerCandidateTimetable())
		);

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("tripId", "plannedArrivalTime")
			.containsExactly(org.assertj.core.groups.Tuple.tuple(
				"z-fast", "2026-07-01T09:35:00+09:00"));
	}

	@Test
	@DisplayName("같은 정차열에서 하차 정책이 다른 후속 trip을 잃지 않는다")
	void preservesLaterTripWhenEarlierTripBlocksDropOff() {
		var results = planner.search(
			command("station-a", "station-b", "2026-07-01T08:50:00+09:00"),
			planner.compile(dropOffVariantTimetable())
		);

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().steps())
			.filteredOn(step -> "ride".equals(step.stepType()))
			.extracting("tripId")
			.containsExactly("trip-usable");
	}

	@Test
	@DisplayName("동시 nextServiceTime은 service day boarding index를 안전하게 lazy publish한다")
	void concurrentNextServiceTimeLazilyPublishesBoardingIndex() throws Exception {
		var compiled = planner.compile(frequencyTimetable());
		var activeDay = compiled.activeServiceDay(WEDNESDAY);
		var expected = OffsetDateTime.parse("2026-07-01T09:00:00+09:00");
		var worker = new AtomicReference<Thread>();
		var started = new CountDownLatch(1);

		assertThat(activeDay.boardingIndexInitialized()).isFalse();
		try (var executor = Executors.newSingleThreadExecutor()) {
			java.util.concurrent.Future<java.util.Optional<OffsetDateTime>> attempt;
			synchronized (activeDay) {
				attempt = executor.submit(() -> {
					worker.set(Thread.currentThread());
					started.countDown();
					return planner.nextServiceTime(command(WEDNESDAY, 8, 0), compiled);
				});
				assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
				assertThreadBlocked(worker.get());
				assertThat(planner.nextServiceTime(command(WEDNESDAY, 8, 0), compiled)).contains(expected);
				assertThat(activeDay.boardingIndexInitialized()).isTrue();
			}
			assertThat(attempt.get(5, TimeUnit.SECONDS)).contains(expected);
		}
		assertThat(activeDay.boardingIndexInitialized()).isTrue();
		assertThat(planner.nextServiceTime(command(WEDNESDAY, 8, 0), compiled)).contains(expected);
	}

	private static void assertThreadBlocked(Thread thread) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
		assertThat(thread.getState()).isEqualTo(Thread.State.BLOCKED);
	}

	@Test
	@DisplayName("active service day cache는 access-order 최근 8일만 유지한다")
	void evictsLeastRecentlyUsedServiceDayAfterEightEntries() {
		var compiled = planner.compile(everyDayTimetable());
		for (int offset = 0; offset < 9; offset += 1) {
			compiled.activeServiceDay(WEDNESDAY.plusDays(offset));
		}

		assertThat(compiled.activeServiceDayCacheSize()).isEqualTo(8);
		assertThat(compiled.isServiceDayCached(WEDNESDAY)).isFalse();
		compiled.activeServiceDay(WEDNESDAY.plusDays(1));
		compiled.activeServiceDay(WEDNESDAY.plusDays(9));
		assertThat(compiled.isServiceDayCached(WEDNESDAY.plusDays(1))).isTrue();
		assertThat(compiled.isServiceDayCached(WEDNESDAY.plusDays(2))).isFalse();
	}

	@Test
	@DisplayName("평일·주말과 calendar exception add/remove semantics를 보존한다")
	void preservesCalendarAndExceptionSemantics() {
		var compiled = planner.compile(calendarExceptionTimetable());

		assertThat(compiled.activeTripCount(WEDNESDAY)).isOne();
		assertThat(compiled.activeTripCount(WEDNESDAY.plusDays(1))).isZero();
		assertThat(compiled.activeTripCount(WEDNESDAY.plusDays(2))).isOne();
		assertThat(compiled.activeTripCount(WEDNESDAY.plusDays(4))).isOne();
	}

	private static RouteTimetable frequencyTimetable() {
		return timetable(
			List.of(weekday("weekday")),
			List.of(),
			List.of(new LoadRouteTimetablePort.TransitTrip(
				"trip-frequency", "route", "weekday", "도착", "0", "LOCAL", 0)),
			List.of(
				stop("trip-frequency", 1, "station-a", 32400),
				stop("trip-frequency", 2, "station-b", 33300)
			),
			List.of(new LoadRouteTimetablePort.TransitFrequency(
				"trip-frequency", 32400, 34200, 600, false))
		);
	}

	private static RouteTimetable branchAndShortTurnTimetable() {
		return timetable(
			List.of(weekday("weekday")),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"trip-full", "route", "weekday", "도착", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"trip-branch", "route", "weekday", "분기", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"trip-short", "route", "weekday", "단축", "0", "LOCAL", 0)
			),
			List.of(
				stop("trip-full", 1, "station-a", 32400),
				stop("trip-full", 2, "station-b", 32500),
				stop("trip-full", 3, "station-c", 32600),
				stop("trip-full", 4, "station-d", 32700),
				stop("trip-branch", 1, "station-a", 33000),
				stop("trip-branch", 2, "station-b", 33100),
				stop("trip-branch", 3, "station-e", 33200),
				stop("trip-short", 1, "station-a", 33600),
				stop("trip-short", 2, "station-b", 33700),
				stop("trip-short", 3, "station-c", 33800)
			),
			List.of(new LoadRouteTimetablePort.TransitFrequency(
				"trip-full", 32400, 33600, 600, false))
		);
	}

	private static RouteTimetable everyDayTimetable() {
		return timetable(
			List.of(new LoadRouteTimetablePort.ServiceCalendar(
				"daily", true, true, true, true, true, true, true,
				WEDNESDAY, WEDNESDAY.plusDays(30), "Asia/Seoul")),
			List.of(),
			List.of(new LoadRouteTimetablePort.TransitTrip(
				"trip-daily", "route", "daily", "도착", "0", "LOCAL", 0)),
			List.of(stop("trip-daily", 1, "station-a", 32400), stop("trip-daily", 2, "station-b", 33000)),
			List.of()
		);
	}

	private static RouteTimetable lineChangingTimetable() {
		var timetable = everyDayTimetable();
		var first = timetable.transitStopTimes().getFirst();
		var second = timetable.transitStopTimes().getLast();
		return new RouteTimetable(timetable.serviceCalendars(), timetable.serviceCalendarDates(),
			timetable.transitRoutes(), timetable.transitTrips(), List.of(first, new LoadRouteTimetablePort.TransitStopTime(
				second.tripId(), second.stopSequence(), second.stationId(), "line-b", second.arrivalSeconds(),
				second.departureSeconds(), second.pickupType(), second.dropOffType())), timetable.transitFrequencies());
	}
	private static RouteTimetable withAccess(
		RouteTimetable timetable,
		LoadRouteTimetablePort.RouteAccessData accessData
	) {
		return new RouteTimetable(
			timetable.serviceCalendars(),
			timetable.serviceCalendarDates(),
			timetable.transitRoutes(),
			timetable.transitTrips(),
			timetable.transitStopTimes(),
			timetable.transitFrequencies(),
			timetable.officialFares(),
			timetable.feedEndDate(),
			accessData
		);
	}
	private static LoadRouteTimetablePort.RouteAccessData entryAccess(
		String verificationStatus,
		String provenanceKind,
		boolean strictEligible,
		boolean includesStairs
	) {
		return entryAccess(verificationStatus, provenanceKind, strictEligible, includesStairs, "AVAILABLE", 100);
	}
	private static LoadRouteTimetablePort.RouteAccessData entryAccess(
		String verificationStatus, String provenanceKind, boolean strictEligible, boolean includesStairs,
		String accessibilityStatus, int reliabilityScore
	) {
		var nodes = List.of(
			new LoadRouteTimetablePort.PathwayNode("entry", "station-a", "line", "ENTRANCE"),
			new LoadRouteTimetablePort.PathwayNode("platform", "station-a", "line", "PLATFORM")
		);
		var edges = List.of(new LoadRouteTimetablePort.PathwayEdge(
			"entry-edge", "entry", "platform", 90, 60, false, includesStairs, reliabilityScore,
			accessibilityStatus, provenanceKind == null ? "UNKNOWN" : provenanceKind,
			verificationStatus == null ? "UNKNOWN" : verificationStatus
		));
		var evidence = verificationStatus == null ? List.<LoadRouteTimetablePort.RouteEdgeEvidence>of() : List.of(
			new LoadRouteTimetablePort.RouteEdgeEvidence(
				"entry-evidence", "station-a", "line", "entry-edge", "ENTRY",
				provenanceKind, verificationStatus, strictEligible, strictEligible ? null : "UNVERIFIED"
			)
		);
		return new LoadRouteTimetablePort.RouteAccessData(nodes, edges, List.of(), evidence);
	}
	private static LoadRouteTimetablePort.RouteAccessData entryAccessWithStatuses(
		String edgeVerificationStatus,
		String evidenceVerificationStatus
	) {
		var edge = new LoadRouteTimetablePort.PathwayEdge(
			"entry-edge", "entry", "platform", 90, 60, false, false, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", edgeVerificationStatus);
		var evidence = new LoadRouteTimetablePort.RouteEdgeEvidence(
			"entry-evidence", "station-a", "line", "entry-edge", "ENTRY",
			"OFFICIAL_SOURCE", evidenceVerificationStatus, false, "UNVERIFIED");
		return new LoadRouteTimetablePort.RouteAccessData(
			List.of(new LoadRouteTimetablePort.PathwayNode("entry", "station-a", null, "ENTRANCE"),
				new LoadRouteTimetablePort.PathwayNode("platform", "station-a", "line", "PLATFORM")),
			List.of(edge), List.of(), List.of(evidence));
	}
	private static LoadRouteTimetablePort.RouteAccessData verifiedDirectAccess() {
		var edges = List.of(
			new LoadRouteTimetablePort.PathwayEdge(
				"entry-generated", "entrance", "platform-a", 30, 20, false, false, 50,
				"UNKNOWN", "GENERATED", "GENERATED"),
			new LoadRouteTimetablePort.PathwayEdge(
				"entry-verified", "entrance", "platform-a", 120, 70, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"),
			new LoadRouteTimetablePort.PathwayEdge(
				"exit-verified", "platform-b", "exit", 75, 40, false, false, 100,
				"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED")
		);
		var evidence = List.of(
			new LoadRouteTimetablePort.RouteEdgeEvidence(
				"entry-generated-evidence", "station-a", "line", "entry-generated", "ENTRY",
				"GENERATED", "GENERATED", false, "GENERATED"),
			new LoadRouteTimetablePort.RouteEdgeEvidence(
				"entry-verified-evidence", "station-a", "line", "entry-verified", "ENTRY",
				"OFFICIAL_SOURCE", "VERIFIED", true, null),
			new LoadRouteTimetablePort.RouteEdgeEvidence(
				"exit-verified-evidence", "station-b", "line-b", "exit-verified", "EXIT",
				"OFFICIAL_SOURCE", "VERIFIED", true, null)
		);
		return new LoadRouteTimetablePort.RouteAccessData(List.of(
			new LoadRouteTimetablePort.PathwayNode("entrance", "station-a", null, "ENTRANCE"),
			new LoadRouteTimetablePort.PathwayNode("platform-a", "station-a", "line", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("platform-b", "station-b", "line-b", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit", "station-b", null, "EXIT")
		), edges, List.of(), evidence);
	}
	private static LoadRouteTimetablePort.RouteAccessData verifiedTransferAccess() {
		var edge = new LoadRouteTimetablePort.PathwayEdge(
			"transfer-edge", "platform-1", "platform-2", 60, 25, false, false, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED");
		var rule = new LoadRouteTimetablePort.TransferRule(
			"transfer-rule", "station-x", "line-1", "station-x", "line-2", "IN_STATION",
			60, "transfer-edge", "transfer-edge", "VERIFIED");
		var evidence = new LoadRouteTimetablePort.RouteEdgeEvidence(
			"transfer-evidence", "station-x", "line-2", "transfer-edge", "TRANSFER",
			"OFFICIAL_SOURCE", "VERIFIED", true, null);
		return new LoadRouteTimetablePort.RouteAccessData(
			List.of(new LoadRouteTimetablePort.PathwayNode("platform-1", "station-x", "line-1", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("platform-2", "station-x", "line-2", "PLATFORM")),
			List.of(edge), List.of(rule), List.of(evidence));
	}
	private static LoadRouteTimetablePort.RouteAccessData ambiguousTransferEvidenceAccess() {
		var edge = new LoadRouteTimetablePort.PathwayEdge(
			"shared-edge", "platform-1", "platform-2", 60, 25, false, false, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED");
		var rule = new LoadRouteTimetablePort.TransferRule(
			"transfer-rule", "station-x", "line-1", "station-x", "line-2", "IN_STATION",
			60, "shared-edge", "shared-edge", "VERIFIED");
		var unrelatedEntry = new LoadRouteTimetablePort.RouteEdgeEvidence(
			"a-entry-evidence", "station-x", "line-2", "shared-edge", "ENTRY",
			"OFFICIAL_SOURCE", "VERIFIED", true, null);
		var blockedTransfer = new LoadRouteTimetablePort.RouteEdgeEvidence(
			"z-transfer-evidence", "station-x", "line-2", "shared-edge", "TRANSFER",
			"OFFICIAL_SOURCE", "VERIFIED", false, "TRANSFER_NOT_STRICT_ELIGIBLE");
		return new LoadRouteTimetablePort.RouteAccessData(
			List.of(new LoadRouteTimetablePort.PathwayNode("platform-1", "station-x", "line-1", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("platform-2", "station-x", "line-2", "PLATFORM")),
			List.of(edge), List.of(rule), List.of(unrelatedEntry, blockedTransfer));
	}
	private static LoadRouteTimetablePort.RouteAccessData incomingLineDominanceAccess() {
		var edges = List.of(
			accessEdge("entry-a"),
			accessEdge("entry-b"),
			accessEdge("transfer-b-c"),
			accessEdge("exit-c")
		);
		var rule = new LoadRouteTimetablePort.TransferRule(
			"transfer-b-c-rule", "station-x", "line-b", "station-x", "line-c", "IN_STATION",
			60, "transfer-b-c", "transfer-b-c", "VERIFIED");
		var evidence = List.of(
			accessEvidence("entry-a-evidence", "station-origin", "line-a", "entry-a", "ENTRY"),
			accessEvidence("entry-b-evidence", "station-origin", "line-b", "entry-b", "ENTRY"),
			accessEvidence("transfer-b-c-evidence", "station-x", "line-c", "transfer-b-c", "TRANSFER"),
			accessEvidence("exit-c-evidence", "station-destination", "line-c", "exit-c", "EXIT")
		);
		return new LoadRouteTimetablePort.RouteAccessData(List.of(
			new LoadRouteTimetablePort.PathwayNode("entry-a-from", "station-origin", null, "ENTRANCE"),
			new LoadRouteTimetablePort.PathwayNode("entry-a-to", "station-origin", "line-a", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("entry-b-from", "station-origin", null, "ENTRANCE"),
			new LoadRouteTimetablePort.PathwayNode("entry-b-to", "station-origin", "line-b", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("transfer-b-c-from", "station-x", "line-b", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("transfer-b-c-to", "station-x", "line-c", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-c-from", "station-destination", "line-c", "PLATFORM"),
			new LoadRouteTimetablePort.PathwayNode("exit-c-to", "station-destination", null, "EXIT")
		), edges, List.of(rule), evidence);
	}
	private static LoadRouteTimetablePort.PathwayEdge accessEdge(String id) {
		return new LoadRouteTimetablePort.PathwayEdge(
			id, id + "-from", id + "-to", 60, 25, false, false, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED");
	}
	private static LoadRouteTimetablePort.RouteEdgeEvidence accessEvidence(
		String id,
		String stationId,
		String lineId,
		String edgeId,
		String edgeType
	) {
		return new LoadRouteTimetablePort.RouteEdgeEvidence(
			id, stationId, lineId, edgeId, edgeType,
			"OFFICIAL_SOURCE", "VERIFIED", true, null);
	}
	private static RouteTimetable oneTransferTimetable() {
		return new RouteTimetable(
			List.of(weekday("weekday")),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitRoute(
					"route-1", "line-1", "1", "1호선", "X 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute(
					"route-2", "line-2", "2", "2호선", "B 방면", "Asia/Seoul")
			),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"trip-1", "route-1", "weekday", "X", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"trip-2", "route-2", "weekday", "B", "0", "LOCAL", 0)
			),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-1", 1, "station-a", "line-1", 31800, 31800, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-1", 2, "station-x", "line-1", 32400, 32400, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-2", 1, "station-x", "line-2", 33000, 33000, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-2", 2, "station-b", "line-2", 33600, 33600, 0, 0)
			),
			List.of()
		);
	}
	private static RouteTimetable incomingLineDominanceTimetable() {
		return new RouteTimetable(
			List.of(weekday("weekday")),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitRoute(
					"route-a", "line-a", "A", "A호선", "X 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute(
					"route-b", "line-b", "B", "B호선", "X 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute(
					"route-c", "line-c", "C", "C호선", "도착 방면", "Asia/Seoul")
			),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"trip-a", "route-a", "weekday", "X", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"trip-b", "route-b", "weekday", "X", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"trip-c", "route-c", "weekday", "도착", "0", "LOCAL", 0)
			),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-a", 1, "station-origin", "line-a", 33000, 33000, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-a", 2, "station-x", "line-a", 33000, 33000, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-b", 1, "station-origin", "line-b", 33060, 33060, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-b", 2, "station-x", "line-b", 33660, 33660, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-c", 1, "station-x", "line-c", 34200, 34200, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-c", 2, "station-destination", "line-c", 34800, 34800, 0, 0)
			),
			List.of()
		);
	}
	private static RouteTimetable calendarExceptionTimetable() {
		return timetable(
			List.of(weekday("weekday")),
			List.of(
				new LoadRouteTimetablePort.ServiceCalendarDate("weekday", WEDNESDAY.plusDays(1), 2),
				new LoadRouteTimetablePort.ServiceCalendarDate("special", WEDNESDAY.plusDays(4), 1)
			),
			List.of(
				new LoadRouteTimetablePort.TransitTrip("trip-weekday", "route", "weekday", "도착", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip("trip-special", "route", "special", "도착", "0", "LOCAL", 0)
			),
			List.of(
				stop("trip-weekday", 1, "station-a", 32400), stop("trip-weekday", 2, "station-b", 33000),
				stop("trip-special", 1, "station-a", 32400), stop("trip-special", 2, "station-b", 33000)
			),
			List.of()
		);
	}

	private static RouteTimetable disconnectedRoutesTimetable() {
		return new RouteTimetable(
			List.of(weekday("weekday")),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitRoute(
					"route-main", "line-main", "M", "주 경로", "B 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute(
					"route-other", "line-other", "O", "무관 경로", "Y 방면", "Asia/Seoul")
			),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"trip-main", "route-main", "weekday", "B", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"trip-other", "route-other", "weekday", "Y", "0", "LOCAL", 0)
			),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-main", 1, "station-a", "line-main", 32400, 32400, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-main", 2, "station-b", "line-main", 33000, 33000, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-other", 1, "station-x", "line-other", 32400, 32400, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-other", 2, "station-y", "line-other", 33000, 33000, 0, 0)
			),
			List.of()
		);
	}

	private static RouteTimetable missingRouteMetadataTimetable() {
		return new RouteTimetable(
			List.of(weekday("weekday")),
			List.of(),
			List.of(),
			List.of(new LoadRouteTimetablePort.TransitTrip(
				"trip-missing-route", "route-missing", "weekday", "B", "0", "LOCAL", 0)),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-missing-route", 1, "station-a", "line-fallback", 32400, 32400, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-missing-route", 2, "station-b", "line-fallback", 33000, 33000, 0, 0)
			),
			List.of()
		);
	}

	private static RouteTimetable overtakingTimetable() {
		return timetable(
			List.of(weekday("weekday")),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"trip-slow", "route", "weekday", "도착", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"trip-fast", "route", "weekday", "도착", "0", "EXPRESS", 0)
			),
			List.of(
				stop("trip-slow", 1, "station-a", 32400),
				stop("trip-slow", 2, "station-b", 34200),
				stop("trip-slow", 3, "station-c", 34800),
				stop("trip-fast", 1, "station-a", 33000),
				stop("trip-fast", 2, "station-b", 33600),
				stop("trip-fast", 3, "station-c", 34500)
			),
			List.of()
		);
	}

	private static RouteTimetable dropOffVariantTimetable() {
		return timetable(
			List.of(weekday("weekday")),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"trip-blocked", "route", "weekday", "도착", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"trip-usable", "route", "weekday", "도착", "0", "LOCAL", 0)
			),
			List.of(
				stop("trip-blocked", 1, "station-a", 32400),
				new LoadRouteTimetablePort.TransitStopTime(
					"trip-blocked", 2, "station-b", "line", 33000, 33000, 0, 1),
				stop("trip-usable", 1, "station-a", 32700),
				stop("trip-usable", 2, "station-b", 33300)
			),
			List.of()
		);
	}

	private static RouteTimetable equalArrivalOvertakingTimetable() {
		return timetable(
			List.of(weekday("weekday")),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"z-slow", "route", "weekday", "도착", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"a-fast", "route", "weekday", "도착", "0", "EXPRESS", 0)
			),
			List.of(
				stop("z-slow", 1, "station-a", 32400),
				stop("z-slow", 2, "station-b", 33600),
				stop("z-slow", 3, "station-c", 34500),
				stop("a-fast", 1, "station-a", 33000),
				stop("a-fast", 2, "station-b", 33300),
				stop("a-fast", 3, "station-c", 34500)
			),
			List.of()
		);
	}

	private static RouteTimetable mergingTimetable() {
		return timetable(
			List.of(weekday("weekday")),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"z-early", "route", "weekday", "도착", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"a-late", "route", "weekday", "도착", "0", "LOCAL", 0)
			),
			List.of(
				stop("z-early", 1, "station-a", 32400),
				stop("z-early", 2, "station-b", 33000),
				stop("z-early", 3, "station-c", 33600),
				stop("a-late", 1, "station-a", 32700),
				stop("a-late", 2, "station-b", 33000),
				stop("a-late", 3, "station-c", 33600)
			),
			List.of()
		);
	}

	private static RouteTimetable laterBoardingPredecessorTimetable() {
		return new RouteTimetable(
			List.of(weekday("weekday")),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitRoute(
					"route-b", "line-b", "B", "B 경유", "B 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute(
					"route-e", "line-e", "E", "E 경유", "E 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute(
					"route-connector", "line-c", "C", "연결", "D 방면", "Asia/Seoul")
			),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"connector", "route-connector", "weekday", "D", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"feeder-b", "route-b", "weekday", "B", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"feeder-e", "route-e", "weekday", "E", "0", "LOCAL", 0)
			),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime(
					"connector", 1, "station-b", "line-c", 33600, 33600, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"connector", 2, "station-e", "line-c", 33900, 33900, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"connector", 3, "station-d", "line-c", 34200, 34200, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"feeder-b", 1, "station-a", "line-b", 32400, 32400, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"feeder-b", 2, "station-b", "line-b", 32700, 32700, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"feeder-e", 1, "station-a", "line-e", 32520, 32520, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"feeder-e", 2, "station-e", "line-e", 32640, 32640, 0, 0)
			),
			List.of()
		);
	}

	private static RouteTimetable sameDepartureDownstreamTimetable() {
		return new RouteTimetable(
			List.of(weekday("weekday")),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitRoute(
					"route-feeder-a", "line-fa", "FA", "A 연결", "A 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute(
					"route-feeder-b", "line-fb", "FB", "B 연결", "B 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute(
					"route-main", "line-main", "M", "본선", "D 방면", "Asia/Seoul")
			),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"a-fast", "route-main", "weekday", "D", "0", "EXPRESS", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"feeder-a", "route-feeder-a", "weekday", "A", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"feeder-b", "route-feeder-b", "weekday", "B", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"z-slow", "route-main", "weekday", "D", "0", "LOCAL", 0)
			),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime(
					"a-fast", 1, "station-a", "line-main", 32100, 32100, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"a-fast", 2, "station-b", "line-main", 33600, 33600, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"a-fast", 3, "station-d", "line-main", 33900, 33900, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"feeder-a", 1, "station-origin", "line-fa", 31620, 31620, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"feeder-a", 2, "station-a", "line-fa", 32400, 32400, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"feeder-b", 1, "station-origin", "line-fb", 31680, 31680, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"feeder-b", 2, "station-b", "line-fb", 32700, 32700, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"z-slow", 1, "station-a", "line-main", 33000, 33000, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"z-slow", 2, "station-b", "line-main", 33600, 33600, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"z-slow", 3, "station-d", "line-main", 34200, 34200, 0, 0)
			),
			List.of()
		);
	}

	private static RouteTimetable sameDepartureSlowerCandidateTimetable() {
		return new RouteTimetable(
			List.of(weekday("weekday")),
			List.of(),
			List.of(
				new LoadRouteTimetablePort.TransitRoute(
					"route-feeder-a", "line-fa", "FA", "A 연결", "A 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute(
					"route-feeder-b", "line-fb", "FB", "B 연결", "B 방면", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute(
					"route-main", "line-main", "M", "본선", "D 방면", "Asia/Seoul")
			),
			List.of(
				new LoadRouteTimetablePort.TransitTrip(
					"a-slow", "route-main", "weekday", "D", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"feeder-a", "route-feeder-a", "weekday", "A", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"feeder-b", "route-feeder-b", "weekday", "B", "0", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip(
					"z-fast", "route-main", "weekday", "D", "0", "EXPRESS", 0)
			),
			List.of(
				new LoadRouteTimetablePort.TransitStopTime(
					"a-slow", 1, "station-a", "line-main", 33600, 33600, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"a-slow", 2, "station-b", "line-main", 33900, 34200, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"a-slow", 3, "station-d", "line-main", 34800, 34800, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"feeder-a", 1, "station-origin", "line-fa", 31620, 31620, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"feeder-a", 2, "station-a", "line-fa", 32700, 32700, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"feeder-b", 1, "station-origin", "line-fb", 31680, 31680, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"feeder-b", 2, "station-b", "line-fb", 32400, 32400, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"z-fast", 1, "station-a", "line-main", 33300, 33300, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"z-fast", 2, "station-b", "line-main", 33600, 34200, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime(
					"z-fast", 3, "station-d", "line-main", 34500, 34500, 0, 0)
			),
			List.of()
		);
	}

	private static LoadRouteTimetablePort.ServiceCalendar weekday(String serviceId) {
		return new LoadRouteTimetablePort.ServiceCalendar(
			serviceId, true, true, true, true, true, false, false,
			WEDNESDAY, WEDNESDAY.plusDays(30), "Asia/Seoul");
	}

	private static RouteTimetable timetable(
		List<LoadRouteTimetablePort.ServiceCalendar> calendars,
		List<LoadRouteTimetablePort.ServiceCalendarDate> exceptions,
		List<LoadRouteTimetablePort.TransitTrip> trips,
		List<LoadRouteTimetablePort.TransitStopTime> stopTimes,
		List<LoadRouteTimetablePort.TransitFrequency> frequencies
	) {
		return new RouteTimetable(
			calendars,
			exceptions,
			List.of(new LoadRouteTimetablePort.TransitRoute(
				"route", "line", "L", "테스트", "도착 방면", "Asia/Seoul")),
			trips,
			stopTimes,
			frequencies
		);
	}

	private static LoadRouteTimetablePort.TransitStopTime stop(
		String tripId,
		int sequence,
		String stationId,
		int seconds
	) {
		return new LoadRouteTimetablePort.TransitStopTime(
			tripId, sequence, stationId, "line", seconds, seconds, 0, 0);
	}

	private static SearchRouteV2Command command(LocalDate date, int hour, int minute) {
		return new SearchRouteV2Command(
			"station-a",
			"station-b",
			OffsetDateTime.parse("%sT%02d:%02d:00+09:00".formatted(date, hour, minute)),
			MobilityType.SENIOR,
			ConstraintMode.ALLOW_WITH_WARNINGS,
			false,
			0,
			3
		);
	}

	private static SearchRouteV2Command command(String origin, String destination, String departureTime) {
		return new SearchRouteV2Command(
			origin,
			destination,
			OffsetDateTime.parse(departureTime),
			MobilityType.SENIOR,
			ConstraintMode.ALLOW_WITH_WARNINGS,
			false,
			0,
			1
		);
	}

	private static int[] primitiveIntArray(
		RouteTimetableRaptorPlanner.CompiledTimetable compiled,
		String fieldName
	) throws Exception {
		return (int[]) primitiveArray(compiled, fieldName);
	}

	private static byte[] primitiveByteArray(
		RouteTimetableRaptorPlanner.CompiledTimetable compiled,
		String fieldName
	) throws Exception {
		return (byte[]) primitiveArray(compiled, fieldName);
	}

	private static Object primitiveArray(
		RouteTimetableRaptorPlanner.CompiledTimetable compiled,
		String fieldName
	) throws Exception {
		var scheduledTripsField = compiled.getClass().getDeclaredField("scheduledTrips");
		scheduledTripsField.setAccessible(true);
		var scheduledTrip = ((List<?>) scheduledTripsField.get(compiled)).getFirst();
		var timesField = scheduledTrip.getClass().getDeclaredField("times");
		timesField.setAccessible(true);
		var times = timesField.get(scheduledTrip);
		var primitiveArrayField = times.getClass().getDeclaredField(fieldName);
		primitiveArrayField.setAccessible(true);
		return primitiveArrayField.get(times);
	}
}
