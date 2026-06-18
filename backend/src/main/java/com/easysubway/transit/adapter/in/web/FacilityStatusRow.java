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
		return switch (type) {
			case ELEVATOR -> "엘리베이터";
			case ESCALATOR -> "에스컬레이터";
			case WHEELCHAIR_LIFT -> "휠체어 리프트";
			case RAMP -> "경사로";
			case ACCESSIBLE_TOILET -> "장애인 화장실";
			case TOILET -> "화장실";
			case NURSING_ROOM -> "수유실";
			case CUSTOMER_CENTER -> "고객센터";
		};
	}

	private static String confidenceLabel(DataConfidenceLevel confidence) {
		return switch (confidence) {
			case HIGH -> "정보 신뢰도 높음";
			case MEDIUM -> "정보 신뢰도 보통";
			case LOW -> "정보 신뢰도 낮음";
			case NEEDS_VERIFICATION -> "정보 확인 필요";
		};
	}
}
