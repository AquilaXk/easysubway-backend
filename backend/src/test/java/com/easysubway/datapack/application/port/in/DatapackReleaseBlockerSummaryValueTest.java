package com.easysubway.datapack.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DatapackReleaseBlockerSummaryValueTest {

	@Test
	void blockerSummaryListsPreserveNullAndIsolateInputAndAccessorMutation() {
		var firstReadiness = new DatapackReleaseBlockerSummaryUseCase.ReleaseReadinessRow("first", "BLOCKED", 1, "first note");
		var secondReadiness = new DatapackReleaseBlockerSummaryUseCase.ReleaseReadinessRow("second", "READY", 2, "second note");
		var readiness = new ArrayList<>(List.of(firstReadiness, secondReadiness));
		readiness.add(1, null);
		var summary = new DatapackReleaseBlockerSummaryUseCase.DatapackReleaseBlockerSummary(
			null, null, null, null, null, null, null, null, null,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, readiness, null
		);
		readiness.clear();
		assertThat(summary.readinessRows()).containsExactly(firstReadiness, null, secondReadiness);
		summary.readinessRows().clear();
		assertThat(summary.readinessRows()).containsExactly(firstReadiness, null, secondReadiness);
		var expectedReadiness = new ArrayList<>(List.of(firstReadiness, secondReadiness));
		expectedReadiness.add(1, null);
		assertThat(summary).isEqualTo(new DatapackReleaseBlockerSummaryUseCase.DatapackReleaseBlockerSummary(
			null, null, null, null, null, null, null, null, null,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, expectedReadiness, null
		));
		assertThat(new DatapackReleaseBlockerSummaryUseCase.DatapackReleaseBlockerSummary(
			null, null, null, null, null, null, null, null, null,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null
		).readinessRows()).isNull();

		var firstRow = new DatapackReleaseBlockerSummaryUseCase.StationReleaseBlockerRow("first", 1, "BLOCKED");
		var secondRow = new DatapackReleaseBlockerSummaryUseCase.StationReleaseBlockerRow("second", 2, "READY");
		var rows = new ArrayList<>(List.of(firstRow, secondRow));
		rows.add(1, null);
		var station = new DatapackReleaseBlockerSummaryUseCase.StationReleaseBlockerSummary(null, null, 0, rows);
		rows.clear();
		assertThat(station.rows()).containsExactly(firstRow, null, secondRow);
		station.rows().clear();
		assertThat(station.rows()).containsExactly(firstRow, null, secondRow);
		var expectedRows = new ArrayList<>(List.of(firstRow, secondRow));
		expectedRows.add(1, null);
		assertThat(station).isEqualTo(new DatapackReleaseBlockerSummaryUseCase.StationReleaseBlockerSummary(
			null, null, 0, expectedRows
		));
		assertThat(new DatapackReleaseBlockerSummaryUseCase.StationReleaseBlockerSummary(null, null, 0, null).rows()).isNull();
	}
}
