package com.easysubway.transit.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationWithLines;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("시설 상태 행 모델")
class FacilityStatusRowTest {

	@ParameterizedTest
	@EnumSource(AccessibilityFacilityStatus.class)
	@DisplayName("모든 접근성 시설 상태의 라벨은 도메인 라벨과 정확히 일치한다")
	void statusLabelMatchesDomainLabel(AccessibilityFacilityStatus status) {
		assertThat(FacilityStatusRow.statusLabel(status)).isEqualTo(status.label());
	}

	@Test
	@DisplayName("시설 상태별 구체적인 한국어 라벨을 매핑한다")
	void statusLabelMapsAllIndividualStatuses() {
		assertThat(FacilityStatusRow.statusLabel(AccessibilityFacilityStatus.NORMAL)).isEqualTo("정상");
		assertThat(FacilityStatusRow.statusLabel(AccessibilityFacilityStatus.BROKEN)).isEqualTo("고장");
		assertThat(FacilityStatusRow.statusLabel(AccessibilityFacilityStatus.UNDER_CONSTRUCTION)).isEqualTo("공사 중");
		assertThat(FacilityStatusRow.statusLabel(AccessibilityFacilityStatus.CLOSED)).isEqualTo("폐쇄");
		assertThat(FacilityStatusRow.statusLabel(AccessibilityFacilityStatus.UNKNOWN)).isEqualTo("확인 필요");
		assertThat(FacilityStatusRow.statusLabel(AccessibilityFacilityStatus.USER_REPORTED)).isEqualTo("사용자 제보");
		assertThat(FacilityStatusRow.statusLabel(AccessibilityFacilityStatus.ADMIN_VERIFIED)).isEqualTo("관리자 확인");
	}

	@ParameterizedTest
	@EnumSource(DataConfidenceLevel.class)
	@DisplayName("모든 데이터 신뢰도 수준의 라벨은 도메인 라벨과 정확히 일치한다")
	void confidenceLabelMatchesDomainLabel(DataConfidenceLevel confidence) {
		assertThat(FacilityStatusRow.confidenceLabel(confidence)).isEqualTo(confidence.label());
	}

	@Test
	@DisplayName("신뢰도 수준별 구체적인 한국어 라벨을 매핑한다")
	void confidenceLabelMapsAllIndividualLevels() {
		assertThat(FacilityStatusRow.confidenceLabel(DataConfidenceLevel.HIGH)).isEqualTo("최근 확인된 정보");
		assertThat(FacilityStatusRow.confidenceLabel(DataConfidenceLevel.MEDIUM)).isEqualTo("일부 확인된 정보");
		assertThat(FacilityStatusRow.confidenceLabel(DataConfidenceLevel.LOW)).isEqualTo("확인이 더 필요한 정보");
		assertThat(FacilityStatusRow.confidenceLabel(DataConfidenceLevel.NEEDS_VERIFICATION)).isEqualTo("확인이 더 필요해요");
	}

	@Test
	@DisplayName("역 및 시설 정보로부터 시설 상태 행을 정확히 생성한다")
	void fromConstructsRowWithAllLabels() {
		StationWithLines station = new StationWithLines(
			new Station(
				"station-101",
				"서울역",
				"Seoul Station",
				"수도권",
				BigDecimal.valueOf(37.555),
				BigDecimal.valueOf(126.972),
				DataQualityLevel.LEVEL_1,
				DataSourceType.ADMIN_VERIFIED,
				LocalDate.of(2026, 1, 1),
				true
			),
			List.of()
		);

		AccessibilityFacility facility = new AccessibilityFacility(
			"fac-1",
			"station-101",
			"exit-1",
			AccessibilityFacilityType.ELEVATOR,
			"1번 출구 엘리베이터",
			"B1",
			"1F",
			BigDecimal.valueOf(37.555),
			BigDecimal.valueOf(126.972),
			"상세 설명",
			AccessibilityFacilityStatus.ADMIN_VERIFIED,
			DataConfidenceLevel.NEEDS_VERIFICATION,
			DataSourceType.OFFICIAL_API,
			LocalDate.of(2026, 3, 15)
		);

		FacilityStatusRow row = FacilityStatusRow.from(station, facility);

		assertThat(row.facilityId()).isEqualTo("fac-1");
		assertThat(row.stationId()).isEqualTo("station-101");
		assertThat(row.stationName()).isEqualTo("서울역");
		assertThat(row.facilityName()).isEqualTo("1번 출구 엘리베이터");
		assertThat(row.typeLabel()).isEqualTo("엘리베이터");
		assertThat(row.status()).isEqualTo(AccessibilityFacilityStatus.ADMIN_VERIFIED);
		assertThat(row.statusLabel()).isEqualTo("관리자 확인");
		assertThat(row.confidenceLabel()).isEqualTo("확인이 더 필요해요");
		assertThat(row.sourceLabel()).isEqualTo("공식 API");
		assertThat(row.lastUpdatedAt()).isEqualTo(LocalDate.of(2026, 3, 15));
	}
}
