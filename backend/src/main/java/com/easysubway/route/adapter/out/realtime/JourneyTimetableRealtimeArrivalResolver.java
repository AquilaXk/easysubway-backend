package com.easysubway.route.adapter.out.realtime;

import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeQuery;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdate;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdates;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableTripDeparture;
import com.easysubway.route.application.port.out.RealtimeArrivalResolver;
import com.easysubway.route.application.service.JourneyTimetableRealtimeResolver;
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
	public TimetableRealtimeUpdates resolve(List<TimetableRealtimeQuery> queries) {
		if (queries == null || queries.size() != 1 || queries.getFirst() == null) {
			return unavailable();
		}
		TimetableRealtimeQuery query = queries.getFirst();
		Map<String, TimetableTripDeparture> plannedByTrainNo = exactPlannedDepartures(query.departures());
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

	private static TimetableRealtimeUpdates project(
		TimetableRealtimeQuery query,
		Map<String, TimetableTripDeparture> plannedByTrainNo,
		RealtimeArrivalResolver.Resolution resolution
	) {
		if (resolution == null
			|| resolution.status() != ArrivalFreshness.FRESH_REALTIME
			|| !singleIdentity(resolution.providerSnapshotId())
			|| resolution.providerReceivedAt() == null) {
			return unavailable();
		}

		String snapshotId = resolution.providerSnapshotId();
		Map<String, TimetableRealtimeUpdate> updatesByTripId = new HashMap<>();
		Set<String> cancelledTrainNos = new TreeSet<>(resolution.cancelledTrainNos());
		for (String trainNo : cancelledTrainNos) {
			TimetableTripDeparture planned = plannedByTrainNo.get(trainNo);
			if (planned != null && !merge(updatesByTripId, new TimetableRealtimeUpdate(
				planned.tripId(), 0, 0, true, snapshotId, resolution.providerReceivedAt()))) {
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
			TimetableTripDeparture planned = plannedByTrainNo.get(entry.getKey());
			ArrivalCandidate candidate = entry.getValue();
			long deltaSeconds = Duration.between(
				planned.scheduledArrivalAt(), candidate.expectedArrivalAt()).toSeconds();
			if (deltaSeconds < Integer.MIN_VALUE || deltaSeconds > Integer.MAX_VALUE) {
				return unavailable();
			}
			if (!merge(updatesByTripId, new TimetableRealtimeUpdate(
				planned.tripId(),
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
		List<TimetableRealtimeUpdate> updates = new ArrayList<>(updatesByTripId.values());
		updates.sort(java.util.Comparator.comparing(TimetableRealtimeUpdate::tripId));
		return new TimetableRealtimeUpdates(snapshotId, true, updates, null);
	}

	private static Map<String, TimetableTripDeparture> exactPlannedDepartures(
		List<TimetableTripDeparture> departures
	) {
		if (departures == null || departures.isEmpty()) {
			return null;
		}
		Map<String, TimetableTripDeparture> plannedByTrainNo = new HashMap<>();
		for (TimetableTripDeparture departure : departures) {
			if (departure == null
				|| departure.trainNo() == null || departure.trainNo().isBlank()
				|| departure.scheduledArrivalAt() == null || departure.scheduledDepartureAt() == null
				|| departure.scheduledDepartureAt().isBefore(departure.scheduledArrivalAt())
				|| plannedByTrainNo.putIfAbsent(departure.trainNo(), departure) != null) {
				return null;
			}
		}
		return plannedByTrainNo;
	}

	private static boolean usableCandidate(
		TimetableRealtimeQuery query,
		Map<String, TimetableTripDeparture> plannedByTrainNo,
		Set<String> cancelledTrainNos,
		ArrivalCandidate candidate
	) {
		return candidate != null
			&& candidate.freshness() == ArrivalFreshness.FRESH_REALTIME
			&& candidate.trainNo() != null && !candidate.trainNo().isBlank()
			&& candidate.providerReceivedAt() != null
			&& !candidate.expectedArrivalAt().isBefore(query.readyAt())
			&& !cancelledTrainNos.contains(candidate.trainNo())
			&& plannedByTrainNo.containsKey(candidate.trainNo());
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
		Map<String, TimetableRealtimeUpdate> updatesByTripId,
		TimetableRealtimeUpdate update
	) {
		TimetableRealtimeUpdate previous = updatesByTripId.get(update.tripId());
		if (previous != null && (previous.cancelled() != update.cancelled()
			|| previous.arrivalDeltaSeconds() != update.arrivalDeltaSeconds()
			|| previous.departureDeltaSeconds() != update.departureDeltaSeconds()
			|| !previous.providerSnapshotId().equals(update.providerSnapshotId()))) {
			return false;
		}
		if (previous == null || previous.providerObservedAt().isBefore(update.providerObservedAt())) {
			updatesByTripId.put(update.tripId(), update);
		}
		return true;
	}

	private static boolean singleIdentity(String value) {
		return value != null && !value.isBlank() && value.indexOf('+') < 0;
	}

	private static TimetableRealtimeUpdates unavailable() {
		return TimetableRealtimeUpdates.unavailable(UNAVAILABLE);
	}
}
