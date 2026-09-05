package com.easysubway.route.application.service;

import com.easysubway.journey.application.ServiceDayResolver;
import com.easysubway.journey.bundle.JourneyProfileMeasurementInputs.Scope;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 측정용 compiled ride 하나만 고르며, 실제 접근 가능 경로 질의는 수행하지 않는다. */
final class JourneyProfileMeasurementOd {
	private JourneyProfileMeasurementOd() { }

	static DirectOdCandidate selectDirectOd(
		Scope scope, String regionId, List<JourneyProfileCandidateEvents.Event> events
	) {
		Objects.requireNonNull(scope, "scope is required");
		Objects.requireNonNull(regionId, "regionId is required");
		Objects.requireNonNull(events, "events are required");
		Map<String, Set<Attribution>> attributions = attributions(scope);
		attributions.forEach((lineId, values) -> {
			if (values.size() != 1) throw new IllegalArgumentException("line attribution is ambiguous: " + lineId);
		});
		Set<String> regionLines = scope.activeLines().stream()
			.filter(line -> regionId.equals(line.regionId()))
			.map(line -> line.lineId())
			.collect(Collectors.toUnmodifiableSet());
		if (regionLines.isEmpty()) throw new IllegalArgumentException("requested region is not in scope: " + regionId);

		DirectOdCandidate selected = null;
		for (JourneyProfileCandidateEvents.Event event : events) {
			if (!regionLines.contains(event.routeLineId())) continue;
			Instant midnight = event.serviceDate().atStartOfDay(ServiceDayResolver.ZONE).toInstant();
			for (int from = 0; from < event.stops().size(); from += 1) {
				JourneyProfileCandidateEvents.Stop board = event.stops().get(from);
				if (!board.allowsPickup()) continue;
				for (int to = from + 1; to < event.stops().size(); to += 1) {
					JourneyProfileCandidateEvents.Stop alight = event.stops().get(to);
					if (!alight.allowsDropOff() || Objects.equals(board.stationId(), alight.stationId())) continue;
					Instant departureAt = midnight.plusSeconds(board.departureSeconds());
					Instant arrivalAt = midnight.plusSeconds(alight.arrivalSeconds());
					if (arrivalAt.isBefore(departureAt)) continue;
					DirectOdCandidate candidate = new DirectOdCandidate(
						regionId, event.routeLineId(), event.serviceDate(), event.tripId(), event.scheduledTripIndex(),
						from, to, board.stationId(), board.lineId(), alight.stationId(), alight.lineId(), departureAt, arrivalAt);
					if (selected == null || ORDER.compare(candidate, selected) < 0) selected = candidate;
				}
			}
		}
		if (selected == null) throw new IllegalArgumentException("no allowed direct OD candidate");
		return selected;
	}

	private static Map<String, Set<Attribution>> attributions(Scope scope) {
		Map<String, Set<Attribution>> result = new HashMap<>();
		scope.activeLines().forEach(line -> result.computeIfAbsent(line.lineId(), ignored -> new java.util.HashSet<>())
			.add(new Attribution(line.regionId(), line.operatorId())));
		return result;
	}

	private static final Comparator<DirectOdCandidate> ORDER = Comparator
		.comparing(DirectOdCandidate::routeLineId)
		.thenComparing(DirectOdCandidate::serviceDate)
		.thenComparing(DirectOdCandidate::tripId)
		.thenComparingInt(DirectOdCandidate::scheduledTripIndex)
		.thenComparingInt(DirectOdCandidate::boardStopIndex)
		.thenComparingInt(DirectOdCandidate::alightStopIndex)
		.thenComparing(DirectOdCandidate::departureAt)
		.thenComparing(DirectOdCandidate::arrivalAt)
		.thenComparing(DirectOdCandidate::originStationId)
		.thenComparing(DirectOdCandidate::destinationStationId);

	private record Attribution(String regionId, String operatorId) { }

	record DirectOdCandidate(
		String regionId, String routeLineId, LocalDate serviceDate, String tripId, int scheduledTripIndex,
		int boardStopIndex, int alightStopIndex, String originStationId, String originLineId,
		String destinationStationId, String destinationLineId, Instant departureAt, Instant arrivalAt
	) { }
}
