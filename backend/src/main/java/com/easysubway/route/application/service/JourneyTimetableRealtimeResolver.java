package com.easysubway.route.application.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@FunctionalInterface
public interface JourneyTimetableRealtimeResolver {

	Updates resolve(List<Query> queries);

	record Query(String stationId, String lineId, Instant readyAt, List<Departure> departures) {
		public Query {
			departures = List.copyOf(departures);
		}
	}

	record Departure(
		String stationId,
		String lineId,
		String tripId,
		String trainNo,
		String servicePattern,
		LocalDate serviceDate,
		int stopSequence,
		Instant scheduledArrivalAt,
		Instant scheduledDepartureAt
	) {
	}

	record Update(
		Departure departure,
		int arrivalDeltaSeconds,
		int departureDeltaSeconds,
		boolean cancelled,
		String providerSnapshotId,
		Instant providerObservedAt
	) {
	}

	record Updates(String version, boolean available, List<Update> updates, String unavailableReason) {
		public Updates {
			updates = List.copyOf(updates);
		}

		public static Updates unavailable(String reason) {
			return new Updates(null, false, List.of(), reason);
		}
	}
}
