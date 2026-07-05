package com.easysubway.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관리자 마스터 라벨 resolver")
class AdminMasterLabelResolverTest {

	private final AdminMasterLabelResolver resolver = new AdminMasterLabelResolver(new FakeTransitMasterPort());

	@Test
	@DisplayName("역 ID는 이름(코드)로, 마스터에 없는 코드는 코드 그대로 해석한다")
	void resolvesStationLabelsWithFallback() {
		var labels = resolver.stationLabels(List.of("station-sangnoksu", "station-unknown", ""));

		assertThat(labels).containsEntry("station-sangnoksu", "상록수(station-sangnoksu)");
		assertThat(labels).containsEntry("station-unknown", "station-unknown");
		assertThat(labels).doesNotContainKey("");
	}

	@Test
	@DisplayName("시설 ID는 시설명(코드)로 해석한다")
	void resolvesFacilityLabels() {
		var labels = resolver.facilityLabels(List.of("facility-elevator-1"));

		assertThat(labels).containsEntry("facility-elevator-1", "1번 출구 엘리베이터(facility-elevator-1)");
	}

	@Test
	@DisplayName("빈 요청은 빈 결과를 준다")
	void emptyRequestReturnsEmpty() {
		assertThat(resolver.stationLabels(List.of())).isEmpty();
		assertThat(resolver.facilityLabels(List.of("  "))).isEmpty();
	}

	@Test
	@DisplayName("이름이 없으면 코드만, 코드도 없으면 빈 문자열로 표기한다")
	void labelFormatterHandlesBlanks() {
		assertThat(AdminMasterLabelResolver.label("상록수", "code-1")).isEqualTo("상록수(code-1)");
		assertThat(AdminMasterLabelResolver.label(" ", "code-1")).isEqualTo("code-1");
		assertThat(AdminMasterLabelResolver.label(null, null)).isEqualTo("");
	}

	private static final class FakeTransitMasterPort implements LoadTransitMasterPort {

		@Override
		public List<TransitOperator> loadOperators() {
			return List.of();
		}

		@Override
		public List<SubwayLine> loadLines() {
			return List.of();
		}

		@Override
		public List<Station> loadStations() {
			return List.of(new Station(
				"station-sangnoksu",
				"상록수",
				"Sangnoksu",
				"수도권",
				BigDecimal.valueOf(37.302),
				BigDecimal.valueOf(126.866),
				DataQualityLevel.LEVEL_1,
				DataSourceType.ADMIN_VERIFIED,
				LocalDate.of(2026, 1, 1),
				true
			));
		}

		@Override
		public List<StationLine> loadStationLines() {
			return List.of();
		}

		@Override
		public List<StationExit> loadStationExits() {
			return List.of();
		}

		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() {
			return List.of(new AccessibilityFacility(
				"facility-elevator-1",
				"station-sangnoksu",
				"exit-1",
				AccessibilityFacilityType.ELEVATOR,
				"1번 출구 엘리베이터",
				"B1",
				"1",
				BigDecimal.valueOf(37.302),
				BigDecimal.valueOf(126.866),
				"엘리베이터",
				AccessibilityFacilityStatus.NORMAL,
				DataConfidenceLevel.HIGH,
				DataSourceType.OFFICIAL_API,
				LocalDate.of(2026, 1, 1)
			));
		}
	}
}
