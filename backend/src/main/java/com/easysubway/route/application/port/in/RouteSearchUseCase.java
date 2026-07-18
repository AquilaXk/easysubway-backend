package com.easysubway.route.application.port.in;

import com.easysubway.route.domain.InternalRouteResult;
import com.easysubway.route.domain.RouteFeedback;
import com.easysubway.route.domain.RouteRefreshResult;
import com.easysubway.route.domain.RouteSearchResult;
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
}
