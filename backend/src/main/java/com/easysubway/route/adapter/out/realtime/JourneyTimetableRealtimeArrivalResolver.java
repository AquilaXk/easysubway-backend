package com.easysubway.route.adapter.out.realtime;

import com.easysubway.route.application.port.out.RealtimeArrivalResolver;
import com.easysubway.route.application.service.JourneyTimetableRealtimeResolver;
import com.easysubway.route.application.service.JourneyTimetableRealtimeResolver.Departure;
import com.easysubway.route.application.service.JourneyTimetableRealtimeResolver.Query;
import com.easysubway.route.application.service.JourneyTimetableRealtimeResolver.Update;
import com.easysubway.route.application.service.JourneyTimetableRealtimeResolver.Updates;
import com.easysubway.route.domain.ArrivalCandidate;
import com.easysubway.route.domain.ArrivalFreshness;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

@Component
final class JourneyTimetableRealtimeArrivalResolver implements JourneyTimetableRealtimeResolver {

	private static final String UNAVAILABLE = "REALTIME_REQUIRED_UNAVAILABLE";

	private final RealtimeArrivalResolver realtimeArrivalResolver;

	JourneyTimetableRealtimeArrivalResolver(RealtimeArrivalResolver realtimeArrivalResolver) {
		this.realtimeArrivalResolver = Objects.requireNonNull(realtimeArrivalResolver, "realtimeArrivalResolver");
	}

	@Override
	public Updates resolve(List<Query> queries) {
		if (queries == null || queries.isEmpty()) {
			return unavailable();
		}
		String expectedStationId = null;
		Set<String> seenLineIds = new HashSet<>(queries.size());
		List<ValidatedQuery> validatedQueries = new ArrayList<>(queries.size());
		for (Query query : queries) {
			if (query == null
				|| query.stationId() == null || query.stationId().isBlank()
				|| query.lineId() == null || query.lineId().isBlank()) {
				return unavailable();
			}
			if (expectedStationId == null) {
				expectedStationId = query.stationId();
			} else if (!expectedStationId.equals(query.stationId())) {
				return unavailable();
			}
			if (!seenLineIds.add(query.lineId())) {
				return unavailable();
			}
			Map<String, List<Departure>> plannedByTrainNo = exactPlannedDepartures(query);
			if (plannedByTrainNo == null) {
				return unavailable();
			}
			validatedQueries.add(new ValidatedQuery(query, plannedByTrainNo));
		}

		Map<String, Update> updatesByTripId = new HashMap<>();
		for (ValidatedQuery validated : validatedQueries) {
			Query query = validated.query();
			RealtimeArrivalResolver.Resolution resolution;
			try {
				resolution = realtimeArrivalResolver.resolve(new RealtimeArrivalResolver.Query(
					query.stationId(), query.lineId(), null, null, null, "", query.readyAt()));
			} catch (RuntimeException exception) {
				continue;
			}
			if (resolution == null || resolution.status() != ArrivalFreshness.FRESH_REALTIME) {
				continue;
			}
			if (!singleIdentity(resolution.providerSnapshotId()) || resolution.providerReceivedAt() == null) {
				return unavailable();
			}
			boolean mergedCleanly;
			try {
				mergedCleanly = projectInto(query, validated.plannedByTrainNo(), resolution, updatesByTripId);
			} catch (RuntimeException exception) {
				return unavailable();
			}
			if (!mergedCleanly) {
				return unavailable();
			}
		}

		if (updatesByTripId.isEmpty()) {
			return unavailable();
		}
		List<Update> updates = new ArrayList<>(updatesByTripId.values());
		updates.sort(Comparator.comparing(update -> update.departure().tripId()));
		Set<String> snapshotIds = new TreeSet<>();
		for (Update update : updates) {
			snapshotIds.add(update.providerSnapshotId());
		}
		return new Updates(String.join("|", snapshotIds), true, updates, null);
	}

	private static boolean projectInto(
		Query query,
		Map<String, List<Departure>> plannedByTrainNo,
		RealtimeArrivalResolver.Resolution resolution,
		Map<String, Update> updatesByTripId
	) {
		String snapshotId = resolution.providerSnapshotId();
		Set<String> cancelledTrainNos = new TreeSet<>(resolution.cancelledTrainNos());
		for (String trainNo : cancelledTrainNos) {
			Departure planned = selectCancelledDeparture(plannedByTrainNo.get(trainNo), query.readyAt());
			if (planned != null && !merge(updatesByTripId, new Update(
				planned, 0, 0, true, snapshotId, resolution.providerReceivedAt()))) {
				return false;
			}
		}

		Map<String, ArrivalCandidate> candidatesByTrainNo = new HashMap<>();
		for (ArrivalCandidate candidate : resolution.candidates()) {
			if (candidate == null
				|| candidate.freshness() != ArrivalFreshness.FRESH_REALTIME
				|| candidate.trainNo() == null || candidate.trainNo().isBlank()
				|| candidate.providerReceivedAt() == null
				|| cancelledTrainNos.contains(candidate.trainNo())) {
				continue;
			}
			if (selectArrivalDeparture(plannedByTrainNo.get(candidate.trainNo()), candidate, query.readyAt()) == null) {
				continue;
			}
			candidatesByTrainNo.merge(
				candidate.trainNo(),
				candidate,
				JourneyTimetableRealtimeArrivalResolver::earlierCandidate);
		}

		for (ArrivalCandidate candidate : candidatesByTrainNo.values()) {
			Departure planned = selectArrivalDeparture(
				plannedByTrainNo.get(candidate.trainNo()), candidate, query.readyAt());
			if (planned == null) {
				continue;
			}
			long deltaSeconds = Duration.between(
				planned.scheduledArrivalAt(), candidate.expectedArrivalAt()).toSeconds();
			if (deltaSeconds < Integer.MIN_VALUE || deltaSeconds > Integer.MAX_VALUE) {
				return false;
			}
			if (!merge(updatesByTripId, new Update(
				planned,
				(int) deltaSeconds,
				(int) deltaSeconds,
				false,
				snapshotId,
				candidate.providerReceivedAt()))) {
				return false;
			}
		}
		return true;
	}

	private static Map<String, List<Departure>> exactPlannedDepartures(
		Query query
	) {
		if (query.stationId() == null || query.stationId().isBlank()
			|| query.lineId() == null || query.lineId().isBlank()
			|| query.readyAt() == null) {
			return null;
		}
		List<Departure> departures = query.departures();
		if (departures == null || departures.isEmpty()) {
			return null;
		}
		Map<String, List<Departure>> plannedByTrainNo = new HashMap<>();
		Set<DepartureOccurrenceKey> seenOccurrences = new HashSet<>(departures.size());
		for (Departure departure : departures) {
			if (departure == null
				|| !query.stationId().equals(departure.stationId())
				|| !query.lineId().equals(departure.lineId())
				|| departure.tripId() == null || departure.tripId().isBlank()
				|| departure.trainNo() == null || departure.trainNo().isBlank()
				|| departure.servicePattern() == null || departure.servicePattern().isBlank()
				|| departure.serviceDate() == null || departure.stopSequence() <= 0
				|| departure.scheduledArrivalAt() == null || departure.scheduledDepartureAt() == null
				|| departure.scheduledDepartureAt().isBefore(departure.scheduledArrivalAt())) {
				return null;
			}
			DepartureOccurrenceKey key = new DepartureOccurrenceKey(
				departure.trainNo(), departure.tripId(), departure.serviceDate(), departure.stopSequence());
			if (!seenOccurrences.add(key)) {
				return null;
			}
			plannedByTrainNo.computeIfAbsent(departure.trainNo(), ignored -> new ArrayList<>()).add(departure);
		}
		return plannedByTrainNo;
	}

	private static Departure selectCancelledDeparture(List<Departure> candidates, Instant readyAt) {
		if (candidates == null || candidates.isEmpty()) {
			return null;
		}
		Departure earliestAfterReady = null;
		Departure latestBeforeReady = null;
		for (Departure departure : candidates) {
			if (!departure.scheduledDepartureAt().isBefore(readyAt)) {
				if (earliestAfterReady == null
					|| departure.scheduledDepartureAt().isBefore(earliestAfterReady.scheduledDepartureAt())) {
					earliestAfterReady = departure;
				}
			} else if (latestBeforeReady == null
				|| departure.scheduledDepartureAt().isAfter(latestBeforeReady.scheduledDepartureAt())) {
				latestBeforeReady = departure;
			}
		}
		return earliestAfterReady != null ? earliestAfterReady : latestBeforeReady;
	}

	private static Departure selectArrivalDeparture(
		List<Departure> candidates,
		ArrivalCandidate candidate,
		Instant readyAt
	) {
		if (candidates == null || candidates.isEmpty()) {
			return null;
		}
		Departure best = null;
		long bestAbsDiffSeconds = Long.MAX_VALUE;
		for (Departure planned : candidates) {
			Duration arrivalDelta = Duration.between(
				planned.scheduledArrivalAt(), candidate.expectedArrivalAt());
			if (planned.scheduledDepartureAt().plus(arrivalDelta).isBefore(readyAt)) {
				continue;
			}
			long absDiffSeconds = Math.abs(arrivalDelta.toSeconds());
			if (best == null
				|| absDiffSeconds < bestAbsDiffSeconds
				|| (absDiffSeconds == bestAbsDiffSeconds && compareTieBreak(planned, best) < 0)) {
				best = planned;
				bestAbsDiffSeconds = absDiffSeconds;
			}
		}
		return best;
	}

	private static int compareTieBreak(Departure left, Departure right) {
		int cmp = left.scheduledArrivalAt().compareTo(right.scheduledArrivalAt());
		if (cmp != 0) return cmp;
		cmp = left.serviceDate().compareTo(right.serviceDate());
		if (cmp != 0) return cmp;
		return Integer.compare(left.stopSequence(), right.stopSequence());
	}

	private static ArrivalCandidate earlierCandidate(ArrivalCandidate left, ArrivalCandidate right) {
		int arrivalOrder = left.expectedArrivalAt().compareTo(right.expectedArrivalAt());
		if (arrivalOrder < 0) {
			return left;
		}
		if (arrivalOrder > 0) {
			return right;
		}
		return left.providerReceivedAt().isBefore(right.providerReceivedAt()) ? right : left;
	}

	private static boolean merge(
		Map<String, Update> updatesByTripId,
		Update update
	) {
		Update previous = updatesByTripId.get(update.departure().tripId());
		if (previous != null && (previous.cancelled() != update.cancelled()
			|| !previous.departure().equals(update.departure())
			|| previous.arrivalDeltaSeconds() != update.arrivalDeltaSeconds()
			|| previous.departureDeltaSeconds() != update.departureDeltaSeconds()
			|| !previous.providerSnapshotId().equals(update.providerSnapshotId()))) {
			return false;
		}
		if (previous == null || previous.providerObservedAt().isBefore(update.providerObservedAt())) {
			updatesByTripId.put(update.departure().tripId(), update);
		}
		return true;
	}

	private static boolean singleIdentity(String value) {
		return value != null && !value.isBlank() && value.indexOf('+') < 0 && value.indexOf('|') < 0;
	}

	private static Updates unavailable() {
		return Updates.unavailable(UNAVAILABLE);
	}

	private record ValidatedQuery(Query query, Map<String, List<Departure>> plannedByTrainNo) {
	}

	private record DepartureOccurrenceKey(String trainNo, String tripId, LocalDate serviceDate, int stopSequence) {
	}
}
