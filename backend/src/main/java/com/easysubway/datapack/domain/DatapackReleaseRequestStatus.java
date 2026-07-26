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
	// DISPATCH_FAILED는 backend dispatch 발화가 제거된 뒤(#2564) 더 이상 생성되지 않는 이력 상태다.
	// 남은 행이 수동 게시로 종결될 수 있도록 APPROVED와 같은 종결 전이(PUBLISHED·FAILED)를 허용한다.
	private static final Map<DatapackReleaseRequestStatus, Set<DatapackReleaseRequestStatus>> ALLOWED = Map.of(
		REQUESTED, Set.of(APPROVED),
		APPROVED, Set.of(DISPATCHED, DISPATCH_FAILED, PUBLISHED, FAILED),
		DISPATCHED, Set.of(PUBLISHED, FAILED),
		DISPATCH_FAILED, Set.of(DISPATCHED, PUBLISHED, FAILED),
		PUBLISHED, Set.of(),
		FAILED, Set.of());

	public boolean canTransitionTo(DatapackReleaseRequestStatus next) {
		return ALLOWED.getOrDefault(this, Set.of()).contains(next);
	}
}
