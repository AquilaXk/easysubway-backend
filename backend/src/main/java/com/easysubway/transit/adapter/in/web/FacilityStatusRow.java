package com.easysubway.transit.adapter.in.web;

import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.StationWithLines;
import java.time.LocalDate;

record FacilityStatusRow(
	String facilityId,
	String stationId,
	String stationName,
	String facilityName,
	String typeLabel,
	AccessibilityFacilityStatus status,
	String statusLabel,
	String confidenceLabel,
	String sourceLabel,
	LocalDate lastUpdatedAt
) {

	static FacilityStatusRow from(StationWithLines station, AccessibilityFacility facility) {
		return new FacilityStatusRow(
			facility.id(),
			station.station().id(),
			station.station().nameKo(),
			facility.name(),
			typeLabel(facility.type()),
			facility.status(),
			statusLabel(facility.status()),
			confidenceLabel(facility.dataConfidence()),
			sourceLabel(facility.dataSourceType()),
			facility.lastUpdatedAt()
		);
	}

	static String statusLabel(AccessibilityFacilityStatus status) {
		return switch (status) {
			case NORMAL -> status.label();
			case BROKEN -> status.label();
			case UNDER_CONSTRUCTION -> status.label();
			case CLOSED -> status.label();
			case UNKNOWN -> status.label();
			case USER_REPORTED -> status.label();
			case ADMIN_VERIFIED -> status.label();
		};
	}

	private static String typeLabel(AccessibilityFacilityType type) {
		return type.label();
	}

	static String confidenceLabel(DataConfidenceLevel confidence) {
		return switch (confidence) {
			case HIGH -> confidence.label();
			case MEDIUM -> confidence.label();
			case LOW -> confidence.label();
			case NEEDS_VERIFICATION -> confidence.label();
		};
	}

	// #2313 F1: 출처 유형 표시 라벨의 단일 원본은 DataSourceType.label()이다. 이전에는 공식 계열
	// (OFFICIAL_API/OFFICIAL_FILE/OPERATOR_PAGE)을 "공식 안내"로 묶어, 역 상세의 label() 표시와
	// 시설 요약 표시가 같은 출처인데 다른 문구로 보이는 불일치가 있었다 — label()에 위임해 해소한다.
	private static String sourceLabel(com.easysubway.transit.domain.DataSourceType sourceType) {
		return sourceType.label();
	}
}
