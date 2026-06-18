package com.easysubway.operator.adapter.in.web;

import com.easysubway.quality.application.port.in.DataQualityUseCase;
import com.easysubway.quality.domain.AccessibilityImprovementPriority;
import com.easysubway.quality.domain.DataQualitySummary;
import com.easysubway.quality.domain.RegionDataQualitySummary;
import com.easysubway.quality.domain.StationAccessibilityScore;
import com.easysubway.transit.application.port.in.TransitMasterQueryUseCase;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.StationNotFoundException;
import com.easysubway.transit.domain.StationWithLines;
import com.easysubway.transit.domain.TransitRegionSummary;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class OperatorAccessibilityReportPageController {

	private final DataQualityUseCase dataQualityUseCase;
	private final TransitMasterQueryUseCase transitMasterQueryUseCase;

	OperatorAccessibilityReportPageController(
		DataQualityUseCase dataQualityUseCase,
		TransitMasterQueryUseCase transitMasterQueryUseCase
	) {
		this.dataQualityUseCase = dataQualityUseCase;
		this.transitMasterQueryUseCase = transitMasterQueryUseCase;
	}

	@GetMapping("/operator/accessibility-report/page")
	String accessibilityReportPage(Model model) {
		DataQualitySummary summary = dataQualityUseCase.summarizeDataQuality();
		List<TransitRegionSummary> regions = transitMasterQueryUseCase.listRegions();
		List<StationAccessibilityScoreRow> stationAccessibilityScoreRows = summary
			.stationAccessibilityScores()
			.stream()
			.map(StationAccessibilityScoreRow::from)
			.toList();
		List<AccessibilityImprovementPriorityRow> accessibilityImprovementPriorityRows = summary
			.accessibilityImprovementPriorities()
			.stream()
			.map(this::accessibilityImprovementPriorityRow)
			.flatMap(Optional::stream)
			.toList();
		model.addAttribute(
			"report",
			OperatorAccessibilityReportView.from(
				summary,
				regions,
				stationAccessibilityScoreRows,
				accessibilityImprovementPriorityRows
			)
		);
		return "operator/accessibility-report";
	}

	private Optional<AccessibilityImprovementPriorityRow> accessibilityImprovementPriorityRow(
		AccessibilityImprovementPriority priority
	) {
		try {
			StationWithLines station = transitMasterQueryUseCase.getStation(priority.stationId());
			return transitMasterQueryUseCase.listStationFacilities(priority.stationId())
				.stream()
				.filter(candidate -> candidate.id().equals(priority.facilityId()))
				.findFirst()
				.map(facility -> new AccessibilityImprovementPriorityRow(
					station.station().nameKo(),
					facility.name(),
					priority.priorityScore(),
					String.join(", ", priority.reasons())
				));
		} catch (StationNotFoundException exception) {
			return Optional.empty();
		}
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

	record OperatorAccessibilityReportView(
		int totalStations,
		int totalFacilities,
		long needsVerificationFacilityCount,
		long delayedFacilityStatusCount,
		long missingStationVerificationDateCount,
		List<QualityCountRow> stationQualityRows,
		List<RegionQualityRow> regionQualityRows,
		List<StationAccessibilityScoreRow> stationAccessibilityScoreRows,
		List<AccessibilityImprovementPriorityRow> accessibilityImprovementPriorityRows
	) {

		static OperatorAccessibilityReportView from(
			DataQualitySummary summary,
			List<TransitRegionSummary> regions,
			List<StationAccessibilityScoreRow> stationAccessibilityScoreRows,
			List<AccessibilityImprovementPriorityRow> accessibilityImprovementPriorityRows
		) {
			return new OperatorAccessibilityReportView(
				summary.totalStations(),
				summary.totalFacilities(),
				summary.needsVerificationFacilityCount(),
				summary.delayedFacilityStatusCount(),
				summary.missingStationVerificationDateCount(),
				qualityRows(summary.stationQualityCounts()),
				regionQualityRows(summary.regionSummaries(), regions),
				stationAccessibilityScoreRows,
				accessibilityImprovementPriorityRows
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

	record StationAccessibilityScoreRow(
		String stationName,
		String region,
		int score,
		String reasons
	) {

		static StationAccessibilityScoreRow from(StationAccessibilityScore score) {
			return new StationAccessibilityScoreRow(
				score.stationName(),
				score.region(),
				score.score(),
				String.join(", ", score.reasons())
			);
		}
	}

	record AccessibilityImprovementPriorityRow(
		String stationName,
		String facilityName,
		int priorityScore,
		String reasons
	) {
	}
}
