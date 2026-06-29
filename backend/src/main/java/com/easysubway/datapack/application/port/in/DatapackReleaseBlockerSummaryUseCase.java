package com.easysubway.datapack.application.port.in;

import java.time.LocalDateTime;
import java.util.List;

public interface DatapackReleaseBlockerSummaryUseCase {

	DatapackReleaseBlockerSummary summarize();

	StationReleaseBlockerSummary summarizeStation(String stationId);

	record DatapackReleaseBlockerSummary(
		String candidateId,
		String scopeId,
		String status,
		long totalBlockers,
		long candidateGateBlockers,
		long aliasBlockers,
		long quarantineBlockers,
		long manualOverrideBlockers,
		long facilityBlockers,
		long routeGateBlockers,
		long manifestBlockers,
		List<ReleaseReadinessRow> readinessRows,
		LocalDateTime candidateCreatedAt
	) {

		public static DatapackReleaseBlockerSummary empty() {
			return new DatapackReleaseBlockerSummary(
				"-",
				"-",
				"확인 필요",
				0,
				0,
				0,
				0,
				0,
				0,
				0,
				0,
				List.of(
					new ReleaseReadinessRow("Source coverage", "확인 필요", 0, "candidate 없음"),
					new ReleaseReadinessRow("Validator", "확인 필요", 0, "candidate 없음"),
					new ReleaseReadinessRow("Facility evidence", "확인 필요", 0, "candidate 없음"),
					new ReleaseReadinessRow("Route gate", "확인 필요", 0, "candidate 없음"),
					new ReleaseReadinessRow("Android evidence", "확인 필요", 0, "candidate 없음"),
					new ReleaseReadinessRow("Manifest signature", "확인 필요", 0, "candidate 없음"),
					new ReleaseReadinessRow("Manual override", "확인 필요", 0, "candidate 없음")
				),
				null
			);
		}
	}

	record ReleaseReadinessRow(String label, String status, long blockerCount, String note) {
	}

	record StationReleaseBlockerSummary(
		String stationId,
		String status,
		long totalBlockers,
		List<StationReleaseBlockerRow> rows
	) {

		public static StationReleaseBlockerSummary empty(String stationId) {
			return new StationReleaseBlockerSummary(
				stationId,
				"확인 필요",
				0,
				List.of(
					new StationReleaseBlockerRow("Facility evidence", 0, "집계 전"),
					new StationReleaseBlockerRow("Route gate", 0, "집계 전")
				)
			);
		}
	}

	record StationReleaseBlockerRow(String label, long blockerCount, String status) {
	}
}
