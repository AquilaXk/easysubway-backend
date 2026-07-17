package com.easysubway.route.application.service;

import com.easysubway.common.error.InvalidRequestException;
import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteSearchUseCase;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableCandidateSource;
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
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.EtaSource;
import com.easysubway.route.domain.ProfileWalkTimeCalculator;
import com.easysubway.route.domain.RouteNotFoundException;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteStep;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
				&& canUseTimetableRaptor(command)
				&& routeTimetablePort.hasRouteTimetable()
				? timetableSnapshot()
				: null;
			if (snapshot != null && timetableCovers(command, snapshot)) {
				if (timetableRaptorPlanner.isFeedStale(command, snapshot.timetable())) {
					return timetablePlan(List.of(), List.of(RouteV2Status.STALE_TIMETABLE), snapshot);
				}
				SearchRouteCommand searchRouteCommand = toSearchRouteCommand(command);
				routeSearchUseCase.validateRouteSearch(searchRouteCommand);
				List<RouteSearchResult> timetableItineraries = timetableRaptorPlanner.search(
					rankingCommand(command),
					snapshot.timetable()
				);
				if (timetableItineraries.isEmpty()) {
					return noTimetableServicePlan(command, snapshot);
				}
				var stabilizedCandidates = routeSearchUseCase.stabilizeTimetableRouteCandidatesWithSource(
					searchRouteCommand,
					RANKING_CANDIDATE_LIMIT,
					command.alternativeCount(),
					timetableItineraries,
					List::copyOf
				);
				timetableItineraries = stabilizedCandidates.itineraries();
				if (stabilizedCandidates.source() == TimetableCandidateSource.TIMETABLE_SCAN) {
					timetableItineraries = routeSearchUseCase.applyRealtimeToTimetableCandidates(
						searchRouteCommand,
						timetableItineraries
					);
				}
				timetableItineraries = rankTimetableItineraries(
					timetableItineraries,
					command.objective(),
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

	private RouteV2Plan noTimetableServicePlan(SearchRouteV2Command command, TimetableSnapshot snapshot) {
		OffsetDateTime nextServiceTime = timetableRaptorPlanner.nextServiceTime(command, snapshot.timetable()).orElse(null);
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

	private boolean canUseTimetableRaptor(SearchRouteV2Command command) {
		return command.constraintMode() != ConstraintMode.STRICT_STEP_FREE
			&& command.mobilityType() != MobilityType.WHEELCHAIR;
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
				java.util.Set<String> coveredStationIds = timetable.transitStopTimes().stream()
					.map(LoadRouteTimetablePort.TransitStopTime::stationId)
					.collect(java.util.stream.Collectors.toUnmodifiableSet());
				cachedTimetableSnapshot = new TimetableSnapshot(
					loaded.cacheKey(),
					timetable,
					coveredStationIds,
					loaded.timetableArtifactId(),
					loaded.plannerIdentity()
				);
				return cachedTimetableSnapshot;
			}
			recordTimetableCache(timetableCacheHit, timetableCacheHitLogged, "hit", cacheKey);
			return cachedTimetableSnapshot;
		}
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
		RouteTimetable timetable,
		java.util.Set<String> coveredStationIds,
		String timetableArtifactId,
		LoadRouteTimetablePort.PlannerIdentity plannerIdentity
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
		int alternativeCount
	) {
		if (itineraries.isEmpty()) {
			return List.of();
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
		RouteSearchResult fastestItinerary = itineraries.stream().min(fastest).orElseThrow();
		RouteSearchResult fewestTransferItinerary = itineraries.stream().min(fewestTransfers).orElseThrow();
		if (fastestItinerary.routeSearchId().equals(fewestTransferItinerary.routeSearchId())) {
			return List.of(withObjectiveTags(fastestItinerary, List.of("FASTEST", "FEWEST_TRANSFERS")));
		}
		RouteSearchResult fastestResult = withObjectiveTags(fastestItinerary, List.of("FASTEST"));
		RouteSearchResult fewestResult = withObjectiveTags(fewestTransferItinerary, List.of("FEWEST_TRANSFERS"));
		List<RouteSearchResult> representatives = requestedObjective == RouteObjective.FASTEST
			? List.of(fastestResult, fewestResult)
			: List.of(fewestResult, fastestResult);
		return representatives.stream().limit(alternativeCount).toList();
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
		return itinerary.etaSource() == EtaSource.STATIC_BACKEND_ESTIMATE
			|| itinerary.etaSource() == EtaSource.PLANNED
			|| itinerary.etaSource() == EtaSource.FALLBACK;
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
