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
			case NORMAL -> "정상";
			case BROKEN -> "고장";
			case UNDER_CONSTRUCTION -> "공사 중";
			case CLOSED -> "폐쇄";
			case UNKNOWN -> "확인 필요";
			case USER_REPORTED -> "사용자 제보";
			case ADMIN_VERIFIED -> "관리자 확인";
		};
	}

	private static String typeLabel(AccessibilityFacilityType type) {
		return type.label();
	}

	private static String confidenceLabel(DataConfidenceLevel confidence) {
		return switch (confidence) {
			case HIGH -> "최근 확인된 정보";
			case MEDIUM -> "일부 확인된 정보";
			case LOW -> "확인이 더 필요한 정보";
			case NEEDS_VERIFICATION -> "확인이 더 필요해요";
		};
	}

	// #2313 F1: 출처 유형 표시 라벨의 단일 원본은 DataSourceType.label()이다. 이전에는 공식 계열
	// (OFFICIAL_API/OFFICIAL_FILE/OPERATOR_PAGE)을 "공식 안내"로 묶어, 역 상세의 label() 표시와
	// 시설 요약 표시가 같은 출처인데 다른 문구로 보이는 불일치가 있었다 — label()에 위임해 해소한다.
	private static String sourceLabel(com.easysubway.transit.domain.DataSourceType sourceType) {
		return sourceType.label();
	}
}
