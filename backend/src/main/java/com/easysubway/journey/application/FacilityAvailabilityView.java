package com.easysubway.journey.application;

import java.time.Instant;
import java.util.Set;

/**
 * 요청 시점에 캡처한 시설 가동 여부.
 *
 * <p>차단 대상은 route bundle의 안정 식별자인 pathway edge id로만 표현한다. 컴파일된 전환 인덱스는
 * 번들 세대마다 달라지므로 싣지 않는다. 캡처된 번들에서 해석되지 않는 edge id가 있으면 소비자는
 * 뷰 전체를 사용할 수 없는 상태로 취급한다.</p>
 */
public interface FacilityAvailabilityView {
	boolean available();

	Instant observedAt();

	Set<String> blockedPathwayEdgeIds();

	static FacilityAvailabilityView unavailable() {
		return UnavailableFacilityAvailabilityView.INSTANCE;
	}

	static FacilityAvailabilityView empty(Instant observedAt) {
		return new SimpleFacilityAvailabilityView(true, observedAt, Set.of());
	}

	static FacilityAvailabilityView blocked(Instant observedAt, Set<String> blockedPathwayEdgeIds) {
		return new SimpleFacilityAvailabilityView(true, observedAt, blockedPathwayEdgeIds);
	}
}
