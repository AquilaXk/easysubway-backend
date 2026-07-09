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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
class OperatorAccessibilityReportAssembler {

	private final DataQualityUseCase dataQualityUseCase;
	private final TransitMasterQueryUseCase transitMasterQueryUseCase;

	OperatorAccessibilityReportAssembler(
		DataQualityUseCase dataQualityUseCase,
		TransitMasterQueryUseCase transitMasterQueryUseCase
	) {
		this.dataQualityUseCase = dataQualityUseCase;
		this.transitMasterQueryUseCase = transitMasterQueryUseCase;
	}

	OperatorAccessibilityReportView assemble() {
		return assemble(OperatorReportQuery.empty());
	}

	OperatorAccessibilityReportView assemble(OperatorReportQuery query) {
		DataQualitySummary summary = dataQualityUseCase.summarizeDataQuality();
		List<TransitRegionSummary> regions = transitMasterQueryUseCase.listRegions();
		return new OperatorAccessibilityReportView(
			summary.totalStations(),
			summary.totalFacilities(),
			summary.needsVerificationFacilityCount(),
			summary.delayedFacilityStatusCount(),
			summary.missingStationVerificationDateCount(),
			qualityRows(summary.stationQualityCounts()),
			regionQualityRows(summary.regionSummaries(), regions, query),
			stationAccessibilityScoreRows(summary.stationAccessibilityScores(), query),
			accessibilityImprovementPriorityRows(summary.accessibilityImprovementPriorities(), query)
		);
	}

	private List<OperatorAccessibilityReportView.QualityCountRow> qualityRows(
		Map<DataQualityLevel, Long> counts
	) {
		return Arrays.stream(DataQualityLevel.values())
			.map(level -> new OperatorAccessibilityReportView.QualityCountRow(
				level.label(),
				level.description(),
				counts.getOrDefault(level, 0L)
			))
			.toList();
	}

	private List<OperatorAccessibilityReportView.RegionQualityRow> regionQualityRows(
		List<RegionDataQualitySummary> regionSummaries,
		List<TransitRegionSummary> regions,
		OperatorReportQuery query
	) {
		Map<String, TransitRegionSummary> regionsByName = regions.stream()
			.collect(Collectors.toMap(TransitRegionSummary::name, region -> region));
		return regionSummaries.stream()
			.map(region -> {
				TransitRegionSummary masterRegion = regionsByName.get(region.name());
				return new OperatorAccessibilityReportView.RegionQualityRow(
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
			.filter(row -> query.matches(row.name()))
			.sorted(regionQualityComparator(query))
			.toList();
	}

	private static Comparator<OperatorAccessibilityReportView.RegionQualityRow> regionQualityComparator(
		OperatorReportQuery query
	) {
		Comparator<OperatorAccessibilityReportView.RegionQualityRow> comparator = switch (query.sort()) {
			case "operators" -> Comparator.comparingInt(OperatorAccessibilityReportView.RegionQualityRow::operatorCount);
			case "lines" -> Comparator.comparingInt(OperatorAccessibilityReportView.RegionQualityRow::lineCount);
			case "stations" -> Comparator.comparingInt(OperatorAccessibilityReportView.RegionQualityRow::stationCount);
			default -> Comparator.comparing(OperatorAccessibilityReportView.RegionQualityRow::name);
		};
		return "desc".equals(query.direction()) ? comparator.reversed() : comparator;
	}

	private List<OperatorAccessibilityReportView.StationAccessibilityScoreRow> stationAccessibilityScoreRows(
		List<StationAccessibilityScore> scores,
		OperatorReportQuery query
	) {
		return scores.stream()
			.map(score -> new OperatorAccessibilityReportView.StationAccessibilityScoreRow(
				score.stationName(),
				score.region(),
				score.score(),
				score.reasons()
			))
			.filter(row -> query.matches(row.stationName(), row.region(), row.reasonText()))
			.toList();
	}

	private List<OperatorAccessibilityReportView.AccessibilityImprovementPriorityRow> accessibilityImprovementPriorityRows(
		List<AccessibilityImprovementPriority> priorities,
		OperatorReportQuery query
	) {
		return priorities.stream()
			.map(this::accessibilityImprovementPriorityRow)
			.flatMap(Optional::stream)
			.filter(row -> query.matches(row.stationName(), row.facilityName(), row.reasonText()))
			.toList();
	}

	private Optional<OperatorAccessibilityReportView.AccessibilityImprovementPriorityRow> accessibilityImprovementPriorityRow(
		AccessibilityImprovementPriority priority
	) {
		try {
			StationWithLines station = transitMasterQueryUseCase.getStation(priority.stationId());
			return transitMasterQueryUseCase.listStationFacilities(priority.stationId())
				.stream()
				.filter(candidate -> candidate.id().equals(priority.facilityId()))
				.findFirst()
				.map(facility -> new OperatorAccessibilityReportView.AccessibilityImprovementPriorityRow(
					station.station().nameKo(),
					facility.name(),
					priority.priorityScore(),
					priority.reasons()
				));
		} catch (StationNotFoundException exception) {
			return Optional.empty();
		}
	}

}
