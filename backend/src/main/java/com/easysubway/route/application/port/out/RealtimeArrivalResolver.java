package com.easysubway.route.application.port.out;

import com.easysubway.route.domain.ArrivalCandidate;
import com.easysubway.route.domain.ArrivalFreshness;
import java.time.Instant;
import java.util.List;

public interface RealtimeArrivalResolver {

	Resolution resolve(Query query);

	record Query(
		String stationId,
		String lineId,
		String providerLineId,
		String stationQueryName,
		String lineName,
		String direction,
		Instant readyAt
	) {
		public Query {
			if (readyAt == null) {
				throw new IllegalArgumentException("readyAt is required.");
			}
		}
	}

	record Resolution(
		ArrivalFreshness status,
		String fallbackCode,
		String providerSnapshotId,
		Instant providerReceivedAt,
		List<ArrivalCandidate> candidates
	) {
		public Resolution {
			candidates = candidates == null ? List.of() : List.copyOf(candidates);
		}
	}
}
