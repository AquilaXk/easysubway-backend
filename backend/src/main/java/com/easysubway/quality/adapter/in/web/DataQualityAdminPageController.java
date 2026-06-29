package com.easysubway.quality.adapter.in.web;

import com.easysubway.common.web.WebMessageResolver;
import com.easysubway.quality.application.port.in.DataQualityUseCase;
import com.easysubway.quality.domain.AccessibilityImprovementPriority;
import com.easysubway.quality.domain.DataQualitySummary;
import com.easysubway.quality.domain.RegionDataQualitySummary;
import com.easysubway.quality.domain.StationAccessibilityScore;
import com.easysubway.report.application.port.in.FacilityReportUseCase;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.RepeatedBrokenFacilityReportSummary;
import com.easysubway.transit.application.port.in.TransitMasterQueryUseCase;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.DataConfidenceLevel;
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
class DataQualityAdminPageController {

	private final DataQualityUseCase dataQualityUseCase;
	private final TransitMasterQueryUseCase transitMasterQueryUseCase;
	private final FacilityReportUseCase facilityReportUseCase;
	private final WebMessageResolver messages;

	DataQualityAdminPageController(
		DataQualityUseCase dataQualityUseCase,
		TransitMasterQueryUseCase transitMasterQueryUseCase,
		FacilityReportUseCase facilityReportUseCase,
		WebMessageResolver messages
	) {
		this.dataQualityUseCase = dataQualityUseCase;
		this.transitMasterQueryUseCase = transitMasterQueryUseCase;
		this.facilityReportUseCase = facilityReportUseCase;
		this.messages = messages;
	}

	@GetMapping("/admin/data-quality/page")
	String dataQualityDashboardPage(Model model) {
		DataQualitySummary summary = dataQualityUseCase.summarizeDataQuality();
		List<TransitRegionSummary> regions = transitMasterQueryUseCase.listRegions();
		Map<FacilityReportStatus, Long> reportStatusCounts = facilityReportUseCase.countReportsByStatus();
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
		List<RepeatedBrokenFacilityRow> repeatedBrokenFacilityRows = facilityReportUseCase
			.listRepeatedBrokenReportFacilities()
			.stream()
			.map(this::repeatedBrokenFacilityRow)
			.flatMap(Optional::stream)
			.toList();
		model.addAttribute(
			"summary",
			DataQualityDashboardView.from(
				summary,
				regions,
				reportStatusCounts,
				stationAccessibilityScoreRows,
				accessibilityImprovementPriorityRows,
				repeatedBrokenFacilityRows,
				messages
			)
		);
		return "admin/quality/dashboard";
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

	private Optional<RepeatedBrokenFacilityRow> repeatedBrokenFacilityRow(RepeatedBrokenFacilityReportSummary summary) {
		try {
			StationWithLines station = transitMasterQueryUseCase.getStation(summary.stationId());
			return transitMasterQueryUseCase.listStationFacilities(summary.stationId())
				.stream()
				.filter(candidate -> candidate.id().equals(summary.facilityId()))
				.findFirst()
				.map(facility -> new RepeatedBrokenFacilityRow(
					station.station().nameKo(),
					facility.name(),
					messages.enumLabel("admin.facility.status", facility.status()),
					summary.reportCount()
				));
		} catch (StationNotFoundException exception) {
			return Optional.empty();
		}
	}

	private static String confidenceLabel(DataConfidenceLevel level) {
		return switch (level) {
			case HIGH -> "확인된 정보";
			case MEDIUM -> "일부 확인된 정보";
			case LOW -> "확인이 더 필요한 정보";
			case NEEDS_VERIFICATION -> "확인 필요";
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
		List<FacilityStatusDelayRow> facilityStatusDelayRows,
		long totalReportCount,
		long verifiedReportCount,
		long pendingReportCount,
		int reportVerificationRatePercent,
		List<ReportStatusCountRow> reportStatusRows,
		List<StationAccessibilityScoreRow> stationAccessibilityScoreRows,
		List<AccessibilityImprovementPriorityRow> accessibilityImprovementPriorityRows,
		List<RepeatedBrokenFacilityRow> repeatedBrokenFacilityRows
	) {

		static DataQualityDashboardView from(
			DataQualitySummary summary,
			List<TransitRegionSummary> regions,
			Map<FacilityReportStatus, Long> reportStatusCounts,
			List<StationAccessibilityScoreRow> stationAccessibilityScoreRows,
			List<AccessibilityImprovementPriorityRow> accessibilityImprovementPriorityRows,
			List<RepeatedBrokenFacilityRow> repeatedBrokenFacilityRows,
			WebMessageResolver messages
		) {
			long totalReportCount = reportStatusCounts.values()
				.stream()
				.mapToLong(Long::longValue)
				.sum();
			long verifiedReportCount = countMatchingReportStatuses(reportStatusCounts, true);
			long pendingReportCount = countMatchingReportStatuses(reportStatusCounts, false);
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
				facilityStatusDelayRows(summary.delayedFacilityStatusCounts(), messages),
				totalReportCount,
				verifiedReportCount,
				pendingReportCount,
				verificationRatePercent(totalReportCount, verifiedReportCount),
				reportStatusRows(reportStatusCounts, messages),
				stationAccessibilityScoreRows,
				accessibilityImprovementPriorityRows,
				repeatedBrokenFacilityRows
			);
		}

		private static List<QualityCountRow> qualityRows(Map<DataQualityLevel, Long> counts) {
			return Arrays.stream(DataQualityLevel.values())
				.map(level -> new QualityCountRow(
					level.label(),
					level.description(),
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
			Map<AccessibilityFacilityStatus, Long> counts,
			WebMessageResolver messages
		) {
			return Arrays.stream(AccessibilityFacilityStatus.values())
				.map(status -> new FacilityStatusDelayRow(
					messages.enumLabel("admin.facility.status", status),
					counts.getOrDefault(status, 0L)
				))
				.toList();
		}

		private static long countMatchingReportStatuses(
			Map<FacilityReportStatus, Long> counts,
			boolean verified
		) {
			return Arrays.stream(FacilityReportStatus.values())
				.filter(status -> isVerifiedReportStatus(status) == verified)
				.mapToLong(status -> counts.getOrDefault(status, 0L))
				.sum();
		}

		private static List<ReportStatusCountRow> reportStatusRows(
			Map<FacilityReportStatus, Long> counts,
			WebMessageResolver messages
		) {
			return Arrays.stream(FacilityReportStatus.values())
				.map(status -> new ReportStatusCountRow(
					messages.enumLabel("admin.report.status", status),
					counts.getOrDefault(status, 0L)
				))
				.toList();
		}

		private static boolean isVerifiedReportStatus(FacilityReportStatus status) {
			return switch (status) {
				case ACCEPTED, REJECTED, DUPLICATE, RESOLVED -> true;
				case SUBMITTED, UNDER_REVIEW -> false;
			};
		}

		private static boolean isPendingReportStatus(FacilityReportStatus status) {
			return switch (status) {
				case SUBMITTED, UNDER_REVIEW -> true;
				case ACCEPTED, REJECTED, DUPLICATE, RESOLVED -> false;
			};
		}

		private static int verificationRatePercent(long totalReportCount, long verifiedReportCount) {
			if (totalReportCount == 0) {
				return 0;
			}
			return (int) Math.round((verifiedReportCount * 100.0) / totalReportCount);
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

	record ReportStatusCountRow(String statusLabel, long count) {
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

	record RepeatedBrokenFacilityRow(String stationName, String facilityName, String statusLabel, long reportCount) {
	}
}
