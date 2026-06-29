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
			case HIGH -> "최근 확인된 정보";
			case MEDIUM -> "일부 확인된 정보";
			case LOW -> "확인이 더 필요한 정보";
			case NEEDS_VERIFICATION -> "확인이 더 필요해요";
		};
	}

	private static String sourceLabel(com.easysubway.transit.domain.DataSourceType sourceType) {
		return switch (sourceType) {
			case ADMIN_VERIFIED -> "관리자 확인";
			case OFFICIAL_API, OFFICIAL_FILE, OPERATOR_PAGE -> "공식 안내";
			case USER_REPORT -> "사용자 제보";
			case PARTNER_FEED -> "제휴기관 안내";
		};
	}
}
