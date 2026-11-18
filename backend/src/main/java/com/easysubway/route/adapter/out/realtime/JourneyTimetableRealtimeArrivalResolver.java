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
import java.util.ArrayList;
import java.util.HashMap;
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
		if (queries == null || queries.size() != 1 || queries.getFirst() == null) {
			return unavailable();
		}
		Query query = queries.getFirst();
		Map<String, Departure> plannedByTrainNo = exactPlannedDepartures(query);
		if (plannedByTrainNo == null) {
			return unavailable();
		}

		RealtimeArrivalResolver.Resolution resolution;
		try {
			resolution = realtimeArrivalResolver.resolve(new RealtimeArrivalResolver.Query(
				query.stationId(), query.lineId(), null, null, null, "", query.readyAt()));
		} catch (RuntimeException exception) {
			return unavailable();
		}

		try {
			return project(query, plannedByTrainNo, resolution);
		} catch (RuntimeException exception) {
			return unavailable();
		}
	}

	private static Updates project(
		Query query,
		Map<String, Departure> plannedByTrainNo,
		RealtimeArrivalResolver.Resolution resolution
	) {
		if (resolution == null
			|| resolution.status() != ArrivalFreshness.FRESH_REALTIME
			|| !singleIdentity(resolution.providerSnapshotId())
			|| resolution.providerReceivedAt() == null) {
			return unavailable();
		}

		String snapshotId = resolution.providerSnapshotId();
		Map<String, Update> updatesByTripId = new HashMap<>();
		Set<String> cancelledTrainNos = new TreeSet<>(resolution.cancelledTrainNos());
		for (String trainNo : cancelledTrainNos) {
			Departure planned = plannedByTrainNo.get(trainNo);
			if (planned != null && !merge(updatesByTripId, new Update(
				planned, 0, 0, true, snapshotId, resolution.providerReceivedAt()))) {
				return unavailable();
			}
		}

		Map<String, ArrivalCandidate> candidatesByTrainNo = new HashMap<>();
		for (ArrivalCandidate candidate : resolution.candidates()) {
			if (!usableCandidate(query, plannedByTrainNo, cancelledTrainNos, candidate)) {
				continue;
			}
			candidatesByTrainNo.merge(candidate.trainNo(), candidate,
				JourneyTimetableRealtimeArrivalResolver::earlierCandidate);
		}

		for (Map.Entry<String, ArrivalCandidate> entry : candidatesByTrainNo.entrySet()) {
			Departure planned = plannedByTrainNo.get(entry.getKey());
			ArrivalCandidate candidate = entry.getValue();
			long deltaSeconds = Duration.between(
				planned.scheduledArrivalAt(), candidate.expectedArrivalAt()).toSeconds();
			if (deltaSeconds < Integer.MIN_VALUE || deltaSeconds > Integer.MAX_VALUE) {
				return unavailable();
			}
			if (!merge(updatesByTripId, new Update(
				planned,
				(int) deltaSeconds,
				(int) deltaSeconds,
				false,
				snapshotId,
				candidate.providerReceivedAt()))) {
				return unavailable();
			}
		}

		if (updatesByTripId.isEmpty()) {
			return unavailable();
		}
		List<Update> updates = new ArrayList<>(updatesByTripId.values());
		updates.sort(java.util.Comparator.comparing(update -> update.departure().tripId()));
		return new Updates(snapshotId, true, updates, null);
	}

	private static Map<String, Departure> exactPlannedDepartures(
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
		Map<String, Departure> plannedByTrainNo = new HashMap<>();
		for (Departure departure : departures) {
			if (departure == null
				|| !query.stationId().equals(departure.stationId())
				|| !query.lineId().equals(departure.lineId())
				|| departure.tripId() == null || departure.tripId().isBlank()
				|| departure.trainNo() == null || departure.trainNo().isBlank()
				|| departure.servicePattern() == null || departure.servicePattern().isBlank()
				|| departure.serviceDate() == null || departure.stopSequence() <= 0
				|| departure.scheduledArrivalAt() == null || departure.scheduledDepartureAt() == null
				|| departure.scheduledDepartureAt().isBefore(departure.scheduledArrivalAt())
				|| plannedByTrainNo.putIfAbsent(departure.trainNo(), departure) != null) {
				return null;
			}
		}
		return plannedByTrainNo;
	}

	private static boolean usableCandidate(
		Query query,
		Map<String, Departure> plannedByTrainNo,
		Set<String> cancelledTrainNos,
		ArrivalCandidate candidate
	) {
		if (candidate == null
			|| candidate.freshness() != ArrivalFreshness.FRESH_REALTIME
			|| candidate.trainNo() == null || candidate.trainNo().isBlank()
			|| candidate.providerReceivedAt() == null
			|| cancelledTrainNos.contains(candidate.trainNo())) {
			return false;
		}
		Departure planned = plannedByTrainNo.get(candidate.trainNo());
		if (planned == null) {
			return false;
		}
		Duration arrivalDelta = Duration.between(
			planned.scheduledArrivalAt(), candidate.expectedArrivalAt());
		return !planned.scheduledDepartureAt().plus(arrivalDelta).isBefore(query.readyAt());
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
		return value != null && !value.isBlank() && value.indexOf('+') < 0;
	}

	private static Updates unavailable() {
		return Updates.unavailable(UNAVAILABLE);
	}
}
