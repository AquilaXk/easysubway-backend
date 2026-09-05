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
		Scope scope, String regionId, List<JourneyProfileCandidateEvents.Event> events,
		List<JourneyProfileExactOracle.Access> accesses, Instant activeFrom, Instant freshUntil, int boardingSlackSeconds
	) {
		Objects.requireNonNull(scope, "scope is required");
		Objects.requireNonNull(regionId, "regionId is required");
		Objects.requireNonNull(events, "events are required");
		accesses = List.copyOf(Objects.requireNonNull(accesses, "accesses are required"));
		if (activeFrom == null || freshUntil == null || !activeFrom.isBefore(freshUntil) || boardingSlackSeconds < 0) {
			throw new IllegalArgumentException("candidate window and boarding slack are required");
		}
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
					var entry = accesses.stream().filter(access -> entry(access, board)).min(ACCESS_ORDER).orElse(null);
					var exit = accesses.stream().filter(access -> exit(access, alight)).min(ACCESS_ORDER).orElse(null);
					if (entry == null || exit == null) continue;
					Instant readyAt = departureAt.minusSeconds(entry.durationSeconds()).minusSeconds(boardingSlackSeconds);
					Instant arrivalAtDestination = arrivalAt.plusSeconds(exit.durationSeconds());
					if (readyAt.isBefore(activeFrom) || !arrivalAtDestination.isBefore(freshUntil)) continue;
					DirectOdCandidate candidate = new DirectOdCandidate(
						regionId, event.routeLineId(), event.serviceDate(), event.tripId(), event.scheduledTripIndex(),
						from, to, board.stationId(), board.lineId(), alight.stationId(), alight.lineId(), departureAt, arrivalAt,
						readyAt, arrivalAtDestination, entry.id(), exit.id());
					if (selected == null || ORDER.compare(candidate, selected) < 0) selected = candidate;
				}
			}
		}
		if (selected == null) throw new IllegalArgumentException("no allowed direct OD candidate");
		return selected;
	}

	private static boolean entry(JourneyProfileExactOracle.Access access, JourneyProfileCandidateEvents.Stop board) {
		return access.usable() && access.kind() == JourneyProfileExactOracle.AccessKind.ENTRY
			&& board.stationId().equals(access.fromStationId()) && board.stationId().equals(access.toStationId())
			&& board.lineId().equals(access.toLineId());
	}

	private static boolean exit(JourneyProfileExactOracle.Access access, JourneyProfileCandidateEvents.Stop alight) {
		return access.usable() && access.kind() == JourneyProfileExactOracle.AccessKind.EXIT
			&& alight.stationId().equals(access.fromStationId()) && alight.stationId().equals(access.toStationId())
			&& alight.lineId().equals(access.fromLineId());
	}

	private static Map<String, Set<Attribution>> attributions(Scope scope) {
		Map<String, Set<Attribution>> result = new HashMap<>();
		scope.activeLines().forEach(line -> result.computeIfAbsent(line.lineId(), ignored -> new java.util.HashSet<>())
			.add(new Attribution(line.regionId(), line.operatorId())));
		return result;
	}

	private static final Comparator<JourneyProfileExactOracle.Access> ACCESS_ORDER = Comparator
		.comparingInt(JourneyProfileExactOracle.Access::durationSeconds)
		.thenComparing(JourneyProfileExactOracle.Access::id);

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
		.thenComparing(DirectOdCandidate::destinationStationId)
		.thenComparing(DirectOdCandidate::entryAccessId)
		.thenComparing(DirectOdCandidate::exitAccessId);

	private record Attribution(String regionId, String operatorId) { }

	record DirectOdCandidate(
		String regionId, String routeLineId, LocalDate serviceDate, String tripId, int scheduledTripIndex,
		int boardStopIndex, int alightStopIndex, String originStationId, String originLineId,
		String destinationStationId, String destinationLineId, Instant departureAt, Instant arrivalAt,
		Instant readyAt, Instant arrivalAtDestination, String entryAccessId, String exitAccessId
	) { }
}
