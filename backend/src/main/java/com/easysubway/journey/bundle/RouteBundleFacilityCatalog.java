package com.easysubway.journey.bundle;

import java.util.List;
import java.util.Objects;

/**
 * 컴파일된 경로 번들이 accessibility 구성요소의 {@code facilities} 표에서 읽어 둔 서울교통공사 엘리베이터
 * ({@code smrt-elev:}) 시설 목록(#419). 관리자 확인 기록이 시설을 고를 때만 쓰는 읽기 전용 목록이다.
 */
public interface RouteBundleFacilityCatalog {

	String SMRT_ELEVATOR_PREFIX = "smrt-elev:";

	/** 시설 id 바이트 순으로 정렬된 {@code smrt-elev:} 시설. */
	List<Facility> smrtElevatorFacilities();

	record Facility(String id, String name) {

		public Facility {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(name, "name");
		}
	}
}
