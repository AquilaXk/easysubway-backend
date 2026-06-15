package com.easysubway.quality.adapter.in.web;

import com.easysubway.quality.application.port.in.DataQualityUseCase;
import com.easysubway.quality.domain.DataQualitySummary;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class DataQualityAdminPageController {

	private final DataQualityUseCase dataQualityUseCase;

	DataQualityAdminPageController(DataQualityUseCase dataQualityUseCase) {
		this.dataQualityUseCase = dataQualityUseCase;
	}

	@GetMapping("/admin/data-quality/page")
	String dataQualityDashboardPage(Model model) {
		DataQualitySummary summary = dataQualityUseCase.summarizeDataQuality();
		model.addAttribute("summary", DataQualityDashboardView.from(summary));
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

	record DataQualityDashboardView(
		int totalStations,
		int totalExits,
		int totalFacilities,
		long needsVerificationFacilityCount,
		long missingStationVerificationDateCount,
		List<QualityCountRow> stationQualityRows,
		List<ConfidenceCountRow> exitConfidenceRows,
		List<ConfidenceCountRow> facilityConfidenceRows
	) {

		static DataQualityDashboardView from(DataQualitySummary summary) {
			return new DataQualityDashboardView(
				summary.totalStations(),
				summary.totalExits(),
				summary.totalFacilities(),
				summary.needsVerificationFacilityCount(),
				summary.missingStationVerificationDateCount(),
				qualityRows(summary.stationQualityCounts()),
				confidenceRows(summary.exitConfidenceCounts()),
				confidenceRows(summary.facilityConfidenceCounts())
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

		private static List<ConfidenceCountRow> confidenceRows(Map<DataConfidenceLevel, Long> counts) {
			return Arrays.stream(DataConfidenceLevel.values())
				.map(level -> new ConfidenceCountRow(confidenceLabel(level), counts.getOrDefault(level, 0L)))
				.toList();
		}
	}

	record QualityCountRow(String label, String description, long count) {
	}

	record ConfidenceCountRow(String label, long count) {
	}
}
