package com.easysubway.transit.application.port.out;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 활성 경로 번들의 서울교통공사 엘리베이터({@code smrt-elev:}) 시설 목록을 읽는 읽기 전용 포트(#419 QA 결정 추가 3).
 * 관리자가 확인 기록할 시설은 이 목록에서만 고른다.
 */
public interface LoadBundleElevatorFacilitiesPort {

	/** 활성 번들이 없거나(미적재·만료) 읽을 수 없으면 비어 있다. 이때 대신 쓸 목록은 없다. */
	Optional<List<BundleElevatorFacility>> loadActiveBundleElevatorFacilities();

	record BundleElevatorFacility(String facilityId, String name) {

		public BundleElevatorFacility {
			Objects.requireNonNull(facilityId, "facilityId");
			Objects.requireNonNull(name, "name");
		}
	}
}
