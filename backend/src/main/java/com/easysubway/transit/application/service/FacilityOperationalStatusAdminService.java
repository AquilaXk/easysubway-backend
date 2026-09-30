package com.easysubway.transit.application.service;

import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore;
import com.easysubway.transit.domain.FacilityOperationalState;
import com.easysubway.transit.domain.SmrtElevatorFacilityIds;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 관리자가 확인한 시설 운영 상태를 기록하는 경로(#419). 미검증 시민 제보는 여기로 쓰지 않고, 관리자가 확인한 결과만
 * {@code ADMIN_VERIFIED}로 남긴다. 대상 시설은 번들의 {@code smrt-elev} 목록에서 고른 정규 id만 받는다.
 */
@Service
public class FacilityOperationalStatusAdminService {

	private final FacilityOperationalStatusStore store;
	private final Clock clock;

	@Autowired
	public FacilityOperationalStatusAdminService(FacilityOperationalStatusStore store) {
		this(store, Clock.systemUTC());
	}

	FacilityOperationalStatusAdminService(FacilityOperationalStatusStore store, Clock clock) {
		this.store = store;
		this.clock = clock;
	}

	/**
	 * @return 기록했으면 {@code true}, 확인 시각보다 새 관측이 이미 있어 기록하지 않았으면 {@code false}
	 */
	public boolean recordAdminVerified(String facilityId, FacilityOperationalState state) {
		if (!SmrtElevatorFacilityIds.isCanonical(facilityId)) {
			throw new IllegalArgumentException("facilityId must be a canonical smrt-elev identifier");
		}
		if (state == null) {
			throw new IllegalArgumentException("state is required");
		}
		return store.recordAdminVerified(facilityId, state, clock.instant().truncatedTo(ChronoUnit.MICROS));
	}
}
