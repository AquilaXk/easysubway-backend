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
		List<ArrivalCandidate> candidates,
		List<String> cancelledTrainNos
	) {
		public Resolution(
			ArrivalFreshness status,
			String fallbackCode,
			String providerSnapshotId,
			Instant providerReceivedAt,
			List<ArrivalCandidate> candidates
		) {
			this(status, fallbackCode, providerSnapshotId, providerReceivedAt, candidates, List.of());
		}

		public Resolution {
			status = status == null ? ArrivalFreshness.UNAVAILABLE : status;
			candidates = candidates == null ? List.of() : List.copyOf(candidates);
			cancelledTrainNos = cancelledTrainNos == null ? List.of() : List.copyOf(cancelledTrainNos.stream()
				.filter(value -> value != null)
				.map(String::trim)
				.filter(value -> !value.isEmpty())
				.distinct()
				.toList());
		}
	}
}
