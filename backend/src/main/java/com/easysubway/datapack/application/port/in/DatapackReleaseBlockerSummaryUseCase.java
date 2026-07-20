package com.easysubway.datapack.application.port.in;

import java.time.LocalDateTime;
import java.util.List;

public interface DatapackReleaseBlockerSummaryUseCase {

	DatapackReleaseBlockerSummary summarize();

	StationReleaseBlockerSummary summarizeStation(String stationId);

	record DatapackReleaseBlockerSummary(
		String candidateId,
		String scopeId,
		String sourceSnapshotSetHash,
		String manifestSha256,
		String evidenceBundleSha256,
		String evidenceWorkflowRunUrl,
		String productionCandidateId,
		String rollbackCandidateId,
		String status,
		long totalBlockers,
		long candidateGateBlockers,
		long aliasBlockers,
		long quarantineBlockers,
		long sourceFreshnessBlockers,
		long manualOverrideBlockers,
		long facilityBlockers,
		long routeGateBlockers,
		long callbackReconciliationBlockers,
		long evidenceBundleBlockers,
		long manifestBlockers,
		List<ReleaseReadinessRow> readinessRows,
		LocalDateTime candidateCreatedAt
	) {

		public static DatapackReleaseBlockerSummary empty() {
			return new DatapackReleaseBlockerSummary(
				"-",
				"-",
				"-",
				"-",
				"-",
				"-",
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
				0,
				0,
				0,
				List.of(
					new ReleaseReadinessRow("소스 커버리지", "확인 필요", 0, "candidate 없음"),
					new ReleaseReadinessRow("소스 최신성", "확인 필요", 0, "source snapshot 없음"),
					new ReleaseReadinessRow("검증기", "확인 필요", 0, "candidate 없음"),
					new ReleaseReadinessRow("시설 근거", "확인 필요", 0, "candidate 없음"),
					new ReleaseReadinessRow("경로 게이트", "확인 필요", 0, "candidate 없음"),
					new ReleaseReadinessRow("Android 증거", "확인 필요", 0, "candidate 없음"),
					new ReleaseReadinessRow("매니페스트 서명", "확인 필요", 0, "candidate 없음"),
					new ReleaseReadinessRow("콜백 정합성 확인", "확인 필요", 0, "delivery 없음"),
					new ReleaseReadinessRow("수동 오버라이드", "확인 필요", 0, "candidate 없음")
				),
				null
			);
		}

		public boolean productionPromoteAllowed() {
			return "READY".equals(status);
		}

		public String productionPromoteReason() {
			if (productionPromoteAllowed()) {
				return "프로덕션 반영 가능";
			}
			return "프로덕션 반영 차단: 차단 요인 " + totalBlockers + "건";
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
					new StationReleaseBlockerRow("시설 근거", 0, "집계 전"),
					new StationReleaseBlockerRow("경로 게이트", 0, "집계 전")
				)
			);
		}
	}

	record StationReleaseBlockerRow(String label, long blockerCount, String status) {
	}
}
