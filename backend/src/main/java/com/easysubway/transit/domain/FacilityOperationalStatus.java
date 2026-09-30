package com.easysubway.transit.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * {@code facility_operational_status} 한 행(#419). {@code sourceCode}는 서울교통공사 원천이 마지막으로 보고한
 * {@code oprtngSitu} 코드이며, 원천 관측 전에 관리자 확인만 있던 시설은 비어 있다.
 */
public record FacilityOperationalStatus(
	String facilityId,
	FacilityOperationalState status,
	FacilityStatusSource source,
	String sourceCode,
	Instant observedAt,
	Instant updatedAt
) {

	public FacilityOperationalStatus {
		Objects.requireNonNull(facilityId, "facilityId");
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(observedAt, "observedAt");
		Objects.requireNonNull(updatedAt, "updatedAt");
	}
}
