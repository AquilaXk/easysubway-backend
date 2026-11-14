package com.easysubway.journey.bundle;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 컴파일된 경로 번들이 accessibility 구성요소의 {@code facilities} 표에서 읽어 둔 서울교통공사 엘리베이터
 * ({@code smrt-elev:}) 시설 목록(#419)과 시설 id별 전환 간선 id 색인을 제공한다.
 */
public interface RouteBundleFacilityCatalog {

	String SMRT_ELEVATOR_PREFIX = "smrt-elev:";

	/** 시설 id 바이트 순으로 정렬된 {@code smrt-elev:} 시설. */
	List<Facility> smrtElevatorFacilities();

	/** 지정한 시설 id에 연결되어 컴파일된 전환 간선 id 목록(없으면 빈 집합). */
	default Set<String> transitionEdgeIdsForFacility(String facilityId) {
		return Set.of();
	}

	/** 번들에 포함된 정본 역 id 집합(id 바이트 순, 없으면 빈 집합). */
	default Set<String> canonicalStationIds() {
		return Set.of();
	}

	/** 번들 accessibility 구성요소 {@code facilities} 표의 전체 시설 목록(id 바이트 순). */
	default List<BundleFacility> bundleFacilities() {
		return List.of();
	}

	/** 번들 accessibility 구성요소 {@code station_exits} 표의 출입구 목록(id 바이트 순). */
	default List<BundleStationExit> bundleStationExits() {
		return List.of();
	}

	record Facility(String id, String name) {

		public Facility {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(name, "name");
		}
	}

	record BundleFacility(
		String id,
		String stationId,
		String exitId,
		String type,
		String name,
		String floorFrom,
		String floorTo,
		String description,
		String status,
		String operationalStatus,
		String provenanceKind,
		long verifiedAtEpochSeconds,
		int confidence
	) {

		public BundleFacility {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(stationId, "stationId");
			exitId = exitId == null || exitId.isBlank() ? null : exitId;
			Objects.requireNonNull(type, "type");
			Objects.requireNonNull(name, "name");
			floorFrom = floorFrom == null ? "" : floorFrom;
			floorTo = floorTo == null ? "" : floorTo;
			description = description == null ? "" : description;
			status = status == null || status.isBlank() ? "UNKNOWN" : status;
			operationalStatus = operationalStatus == null || operationalStatus.isBlank() ? "UNKNOWN" : operationalStatus;
			provenanceKind = provenanceKind == null || provenanceKind.isBlank() ? "UNKNOWN" : provenanceKind;
		}
	}

	record BundleStationExit(
		String id,
		String stationId,
		String exitNumber,
		String description,
		Double latitude,
		Double longitude,
		boolean hasElevatorConnection,
		String dataSourceType
	) {

		public BundleStationExit {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(stationId, "stationId");
			Objects.requireNonNull(exitNumber, "exitNumber");
			description = description == null ? "" : description;
			dataSourceType = dataSourceType == null || dataSourceType.isBlank() ? "OFFICIAL_FILE" : dataSourceType;
		}
	}
}
