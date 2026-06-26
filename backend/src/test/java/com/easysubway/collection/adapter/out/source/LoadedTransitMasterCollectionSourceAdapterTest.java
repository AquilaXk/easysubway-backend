package com.easysubway.collection.adapter.out.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.domain.AccessibilityFacility;
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

@DisplayName("로딩된 도시철도 마스터 수집 source adapter")
class LoadedTransitMasterCollectionSourceAdapterTest {

	@Test
	@DisplayName("동일한 건수라도 record 내용이 바뀌면 checksum이 달라진다")
	void checksumChangesWhenRecordContentsChangeWithSameCount() {
		var firstSnapshot = new LoadedTransitMasterCollectionSourceAdapter(new FakeTransitMasterPort("상록수"))
			.fetch();
		var secondSnapshot = new LoadedTransitMasterCollectionSourceAdapter(new FakeTransitMasterPort("한대앞"))
			.fetch();

		assertThat(firstSnapshot.recordCount()).isEqualTo(secondSnapshot.recordCount());
		assertThat(firstSnapshot.checksum()).isNotEqualTo(secondSnapshot.checksum());
	}

	private record FakeTransitMasterPort(String stationName) implements LoadTransitMasterPort {

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
				"station-a",
				stationName,
				"Station A",
				"수도권",
				BigDecimal.valueOf(37.3),
				BigDecimal.valueOf(126.8),
				DataQualityLevel.LEVEL_1,
				DataSourceType.OFFICIAL_FILE,
				LocalDate.of(2026, 6, 16),
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
			return List.of();
		}
	}
}
