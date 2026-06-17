package com.easysubway.quality.adapter.in.web;

import com.easysubway.quality.application.port.in.DataQualityUseCase;
import com.easysubway.quality.domain.DataQualitySummary;
import com.easysubway.quality.domain.RegionDataQualitySummary;
import com.easysubway.transit.application.port.in.TransitMasterQueryUseCase;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.TransitRegionSummary;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class DataQualityAdminPageController {

	private final DataQualityUseCase dataQualityUseCase;
	private final TransitMasterQueryUseCase transitMasterQueryUseCase;

	DataQualityAdminPageController(
		DataQualityUseCase dataQualityUseCase,
		TransitMasterQueryUseCase transitMasterQueryUseCase
	) {
		this.dataQualityUseCase = dataQualityUseCase;
		this.transitMasterQueryUseCase = transitMasterQueryUseCase;
	}

	@GetMapping("/admin/data-quality/page")
	String dataQualityDashboardPage(Model model) {
		DataQualitySummary summary = dataQualityUseCase.summarizeDataQuality();
		List<TransitRegionSummary> regions = transitMasterQueryUseCase.listRegions();
		model.addAttribute("summary", DataQualityDashboardView.from(summary, regions));
		return "admin/quality/dashboard";
	}

	private static String qualityLabel(DataQualityLevel level) {
		return switch (level) {
			case LEVEL_1 -> "Level 1";
			case LEVEL_2 -> "Level 2";
			case LEVEL_3 -> "Level 3";
			case LEVEL_4 -> "Level 4";
		};
	}

	private static String qualityDescription(DataQualityLevel level) {
		return switch (level) {
			case LEVEL_1 -> "기본 정보 확인";
			case LEVEL_2 -> "일부 정보 확인";
			case LEVEL_3 -> "정보 보강 필요";
			case LEVEL_4 -> "제보 필요";
		};
	}

	private static String confidenceLabel(DataConfidenceLevel level) {
		return switch (level) {
			case HIGH -> "높음";
			case MEDIUM -> "보통";
			case LOW -> "낮음";
			case NEEDS_VERIFICATION -> "확인 필요";
		};
	}

	private static String statusLabel(AccessibilityFacilityStatus status) {
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

	record DataQualityDashboardView(
		int totalStations,
		int totalExits,
		int totalFacilities,
		long needsVerificationFacilityCount,
		long delayedFacilityStatusCount,
		long missingStationVerificationDateCount,
		List<QualityCountRow> stationQualityRows,
		List<RegionQualityRow> regionQualityRows,
		List<ConfidenceCountRow> exitConfidenceRows,
		List<ConfidenceCountRow> facilityConfidenceRows,
		List<FacilityStatusDelayRow> facilityStatusDelayRows
	) {

		static DataQualityDashboardView from(DataQualitySummary summary, List<TransitRegionSummary> regions) {
				return new DataQualityDashboardView(
					summary.totalStations(),
					summary.totalExits(),
					summary.totalFacilities(),
					summary.needsVerificationFacilityCount(),
					summary.delayedFacilityStatusCount(),
					summary.missingStationVerificationDateCount(),
					qualityRows(summary.stationQualityCounts()),
					regionQualityRows(summary.regionSummaries(), regions),
					confidenceRows(summary.exitConfidenceCounts()),
					confidenceRows(summary.facilityConfidenceCounts()),
					facilityStatusDelayRows(summary.delayedFacilityStatusCounts())
				);
		}

		private static List<QualityCountRow> qualityRows(Map<DataQualityLevel, Long> counts) {
			return Arrays.stream(DataQualityLevel.values())
				.map(level -> new QualityCountRow(
					qualityLabel(level),
					qualityDescription(level),
					counts.getOrDefault(level, 0L)
				))
				.toList();
		}

		private static List<RegionQualityRow> regionQualityRows(
			List<RegionDataQualitySummary> regionSummaries,
			List<TransitRegionSummary> regions
		) {
			Map<String, TransitRegionSummary> regionsByName = regions.stream()
				.collect(Collectors.toMap(TransitRegionSummary::name, region -> region));
			return regionSummaries.stream()
				.map(region -> {
					TransitRegionSummary masterRegion = regionsByName.get(region.name());
					return new RegionQualityRow(
						region.name(),
						masterRegion == null ? 0 : masterRegion.operatorCount(),
						masterRegion == null ? 0 : masterRegion.lineCount(),
						region.stationCount(),
						region.stationQualityCounts().getOrDefault(DataQualityLevel.LEVEL_1, 0L),
						region.stationQualityCounts().getOrDefault(DataQualityLevel.LEVEL_2, 0L),
						region.stationQualityCounts().getOrDefault(DataQualityLevel.LEVEL_3, 0L),
						region.stationQualityCounts().getOrDefault(DataQualityLevel.LEVEL_4, 0L)
					);
				})
				.toList();
		}

		private static List<ConfidenceCountRow> confidenceRows(Map<DataConfidenceLevel, Long> counts) {
			return Arrays.stream(DataConfidenceLevel.values())
				.map(level -> new ConfidenceCountRow(confidenceLabel(level), counts.getOrDefault(level, 0L)))
				.toList();
		}

		private static List<FacilityStatusDelayRow> facilityStatusDelayRows(
			Map<AccessibilityFacilityStatus, Long> counts
		) {
			return Arrays.stream(AccessibilityFacilityStatus.values())
				.map(status -> new FacilityStatusDelayRow(statusLabel(status), counts.getOrDefault(status, 0L)))
				.toList();
		}
	}

	record QualityCountRow(String label, String description, long count) {
	}

	record RegionQualityRow(
		String name,
		int operatorCount,
		int lineCount,
		int stationCount,
		long level1Count,
		long level2Count,
		long level3Count,
		long level4Count
	) {
	}

	record ConfidenceCountRow(String label, long count) {
	}

	record FacilityStatusDelayRow(String statusLabel, long count) {
	}
}
