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
	// 남은 행이 수동 게시로 종결될 수 있도록 APPROVED와 같은 종결 전이(PUBLISHED·FAILED)를 허용하고,
	// 재시도 이력 행을 위해 DISPATCH_FAILED→DISPATCHED 진입도 함께 남겨 둔다.
	// dispatch 발화 경로까지 제거된(#2569) 지금
	// APPROVED→DISPATCHED·APPROVED→DISPATCH_FAILED·DISPATCH_FAILED→DISPATCHED는 도달 불가능하지만,
	// 기존 이력 행의 상태값 정리 여부를 판단하기 전까지 read 경로 호환을 위해 의도적으로 유지한다.
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
