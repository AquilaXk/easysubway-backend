package com.easysubway.route.application.port.in;

import com.easysubway.route.domain.InternalRouteResult;
import com.easysubway.route.domain.RouteFeedback;
import com.easysubway.route.domain.RouteRefreshResult;
import com.easysubway.route.domain.RouteSearchResult;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

public interface RouteSearchUseCase {

	RouteSearchResult searchRoute(SearchRouteCommand command);

	List<RouteSearchResult> searchRouteAlternatives(SearchRouteCommand command, int alternativeCount);

	default List<RouteSearchResult> planRouteAlternatives(SearchRouteCommand command, int alternativeCount) {
		return searchRouteAlternatives(command, alternativeCount);
	}

	default void validateRouteSearch(SearchRouteCommand command) {
	}

	default List<RouteSearchResult> stabilizeTimetableRouteResults(
		SearchRouteCommand command,
		int alternativeCount,
		List<RouteSearchResult> timetableResults
	) {
		return List.copyOf(timetableResults);
	}

	default List<RouteSearchResult> stabilizeTimetableRouteCandidates(
		SearchRouteCommand command,
		int candidateCount,
		int alternativeCount,
		List<RouteSearchResult> timetableResults
	) {
		return stabilizeTimetableRouteResults(command, alternativeCount, timetableResults);
	}

	default List<RouteSearchResult> stabilizeTimetableRouteCandidates(
		SearchRouteCommand command,
		int candidateCount,
		int alternativeCount,
		List<RouteSearchResult> timetableResults,
		UnaryOperator<List<RouteSearchResult>> selectCandidates
	) {
		return selectCandidates.apply(stabilizeTimetableRouteCandidates(
			command,
			candidateCount,
			alternativeCount,
			timetableResults
		));
	}

	default TimetableCandidateSelection stabilizeTimetableRouteCandidatesWithSource(
		SearchRouteCommand command,
		int candidateCount,
		int alternativeCount,
		List<RouteSearchResult> timetableResults,
		UnaryOperator<List<RouteSearchResult>> selectCandidates,
		boolean legacyGraphCandidateAllowed
	) {
		return new TimetableCandidateSelection(
			stabilizeTimetableRouteCandidates(
				command,
				candidateCount,
				alternativeCount,
				timetableResults,
				selectCandidates
			),
			TimetableCandidateSource.TIMETABLE_SCAN
		);
	}

	default List<RouteSearchResult> applyRealtimeToTimetableCandidates(
		SearchRouteCommand command,
		List<RouteSearchResult> timetableResults
	) {
		return List.copyOf(timetableResults);
	}

	default TimetableRealtimeUpdates resolveTimetableRealtime(
		List<TimetableRealtimeQuery> queries
	) {
		return TimetableRealtimeUpdates.unavailable("REALTIME_OVERLAY_UNSUPPORTED");
	}

	default boolean supportsRealtimeOverlay() {
		return true;
	}

	InternalRouteResult searchInternalRoute(SearchInternalRouteCommand command);

	RouteSearchResult getRouteSearch(String routeSearchId);

	RouteRefreshResult refreshRoute(String routeSearchId);

	RouteFeedback submitRouteFeedback(SubmitRouteFeedbackCommand command);

	record TimetableCandidateSelection(
		List<RouteSearchResult> itineraries,
		TimetableCandidateSource source
	) {
		public TimetableCandidateSelection {
			itineraries = List.copyOf(Objects.requireNonNull(itineraries, "itineraries must not be null"));
			Objects.requireNonNull(source, "source must not be null");
		}
	}

	enum TimetableCandidateSource {
		TIMETABLE_SCAN,
		LEGACY_ACCESSIBILITY_CHECK
	}

	record TimetableRealtimeQuery(
		String stationId,
		String lineId,
		Instant readyAt,
		List<TimetableTripDeparture> departures
	) {
		public TimetableRealtimeQuery {
			requireText(stationId, "stationId");
			requireText(lineId, "lineId");
			Objects.requireNonNull(readyAt, "readyAt must not be null");
			departures = List.copyOf(Objects.requireNonNull(departures, "departures must not be null"));
		}
	}

	record TimetableTripDeparture(
		String tripId,
		String trainNo,
		String servicePattern,
		Instant scheduledArrivalAt,
		Instant scheduledDepartureAt
	) {
		public TimetableTripDeparture {
			requireText(tripId, "tripId");
			requireText(trainNo, "trainNo");
			Objects.requireNonNull(scheduledArrivalAt, "scheduledArrivalAt must not be null");
			Objects.requireNonNull(scheduledDepartureAt, "scheduledDepartureAt must not be null");
		}
	}

	record TimetableRealtimeUpdate(
		String tripId,
		int arrivalDeltaSeconds,
		int departureDeltaSeconds,
		boolean cancelled,
		String providerSnapshotId,
		Instant providerObservedAt
	) {
		public TimetableRealtimeUpdate {
			requireText(tripId, "tripId");
			requireText(providerSnapshotId, "providerSnapshotId");
			Objects.requireNonNull(providerObservedAt, "providerObservedAt must not be null");
		}
	}

	record TimetableRealtimeUpdates(
		String version,
		boolean available,
		List<TimetableRealtimeUpdate> updates,
		String fallbackCode
	) {
		public TimetableRealtimeUpdates {
			updates = List.copyOf(Objects.requireNonNull(updates, "updates must not be null"));
			if (available && ((version == null || version.isBlank()) || updates.isEmpty())) {
				throw new IllegalArgumentException("available realtime updates require a version and sparse updates");
			}
			if (!available && !updates.isEmpty()) {
				throw new IllegalArgumentException("unavailable realtime updates must be empty");
			}
			if (!available) {
				requireText(fallbackCode, "fallbackCode");
			}
		}

		public static TimetableRealtimeUpdates unavailable(String fallbackCode) {
			return new TimetableRealtimeUpdates(null, false, List.of(), fallbackCode);
		}
	}

	private static void requireText(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
	}
}
