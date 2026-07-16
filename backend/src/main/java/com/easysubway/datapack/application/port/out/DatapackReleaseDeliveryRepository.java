package com.easysubway.datapack.application.port.out;

import com.easysubway.datapack.domain.DatapackReleaseDelivery;
import com.easysubway.datapack.domain.DatapackReleaseDelivery.State;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DatapackReleaseDeliveryRepository {
	DatapackReleaseDelivery upsertSameDelivery(DatapackReleaseDelivery delivery);
	Optional<DatapackReleaseDelivery> findByIdempotencyKey(String idempotencyKey);
	Optional<DatapackReleaseDelivery> findByRequestAndSequence(String requestId, long sequence);
	List<DatapackReleaseDelivery> claimDue(LocalDateTime now, String owner, int limit);
	void mark(String idempotencyKey, State state, int attempts, LocalDateTime nextAttemptAt,
		String httpClass, String detail, LocalDateTime now);
	void markClaimed(String idempotencyKey, String owner, State state, int attempts,
		LocalDateTime nextAttemptAt, String httpClass, String detail, LocalDateTime now);
	List<DatapackReleaseDelivery> findRecent(int limit);
}
