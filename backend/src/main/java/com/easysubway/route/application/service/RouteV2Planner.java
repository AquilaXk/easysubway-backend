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
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.EtaSource;
import com.easysubway.route.domain.ProfileWalkTimeCalculator;
import com.easysubway.route.domain.RouteNotFoundException;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteStep;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

@Service
public class RouteV2Planner implements RouteV2SearchUseCase {

	private static final String PLANNER_ADR = "tools/routes/route-algorithm-v2-adr.json";
	private static final int RANKING_CANDIDATE_LIMIT = 3;

	private final RouteSearchUseCase routeSearchUseCase;
	private final LoadRouteTimetablePort routeTimetablePort;
	private final RouteTimetableRaptorPlanner timetableRaptorPlanner = new RouteTimetableRaptorPlanner();
	private final boolean timetableRequired;
	private final boolean legacyPersistenceAllowed;
	private volatile TimetableSnapshot cachedTimetableSnapshot;

	public RouteV2Planner(RouteSearchUseCase routeSearchUseCase) {
		this(routeSearchUseCase, RouteTimetable::empty, false, true);
	}

	@Autowired
	public RouteV2Planner(
		RouteSearchUseCase routeSearchUseCase,
		ObjectProvider<LoadRouteTimetablePort> routeTimetablePortProvider,
		Environment environment
	) {
		this(
			routeSearchUseCase,
			routeTimetablePortProvider.getIfAvailable(),
			true,
			!environment.acceptsProfiles(Profiles.of("prod", "staging", "release", "prod-like"))
		);
	}

	RouteV2Planner(RouteSearchUseCase routeSearchUseCase, LoadRouteTimetablePort routeTimetablePort) {
		this(routeSearchUseCase, routeTimetablePort, true, false);
	}

	private RouteV2Planner(
		RouteSearchUseCase routeSearchUseCase,
		LoadRouteTimetablePort routeTimetablePort,
		boolean timetableRequired,
		boolean legacyPersistenceAllowed
	) {
		this.routeSearchUseCase = routeSearchUseCase;
		this.routeTimetablePort = routeTimetablePort == null ? RouteTimetable::empty : routeTimetablePort;
		this.timetableRequired = timetableRequired && routeTimetablePort != null;
		this.legacyPersistenceAllowed = legacyPersistenceAllowed;
	}

	@Override
	public RouteV2Plan search(SearchRouteV2Command command) {
		try {
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
					candidates -> rankTimetableItineraries(candidates, command.alternativeCount())
				);
				timetableItineraries = stabilizedCandidates.itineraries();
				if (stabilizedCandidates.source() == TimetableCandidateSource.TIMETABLE_SCAN) {
					timetableItineraries = routeSearchUseCase.applyRealtimeToTimetableCandidates(
						searchRouteCommand,
						timetableItineraries
					);
				}
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
			snapshot.timetableArtifactId()
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
			snapshot.timetableArtifactId()
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
			return snapshot;
		}
		synchronized (this) {
			cacheKey = routeTimetablePort.timetableCacheKey();
			snapshot = cachedTimetableSnapshot;
			if (snapshot == null || !snapshot.cacheKey().equals(cacheKey)) {
				for (int attempt = 0; attempt < 2; attempt++) {
					cacheKey = routeTimetablePort.timetableCacheKey();
					String artifactId = routeTimetablePort.activeItxTimetableArtifactId().orElse(null);
					RouteTimetable timetable = routeTimetablePort.loadRouteTimetable();
					if (!cacheKey.equals(routeTimetablePort.timetableCacheKey())) {
						continue;
					}
					java.util.Set<String> coveredStationIds = timetable.transitStopTimes().stream()
						.map(LoadRouteTimetablePort.TransitStopTime::stationId)
						.collect(java.util.stream.Collectors.toUnmodifiableSet());
					cachedTimetableSnapshot = new TimetableSnapshot(cacheKey, timetable, coveredStationIds, artifactId);
					return cachedTimetableSnapshot;
				}
				return new TimetableSnapshot("UNSTABLE", RouteTimetable.empty(), java.util.Set.of(), null);
			}
			return cachedTimetableSnapshot;
		}
	}

	private record TimetableSnapshot(
		String cacheKey,
		RouteTimetable timetable,
		java.util.Set<String> coveredStationIds,
		String timetableArtifactId
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
			RANKING_CANDIDATE_LIMIT
		);
	}

	private List<RouteSearchResult> rankTimetableItineraries(List<RouteSearchResult> itineraries, int alternativeCount) {
		return itineraries.stream()
			.sorted(Comparator.comparingInt(RouteSearchResult::estimatedDurationSeconds)
				.thenComparingInt(RouteSearchResult::transferCount)
				.thenComparingInt(this::accessibilityRiskScore))
			.limit(alternativeCount)
			.toList();
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
