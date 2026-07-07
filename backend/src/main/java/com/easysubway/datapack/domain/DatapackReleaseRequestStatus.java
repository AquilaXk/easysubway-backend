package com.easysubway.datapack.domain;

import java.util.Map;
import java.util.Set;

public enum DatapackReleaseRequestStatus {
	REQUESTED,
	APPROVED,
	DISPATCHED,
	DISPATCH_FAILED,
	PUBLISHED,
	FAILED;

	// 전이 규칙(Part A는 REQUESTED→APPROVED만 발화. 나머지는 Part B/C가 사용).
	private static final Map<DatapackReleaseRequestStatus, Set<DatapackReleaseRequestStatus>> ALLOWED = Map.of(
		REQUESTED, Set.of(APPROVED),
		APPROVED, Set.of(DISPATCHED, DISPATCH_FAILED, PUBLISHED, FAILED),
		DISPATCHED, Set.of(PUBLISHED, FAILED),
		DISPATCH_FAILED, Set.of(DISPATCHED),
		PUBLISHED, Set.of(),
		FAILED, Set.of());

	public boolean canTransitionTo(DatapackReleaseRequestStatus next) {
		return ALLOWED.getOrDefault(this, Set.of()).contains(next);
	}
}
