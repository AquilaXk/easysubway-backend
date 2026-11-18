package com.easysubway.datapack.application.port.out;

import java.time.Instant;
import java.util.Optional;

/** 자동화 상태 snapshot의 마지막 1건 저장소. */
public interface AutomationStatusRepository {

	/** 더 새로운 generatedAt만 이전 값을 대체한다. 같거나 오래되면 STALE이고 아무것도 바꾸지 않는다. */
	SaveResult save(String payloadJson, Instant generatedAt, Instant receivedAt);

	Optional<StoredPayload> findLatest();

	enum SaveResult { ACCEPTED, STALE }

	record StoredPayload(String payloadJson, Instant generatedAt, Instant receivedAt) {
	}
}
