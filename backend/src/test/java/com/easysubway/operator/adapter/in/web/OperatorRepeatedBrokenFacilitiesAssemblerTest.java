package com.easysubway.operator.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.easysubway.report.application.port.in.FacilityReportUseCase;
import com.easysubway.report.domain.RepeatedBrokenFacilityReportSummary;
import com.easysubway.transit.application.port.in.TransitMasterQueryUseCase;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationNotFoundException;
import com.easysubway.transit.domain.StationWithLines;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("운영기관 반복 고장 시설 통계 조립")
class OperatorRepeatedBrokenFacilitiesAssemblerTest {

	@Test
	@DisplayName("현재 마스터데이터와 맞지 않는 반복 고장 시설 집계는 제외한다")
	void assembleSkipsStaleRepeatedBrokenFacilityTargets() {
		FacilityReportUseCase facilityReportUseCase = mock(FacilityReportUseCase.class);
		TransitMasterQueryUseCase transitMasterQueryUseCase = mock(TransitMasterQueryUseCase.class);
		OperatorRepeatedBrokenFacilitiesAssembler assembler = new OperatorRepeatedBrokenFacilitiesAssembler(
			facilityReportUseCase,
			transitMasterQueryUseCase
		);
		when(facilityReportUseCase.listRepeatedBrokenReportFacilities()).thenReturn(List.of(
			new RepeatedBrokenFacilityReportSummary("station-sangnoksu", "facility-removed", 2),
			new RepeatedBrokenFacilityReportSummary("station-removed", "facility-old", 3),
			new RepeatedBrokenFacilityReportSummary("station-sangnoksu", "facility-sangnoksu-elevator-1", 4)
		));
		when(transitMasterQueryUseCase.getStation("station-sangnoksu")).thenReturn(station("station-sangnoksu", "상록수"));
		when(transitMasterQueryUseCase.getStation("station-removed")).thenThrow(new StationNotFoundException());
		when(transitMasterQueryUseCase.listStationFacilities("station-sangnoksu"))
			.thenReturn(List.of(facility("facility-sangnoksu-elevator-1", "1번 출구 엘리베이터")));

		OperatorRepeatedBrokenFacilitiesView view = assembler.assemble();

		assertThat(view.totalRepeatedFacilityCount()).isEqualTo(1);
		assertThat(view.rows()).hasSize(1);
		OperatorRepeatedBrokenFacilitiesView.RepeatedBrokenFacilityRow row = view.rows().getFirst();
		assertThat(row.stationName()).isEqualTo("상록수");
		assertThat(row.facilityName()).isEqualTo("1번 출구 엘리베이터");
		assertThat(row.statusLabel()).isEqualTo("정상");
		assertThat(row.reportCount()).isEqualTo(4);
	}

	private static StationWithLines station(String id, String nameKo) {
		return new StationWithLines(
			new Station(
				id,
				nameKo,
				"Sangnoksu",
				"수도권",
				BigDecimal.valueOf(37.302),
				BigDecimal.valueOf(126.866),
				DataQualityLevel.LEVEL_1,
				DataSourceType.ADMIN_VERIFIED,
				LocalDate.of(2026, 1, 1),
				true
			),
			List.of()
		);
	}

	private static AccessibilityFacility facility(String id, String name) {
		return new AccessibilityFacility(
			id,
			"station-sangnoksu",
			"exit-sangnoksu-1",
			AccessibilityFacilityType.ELEVATOR,
			name,
			"B1",
			"1F",
			BigDecimal.valueOf(37.302),
			BigDecimal.valueOf(126.866),
			"승강장 연결",
			AccessibilityFacilityStatus.NORMAL,
			DataConfidenceLevel.HIGH,
			DataSourceType.ADMIN_VERIFIED,
			LocalDate.of(2026, 1, 1)
		);
	}
}
