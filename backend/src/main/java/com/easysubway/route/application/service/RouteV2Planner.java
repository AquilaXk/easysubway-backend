package com.easysubway.route.application.service;

import com.easysubway.common.error.InvalidRequestException;
import com.easysubway.route.application.model.PlannerIdentity;
import com.easysubway.route.application.port.in.RouteSearchUseCase;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableCandidateSource;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeQuery;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdate;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdates;
import com.easysubway.route.application.port.in.SearchRouteCommand;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.SearchRouteV2Command;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteV2Plan;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteV2PlanSource;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteV2Status;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteObjective;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteTransportScope;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.CompiledTimetable;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.RealtimeOverlay;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.EtaSource;
import com.easysubway.route.domain.ProfileWalkTimeCalculator;
import com.easysubway.route.domain.RouteNotFoundException;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteStep;
import com.easysubway.route.domain.StairAccess;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RouteV2Planner implements RouteV2SearchUseCase {

	private static final Logger log = LoggerFactory.getLogger(RouteV2Planner.class);
	private static final String PLANNER_ADR = "tools/routes/route-algorithm-v2-adr.json";
	private static final int RANKING_CANDIDATE_LIMIT = 3;
	// #2560: PREFER_STEP_FREE에서 objective 대표와 별개로 보존하는 무단차 대안의 objective tag다.
	// RouteObjective(요청 스키마)는 그대로 두고 응답 태그 어휘만 넓힌다 — 요청으로 지정할 수 있는
	// objective가 아니라 FASTEST·FEWEST_TRANSFERS와 같은 "대표" 표시이며, "무단차 선호가 고른
	// 후보"를 뜻한다. 접근성 검증 여부까지 단언하지 않는다(경로의 검증 수준은 기존대로 warnings·
	// stairAccessState·requiresAccessibilityCheck가 전달한다). 미지 태그를 무시하는 클라이언트는
	// 기존 동작 그대로다.
	private static final String STEP_FREE_OBJECTIVE_TAG = "STEP_FREE_PREFERRED";

	private final RouteSearchUseCase routeSearchUseCase;
	private final LoadRouteTimetablePort routeTimetablePort;
	private final RouteTimetableRaptorPlanner timetableRaptorPlanner = new RouteTimetableRaptorPlanner();
	private final boolean timetableRequired;
	private final boolean legacyPersistenceAllowed;
	private final Counter timetableCacheHit;
	private final Counter timetableCacheMiss;
	private final AtomicBoolean timetableCacheHitLogged = new AtomicBoolean();
	private final AtomicBoolean timetableCacheMissLogged = new AtomicBoolean();
	private volatile TimetableSnapshot cachedTimetableSnapshot;
	private volatile RealtimeSnapshot cachedRealtimeSnapshot = RealtimeSnapshot.empty();

	public RouteV2Planner(RouteSearchUseCase routeSearchUseCase) {
		this(routeSearchUseCase, RouteTimetable::empty, false, true, new SimpleMeterRegistry());
	}

	@Autowired
	public RouteV2Planner(
		RouteSearchUseCase routeSearchUseCase,
		ObjectProvider<LoadRouteTimetablePort> routeTimetablePortProvider,
		Environment environment,
		MeterRegistry meterRegistry
	) {
		this(
			routeSearchUseCase,
			routeTimetablePortProvider.getIfAvailable(),
			true,
			!environment.acceptsProfiles(Profiles.of("prod", "staging", "release", "prod-like")),
			meterRegistry
		);
	}

	RouteV2Planner(RouteSearchUseCase routeSearchUseCase, LoadRouteTimetablePort routeTimetablePort) {
		this(routeSearchUseCase, routeTimetablePort, true, false, new SimpleMeterRegistry());
	}

	RouteV2Planner(
		RouteSearchUseCase routeSearchUseCase,
		LoadRouteTimetablePort routeTimetablePort,
		MeterRegistry meterRegistry
	) {
		this(routeSearchUseCase, routeTimetablePort, true, false, meterRegistry);
	}

	private RouteV2Planner(
		RouteSearchUseCase routeSearchUseCase,
		LoadRouteTimetablePort routeTimetablePort,
		boolean timetableRequired,
		boolean legacyPersistenceAllowed,
		MeterRegistry meterRegistry
	) {
		this.routeSearchUseCase = routeSearchUseCase;
		this.routeTimetablePort = routeTimetablePort == null ? RouteTimetable::empty : routeTimetablePort;
		this.timetableRequired = timetableRequired && routeTimetablePort != null;
		this.legacyPersistenceAllowed = legacyPersistenceAllowed;
		this.timetableCacheHit = cacheCounter(meterRegistry, "hit");
		this.timetableCacheMiss = cacheCounter(meterRegistry, "miss");
	}

	@Override
	public RouteV2Plan search(SearchRouteV2Command command) {
		try {
			if (command.transportScope() != RouteTransportScope.SUBWAY_AND_ITX_CHEONGCHUN) {
				throw new InvalidRequestException("authenticated Route V2는 지하철·ITX-청춘 통합 검색만 지원합니다.");
			}
			TimetableSnapshot snapshot = timetableRequired
				&& routeTimetablePort.hasRouteTimetable()
				? timetableSnapshot()
				: null;
			if (snapshot != null && timetableCovers(command, snapshot)) {
				if (timetableRaptorPlanner.isFeedStale(command, snapshot.compiledTimetable())) {
					return timetablePlan(List.of(), List.of(RouteV2Status.STALE_TIMETABLE), snapshot);
				}
				SearchRouteCommand searchRouteCommand = toSearchRouteCommand(command);
				routeSearchUseCase.validateRouteSearch(searchRouteCommand);
				RealtimeSearch realtimeSearch = realtimeSearch(command, snapshot);
				RealtimeSnapshot realtimeSnapshot = realtimeSearch.snapshot();
				RouteTimetableRaptorPlanner.SearchOutcome searchOutcome = realtimeSearch.outcome();
				boolean blockedAccessibility = searchOutcome.blockedAccessibility() != null;
				List<RouteSearchResult> timetableItineraries = blockedAccessibility
					? List.of(searchOutcome.blockedAccessibility()) : searchOutcome.itineraries();
				if (timetableItineraries.isEmpty()) {
					return noTimetableServicePlan(command, snapshot, realtimeSnapshot.overlay());
				}
				// #2095/#2286: 인증 Route V2는 SUBWAY_AND_ITX_CHEONGCHUN scope만 받고(위에서 강제)
				// prod 게이트가 TIMETABLE_RAPTOR 출처만 허용하므로, 레거시 그래프 우선 시도를
				// 건너뛰고 항상 RAPTOR(TIMETABLE_SCAN)를 쓴다. ITX pilot 역처럼 STATION_LINES로
				// 연결됐지만 접근성 시설 데이터가 없는 역은 레거시 그래프가 채택될 경우
				// timetableArtifactId가 null이 돼 prod 게이트에서 503으로 막힌다.
				var stabilizedCandidates = routeSearchUseCase.stabilizeTimetableRouteCandidatesWithSource(
					searchRouteCommand,
					RANKING_CANDIDATE_LIMIT,
					command.alternativeCount(),
					timetableItineraries,
					List::copyOf,
					false
				);
				timetableItineraries = stabilizedCandidates.itineraries();
				if (blockedAccessibility) {
					return timetablePlan(timetableItineraries, statusesOf(timetableItineraries, false), snapshot);
				}
				if (stabilizedCandidates.source() == TimetableCandidateSource.TIMETABLE_SCAN) {
					timetableItineraries = routeSearchUseCase.applyRealtimeToTimetableCandidates(
						searchRouteCommand,
						timetableItineraries
					);
				}
				timetableItineraries = rankTimetableItineraries(
					timetableItineraries,
					command.objective(),
					command.constraintMode(),
					command.alternativeCount()
				);
				return new RouteV2Plan(
					timetableItineraries,
					statusesOf(timetableItineraries, command.useRealtime()),
					PLANNER_ADR,
					null,
					stabilizedCandidates.source() == TimetableCandidateSource.TIMETABLE_SCAN
						? RouteV2PlanSource.TIMETABLE_RAPTOR
						: RouteV2PlanSource.LEGACY_GRAPH,
					stabilizedCandidates.source() == TimetableCandidateSource.TIMETABLE_SCAN
						? snapshot.timetableArtifactId()
						: null,
					stabilizedCandidates.source() == TimetableCandidateSource.TIMETABLE_SCAN
						? snapshot.plannerIdentity()
						: null
				);
			}
			rejectUnsupportedMobilityPreset(command);
			SearchRouteCommand searchRouteCommand = toSearchRouteCommand(command);
			List<RouteSearchResult> itineraries = legacyPersistenceAllowed
				? routeSearchUseCase.searchRouteAlternatives(searchRouteCommand, command.alternativeCount())
				: routeSearchUseCase.planRouteAlternatives(searchRouteCommand, command.alternativeCount());
			return new RouteV2Plan(
				itineraries,
				statusesOf(itineraries, command.useRealtime()),
				PLANNER_ADR,
				null,
				RouteV2PlanSource.LEGACY_GRAPH
			);
		} catch (RouteNotFoundException exception) {
			return new RouteV2Plan(List.of(), List.of(RouteV2Status.NO_TIMETABLE_SERVICE), PLANNER_ADR);
		}
	}

	private RouteV2Plan noTimetableServicePlan(
		SearchRouteV2Command command,
		TimetableSnapshot snapshot,
		RealtimeOverlay realtimeOverlay
	) {
		OffsetDateTime nextServiceTime = timetableRaptorPlanner.nextServiceTime(
			command,
			snapshot.compiledTimetable(),
			realtimeOverlay
		).orElse(null);
		return new RouteV2Plan(
			List.of(),
			List.of(RouteV2Status.NO_TIMETABLE_SERVICE),
			PLANNER_ADR,
			nextServiceTime,
			RouteV2PlanSource.TIMETABLE_RAPTOR,
			snapshot.timetableArtifactId(),
			snapshot.plannerIdentity()
		);
	}

	private RouteV2Plan timetablePlan(
		List<RouteSearchResult> itineraries,
		List<RouteV2Status> statuses,
		TimetableSnapshot snapshot
	) {
		return new RouteV2Plan(
			itineraries,
			statuses,
			PLANNER_ADR,
			null,
			RouteV2PlanSource.TIMETABLE_RAPTOR,
			snapshot.timetableArtifactId(),
			snapshot.plannerIdentity()
		);
	}

	private void rejectUnsupportedMobilityPreset(SearchRouteV2Command command) {
		if (command.mobilityPreset() != ProfileWalkTimeCalculator.presetFor(command.mobilityType())) {
			throw new InvalidRequestException("보행 프리셋은 RAPTOR 시간표 경로에서만 변경할 수 있습니다.");
		}
	}

	private boolean timetableCovers(SearchRouteV2Command command, TimetableSnapshot snapshot) {
		java.util.Set<String> covered = snapshot.coveredStationIds();
		return covered.contains(command.originStationId())
			&& covered.contains(command.destinationStationId());
	}

	private TimetableSnapshot timetableSnapshot() {
		String cacheKey = routeTimetablePort.timetableCacheKey();
		TimetableSnapshot snapshot = cachedTimetableSnapshot;
		if (snapshot != null && snapshot.cacheKey().equals(cacheKey)) {
			recordTimetableCache(timetableCacheHit, timetableCacheHitLogged, "hit", cacheKey);
			return snapshot;
		}
		synchronized (this) {
			cacheKey = routeTimetablePort.timetableCacheKey();
			snapshot = cachedTimetableSnapshot;
			if (snapshot == null || !snapshot.cacheKey().equals(cacheKey)) {
				recordTimetableCache(timetableCacheMiss, timetableCacheMissLogged, "miss", cacheKey);
				LoadRouteTimetablePort.RouteTimetableSnapshot loaded = routeTimetablePort.loadRouteTimetableSnapshot();
				RouteTimetable timetable = loaded.timetable();
				CompiledTimetable compiledTimetable = timetableRaptorPlanner.compile(timetable);
				TimetableSnapshot replacement = new TimetableSnapshot(
					loaded.cacheKey(),
					compiledTimetable,
					compiledTimetable.coveredStationIds(),
					loaded.timetableArtifactId(),
					loaded.plannerIdentity()
				);
				cachedTimetableSnapshot = replacement;
				return replacement;
			}
			recordTimetableCache(timetableCacheHit, timetableCacheHitLogged, "hit", cacheKey);
			return cachedTimetableSnapshot;
		}
	}

	private RealtimeSearch realtimeSearch(SearchRouteV2Command command, TimetableSnapshot timetableSnapshot) {
		var rankingCommand = rankingCommand(command);
		if (command.useRealtime()) {
			return realtimeSearch(command, rankingCommand, timetableSnapshot);
		}
		RealtimeSnapshot empty = RealtimeSnapshot.empty();
		return new RealtimeSearch(
			empty,
			timetableRaptorPlanner.searchWithDiagnostics(
				rankingCommand, timetableSnapshot.compiledTimetable(), empty.overlay())
		);
	}

	private RealtimeSearch realtimeSearch(
		SearchRouteV2Command command,
		SearchRouteV2Command rankingCommand,
		TimetableSnapshot timetableSnapshot
	) {
		List<TimetableRealtimeQuery> queried = new ArrayList<>(
			timetableRaptorPlanner.realtimeQueries(command, timetableSnapshot.compiledTimetable()));
		TimetableRealtimeUpdates updates = resolveRealtimeUpdates(queried);
		RealtimeSnapshot realtimeSnapshot = realtimeSnapshot(timetableSnapshot, updates);
		RouteTimetableRaptorPlanner.SearchOutcome searchOutcome = timetableRaptorPlanner.searchWithDiagnostics(
			rankingCommand, timetableSnapshot.compiledTimetable(), realtimeSnapshot.overlay());

		int refinementLimit = RANKING_CANDIDATE_LIMIT * (command.maxTransfers() + 1);
		for (int pass = 0; pass < refinementLimit && updates.available(); pass += 1) {
			List<TimetableRealtimeQuery> additions = timetableRaptorPlanner.realtimeQueries(
				command, timetableSnapshot.compiledTimetable(), searchOutcome.itineraries(), queried);
			if (additions.isEmpty()) {
				return new RealtimeSearch(realtimeSnapshot, searchOutcome);
			}
			queried.addAll(additions);
			updates = mergeRealtimeUpdates(updates, resolveRealtimeUpdates(additions));
			realtimeSnapshot = realtimeSnapshot(timetableSnapshot, updates);
			searchOutcome = timetableRaptorPlanner.searchWithDiagnostics(
				rankingCommand, timetableSnapshot.compiledTimetable(), realtimeSnapshot.overlay());
		}
		if (updates.available() && !timetableRaptorPlanner.realtimeQueries(
			command, timetableSnapshot.compiledTimetable(), searchOutcome.itineraries(), queried).isEmpty()) {
			updates = TimetableRealtimeUpdates.unavailable("REALTIME_REFINEMENT_LIMIT_EXCEEDED");
			realtimeSnapshot = realtimeSnapshot(timetableSnapshot, updates);
			searchOutcome = timetableRaptorPlanner.searchWithDiagnostics(
				rankingCommand, timetableSnapshot.compiledTimetable(), realtimeSnapshot.overlay());
		}
		return new RealtimeSearch(realtimeSnapshot, searchOutcome);
	}

	private TimetableRealtimeUpdates resolveRealtimeUpdates(List<TimetableRealtimeQuery> queries) {
		TimetableRealtimeUpdates updates = routeSearchUseCase.resolveTimetableRealtime(queries);
		return updates == null
			? TimetableRealtimeUpdates.unavailable("REALTIME_OVERLAY_UNAVAILABLE")
			: updates;
	}

	private RealtimeSnapshot realtimeSnapshot(
		TimetableSnapshot timetableSnapshot,
		TimetableRealtimeUpdates updates
	) {
		RealtimeOverlay overlay = timetableRaptorPlanner.compileRealtimeOverlay(
			timetableSnapshot.compiledTimetable(), updates);
		RealtimeSnapshot replacement = new RealtimeSnapshot(
			timetableSnapshot.cacheKey(), overlay.version(), overlay, updates.fallbackCode());
		// 단일 volatile 참조 교체로 스캔은 구/신 overlay 중 하나만 캡처한다.
		cachedRealtimeSnapshot = replacement;
		return replacement;
	}

	private static TimetableRealtimeUpdates mergeRealtimeUpdates(
		TimetableRealtimeUpdates current,
		TimetableRealtimeUpdates addition
	) {
		if (!current.available()) {
			return current;
		}
		if (!addition.available()) {
			return addition;
		}
		Map<String, TimetableRealtimeUpdate> updatesByTripId = new LinkedHashMap<>();
		for (TimetableRealtimeUpdate update : current.updates()) {
			updatesByTripId.put(update.tripId(), update);
		}
		for (TimetableRealtimeUpdate update : addition.updates()) {
			TimetableRealtimeUpdate previous = updatesByTripId.get(update.tripId());
			if (previous != null && (previous.cancelled() != update.cancelled()
				|| previous.arrivalDeltaSeconds() != update.arrivalDeltaSeconds()
				|| previous.departureDeltaSeconds() != update.departureDeltaSeconds())) {
				return TimetableRealtimeUpdates.unavailable("CONFLICTING_REALTIME_TRIP_UPDATE");
			}
			if (previous == null || previous.providerObservedAt().isBefore(update.providerObservedAt())) {
				updatesByTripId.put(update.tripId(), update);
			}
		}
		List<TimetableRealtimeUpdate> merged = updatesByTripId.values().stream()
			.sorted(Comparator.comparing(TimetableRealtimeUpdate::tripId))
			.toList();
		return new TimetableRealtimeUpdates(
			current.version() + "+" + addition.version(), true, merged, null);
	}

	private static Counter cacheCounter(MeterRegistry registry, String result) {
		return Counter.builder("easysubway.route.v2.timetable.cache")
			.tag("result", result)
			.register(registry);
	}

	private static void recordTimetableCache(
		Counter counter,
		AtomicBoolean firstLog,
		String result,
		String cacheKey
	) {
		counter.increment();
		if (firstLog.compareAndSet(false, true)) {
			log.info("route V2 timetable cache result={} key={}", result, cacheKey);
		}
	}

	private record TimetableSnapshot(
		String cacheKey,
		CompiledTimetable compiledTimetable,
		java.util.Set<String> coveredStationIds,
		String timetableArtifactId,
		PlannerIdentity plannerIdentity
	) {
	}

	private record RealtimeSnapshot(
		String timetableCacheKey,
		String overlayVersion,
		RealtimeOverlay overlay,
		String fallbackCode
	) {
		private static RealtimeSnapshot empty() {
			return new RealtimeSnapshot(null, null, RealtimeOverlay.empty(), null);
		}
	}

	private record RealtimeSearch(
		RealtimeSnapshot snapshot,
		RouteTimetableRaptorPlanner.SearchOutcome outcome
	) {
	}

	private SearchRouteV2Command rankingCommand(SearchRouteV2Command command) {
		return new SearchRouteV2Command(
			command.originStationId(),
			command.destinationStationId(),
			command.departureTime(),
			command.mobilityType(),
			command.mobilityPreset(),
			command.constraintMode(),
			command.useRealtime(),
			command.maxTransfers(),
			RANKING_CANDIDATE_LIMIT,
			command.transportScope(),
			command.objective()
		);
	}

	private List<RouteSearchResult> rankTimetableItineraries(
		List<RouteSearchResult> itineraries,
		RouteObjective requestedObjective,
		ConstraintMode constraintMode,
		int alternativeCount
	) {
		if (itineraries.isEmpty()) {
			return List.of();
		}
		List<RouteSearchResult> found = itineraries.stream()
			.filter(itinerary -> itinerary.status() == RouteSearchStatus.FOUND)
			.toList();
		if (found.isEmpty()) {
			return itineraries.stream().limit(alternativeCount).toList();
		}
		Comparator<RouteSearchResult> fastest = Comparator
			.comparingLong(this::plannedArrivalEpochSecond)
			.thenComparingInt(RouteSearchResult::transferCount)
			.thenComparingInt(this::accessibilityRiskScore)
			.thenComparing(RouteSearchResult::routeSearchId);
		Comparator<RouteSearchResult> fewestTransfers = Comparator
			.comparingInt(RouteSearchResult::transferCount)
			.thenComparingLong(this::plannedArrivalEpochSecond)
			.thenComparingInt(this::accessibilityRiskScore)
			.thenComparing(RouteSearchResult::routeSearchId);
		RouteSearchResult fastestItinerary = found.stream().min(fastest).orElseThrow();
		RouteSearchResult fewestTransferItinerary = found.stream().min(fewestTransfers).orElseThrow();
		List<RouteSearchResult> rankedFound;
		if (fastestItinerary.routeSearchId().equals(fewestTransferItinerary.routeSearchId())) {
			rankedFound = List.of(withObjectiveTags(fastestItinerary, List.of("FASTEST", "FEWEST_TRANSFERS")));
		} else {
			RouteSearchResult fastestResult = withObjectiveTags(fastestItinerary, List.of("FASTEST"));
			RouteSearchResult fewestResult = withObjectiveTags(fewestTransferItinerary, List.of("FEWEST_TRANSFERS"));
			rankedFound = requestedObjective == RouteObjective.FASTEST
				? List.of(fastestResult, fewestResult)
				: List.of(fewestResult, fastestResult);
		}
		List<RouteSearchResult> ranked = new ArrayList<>(alternativeCount);
		rankedFound.stream().limit(alternativeCount).forEach(ranked::add);
		stepFreeAlternative(constraintMode, found, ranked, alternativeCount).ifPresent(ranked::add);
		itineraries.stream()
			.filter(itinerary -> itinerary.status() != RouteSearchStatus.FOUND)
			.limit(alternativeCount - ranked.size())
			.forEach(ranked::add);
		return List.copyOf(ranked);
	}

	// #2560: 플래너가 보존한 무단차 후보(#2534)는 objective 대표 2건 축약에서 다시 버려진다. 두
	// comparator 모두 accessibilityRiskScore가 3순위라, 환승 수가 같고 계단 경로가 더 빠르면 두 대표가
	// 모두 계단 경로로 확정되기 때문이다. 선호(prefer)는 대안을 지우는 필터가 아니므로 대표를 교체하지
	// 않고, alternativeCount에 남는 자리가 있을 때만 무단차 대표 1건을 덧붙인다 — 표시 선두("최속"·
	// "최소 환승") 계약은 그대로 두고 응답 후보 집합만 넓힌다(정렬과 보존의 분리).
	// 발동 조건은 "응답에 담긴 대표 중 검증된 무단차가 없음"이다. 검증되지 않은 접근 동선(UNKNOWN)은
	// 무단차로 확인된 것이 아니므로 대표에 있어도 대안 보존을 막지 않는다.
	private Optional<RouteSearchResult> stepFreeAlternative(
		ConstraintMode constraintMode,
		List<RouteSearchResult> found,
		List<RouteSearchResult> representatives,
		int alternativeCount
	) {
		if (constraintMode != ConstraintMode.PREFER_STEP_FREE
			|| representatives.size() >= alternativeCount
			|| representatives.stream().anyMatch(itinerary -> StairAccess.ofItinerary(itinerary) == StairAccess.STEP_FREE)) {
			return Optional.empty();
		}
		List<String> representativeIds = representatives.stream()
			.map(RouteSearchResult::routeSearchId)
			.toList();
		Comparator<RouteSearchResult> stepFreePreference = Comparator
			.comparingInt(this::accessibilityRiskScore)
			.thenComparingLong(this::plannedArrivalEpochSecond)
			.thenComparingInt(RouteSearchResult::transferCount)
			.thenComparing(RouteSearchResult::routeSearchId);
		return found.stream()
			// 검증된 무단차 후보만 태깅한다. UNKNOWN 후보를 STEP_FREE_PREFERRED로 붙이면 태그가
			// 그 후보의 stairAccessState=UNKNOWN·requiresAccessibilityCheck=true와 모순된다.
			.filter(itinerary -> StairAccess.ofItinerary(itinerary) == StairAccess.STEP_FREE)
			.filter(itinerary -> !representativeIds.contains(itinerary.routeSearchId()))
			.map(itinerary -> withObjectiveTags(itinerary, List.of(STEP_FREE_OBJECTIVE_TAG)))
			// 응답에 편입되는 순간 prod 완결성 계약의 대상이 되고, 어기면 requireUsablePlan()이 plan
			// 전체를 503으로 거부한다(200이던 검색이 통째로 실패). 계약을 못 채우는 후보는 붙이지 않아
			// 기존 동작(=대안 미첨부)으로 안전하게 되돌아간다. 태깅 뒤에 걸러야 objectiveTags 조건을
			// 실제 응답 형태로 판정한다.
			.filter(itinerary -> !ProductionRouteV2Support.incompleteFoundItinerary(itinerary))
			.min(stepFreePreference);
	}

	private long plannedArrivalEpochSecond(RouteSearchResult itinerary) {
		return itinerary.steps().stream()
			.map(RouteStep::plannedArrivalTime)
			.filter(value -> value != null && !value.isBlank())
			.reduce((left, right) -> right)
			.map(OffsetDateTime::parse)
			.map(value -> value.toInstant().getEpochSecond())
			.orElseGet(() -> itinerary.createdAt().atOffset(java.time.ZoneOffset.ofHours(9))
				.plusSeconds(itinerary.estimatedDurationSeconds()).toInstant().getEpochSecond());
	}

	private RouteSearchResult withObjectiveTags(RouteSearchResult result, List<String> objectiveTags) {
		return new RouteSearchResult(
			result.routeSearchId(), result.originStationId(), result.originStationName(),
			result.destinationStationId(), result.destinationStationName(), result.mobilityType(),
			result.status(), result.lineId(), result.lineName(), result.score(), result.steps(),
			result.warnings(), result.blockedReasons(), result.createdAt(), objectiveTags, result.officialFare()
		);
	}

	private int accessibilityRiskScore(RouteSearchResult itinerary) {
		int score = itinerary.warnings().size() * 1_000 + itinerary.blockedReasons().size() * 1_000;
		for (RouteStep step : itinerary.steps()) {
			if (step.includesStairs()) {
				score += 100;
			}
			if ("UNKNOWN".equals(step.stairAccessState())) {
				score += 10;
			}
			if (step.requiresAccessibilityCheck()) {
				score += 1;
			}
		}
		return score;
	}

	private List<RouteV2Status> statusesOf(List<RouteSearchResult> itineraries, boolean useRealtime) {
		List<RouteV2Status> statuses = new ArrayList<>();
		for (RouteSearchResult itinerary : itineraries) {
			statuses.add(statusOf(itinerary));
			if (usesPlannedEtaAfterRealtimeRequest(itinerary, useRealtime)) {
				statuses.add(RouteV2Status.REALTIME_UNAVAILABLE_PLANNED_USED);
			}
		}
		return List.copyOf(statuses.stream().distinct().toList());
	}

	private boolean usesPlannedEtaAfterRealtimeRequest(RouteSearchResult itinerary, boolean useRealtime) {
		if (!useRealtime || itinerary.status() != RouteSearchStatus.FOUND) {
			return false;
		}
		List<RouteStep> rideSteps = itinerary.steps().stream()
			.filter(step -> "ride".equals(step.stepType()))
			.toList();
		if (rideSteps.isEmpty()) {
			return itinerary.etaSource() == EtaSource.STATIC_BACKEND_ESTIMATE
				|| itinerary.etaSource() == EtaSource.PLANNED
				|| itinerary.etaSource() == EtaSource.FALLBACK;
		}
		return rideSteps.stream()
			.anyMatch(step -> EtaSource.PLANNED.name().equals(step.timeSource())
				|| EtaSource.FALLBACK.name().equals(step.timeSource())
				|| EtaSource.STATIC_BACKEND_ESTIMATE.name().equals(step.timeSource())
				|| "ESTIMATED_CONSTANT".equals(step.timeSource())
				|| "STATIC_BACKEND_V1".equals(step.timeSource()));
	}

	private RouteV2Status statusOf(RouteSearchResult itinerary) {
		return itinerary.status() == RouteSearchStatus.BLOCKED
			? RouteV2Status.BLOCKED_ACCESSIBILITY
			: RouteV2Status.valueOf(itinerary.status().name());
	}

	private SearchRouteCommand toSearchRouteCommand(SearchRouteV2Command command) {
		return new SearchRouteCommand(
			command.originStationId(),
			command.destinationStationId(),
			command.mobilityType(),
			command.constraintMode(),
			command.maxTransfers(),
			command.departureTime(),
			command.useRealtime()
		);
	}
}
