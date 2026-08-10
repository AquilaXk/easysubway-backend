package com.easysubway.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.admin.batch.application.service.AdminBatchOperationService.JobExecutionHistory;
import com.easysubway.admin.batch.application.service.AdminBatchOperationService.RunExecution;
import com.easysubway.admin.metric.application.service.AdminMetricQueryService.AdminMetricChart;
import com.easysubway.admin.metric.application.service.AdminMetricQueryService.AdminMetricSeries;
import com.easysubway.admin.transition.AdminPlatformTransitionProperties.BlockerMode;
import com.easysubway.admin.transition.AdminPlatformTransitionProperties.LegacyEnvAdminFallback;
import com.easysubway.admin.transition.AdminPlatformTransitionProperties.ReleaseGate;
import com.easysubway.admin.transition.AdminPlatformTransitionProperties.ShadowMode;
import com.easysubway.admin.web.AdminFormErrorView;
import com.easysubway.collection.domain.DataCollectionStatus;
import com.easysubway.operator.adapter.in.web.OperatorAccessibilityReportView;
import com.easysubway.operator.adapter.in.web.OperatorAccessibilityReportView.AccessibilityImprovementPriorityRow;
import com.easysubway.operator.adapter.in.web.OperatorAccessibilityReportView.QualityCountRow;
import com.easysubway.operator.adapter.in.web.OperatorAccessibilityReportView.RegionQualityRow;
import com.easysubway.operator.adapter.in.web.OperatorAccessibilityReportView.StationAccessibilityScoreRow;
import com.easysubway.quality.domain.DataQualitySummary;
import com.easysubway.quality.domain.RegionDataQualitySummary;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관리자·운영자·데이터 품질 값 경계")
class AdminOperatorQualityValueBoundaryTest {

	@Test
	@DisplayName("배치·지표·전환 설정 list는 null과 순서를 보존하는 독립 snapshot이다")
	void batchMetricAndTransitionListsAreIndependentSnapshots() {
		RunExecution firstExecution = new RunExecution(
			LocalDateTime.of(2026, 8, 10, 1, 0), DataCollectionStatus.COMPLETED, 10L);
		RunExecution secondExecution = new RunExecution(
			LocalDateTime.of(2026, 8, 10, 2, 0), DataCollectionStatus.FAILED, 20L);
		var executions = new ArrayList<>(Arrays.asList(firstExecution, null, secondExecution));
		var history = new JobExecutionHistory("job", "잡", executions);

		executions.clear();
		assertThat(history.executions()).containsExactly(firstExecution, null, secondExecution);
		history.executions().removeFirst();
		assertThat(history.executions()).containsExactly(firstExecution, null, secondExecution);
		assertThat(history).isEqualTo(new JobExecutionHistory(
			"job", "잡", new ArrayList<>(Arrays.asList(firstExecution, null, secondExecution))));

		var values = new ArrayList<Double>(Arrays.asList(1.0, null, 2.0));
		var series = new AdminMetricSeries("metric", "지표", values);
		values.clear();
		assertThat(series.values()).containsExactly(1.0, null, 2.0);
		series.values().removeFirst();
		assertThat(series.values()).containsExactly(1.0, null, 2.0);

		var labels = new ArrayList<String>(Arrays.asList("first", null, "second"));
		var seriesRows = new ArrayList<>(Arrays.asList(series, null, new AdminMetricSeries("other", "다른 지표", List.of(3.0))));
		var chart = new AdminMetricChart(7, labels, seriesRows);
		labels.clear();
		seriesRows.clear();
		assertThat(chart.labels()).containsExactly("first", null, "second");
		assertThat(chart.series()).containsExactly(series, null, new AdminMetricSeries("other", "다른 지표", List.of(3.0)));
		chart.labels().removeFirst();
		chart.series().removeFirst();
		assertThat(chart.labels()).containsExactly("first", null, "second");
		assertThat(chart.series()).hasSize(3);

		var promotionCriteria = new ArrayList<String>(Arrays.asList("first", null, "second"));
		var shadow = new ShadowMode("compare", "metric", promotionCriteria);
		var removalCriteria = new ArrayList<String>(Arrays.asList("first", null, "second"));
		var fallback = new LegacyEnvAdminFallback(removalCriteria, "rollback");
		var blockers = new ArrayList<String>(Arrays.asList("first", null, "second"));
		var releaseGate = new ReleaseGate(BlockerMode.FAIL, blockers);
		promotionCriteria.clear();
		removalCriteria.clear();
		blockers.clear();
		assertThat(shadow.promotionCriteria()).containsExactly("first", null, "second");
		assertThat(fallback.removalCriteria()).containsExactly("first", null, "second");
		assertThat(releaseGate.blockers()).containsExactly("first", null, "second");
		shadow.promotionCriteria().clear();
		fallback.removalCriteria().clear();
		releaseGate.blockers().clear();
		assertThat(shadow.promotionCriteria()).hasSize(3);
		assertThat(fallback.removalCriteria()).hasSize(3);
		assertThat(releaseGate.blockers()).hasSize(3);
	}

	@Test
	@DisplayName("form·operator view list와 map은 입력·accessor mutation을 내부로 전달하지 않는다")
	void formAndOperatorViewCollectionsAreIndependentSnapshots() {
		var summaryRows = new ArrayList<String>(Arrays.asList("first", null, "second"));
		var fieldErrors = new LinkedHashMap<String, String>();
		fieldErrors.put("first", "오류 1");
		fieldErrors.put("middle", null);
		fieldErrors.put("second", "오류 2");
		var form = new AdminFormErrorView(summaryRows, fieldErrors);
		summaryRows.clear();
		fieldErrors.clear();
		assertThat(form.summary()).containsExactly("first", null, "second");
		assertThat(form.fieldErrors()).containsExactly(
			Map.entry("first", "오류 1"), new java.util.AbstractMap.SimpleEntry<>("middle", null), Map.entry("second", "오류 2"));
		form.summary().removeFirst();
		form.fieldErrors().remove("first");
		assertThat(form.summary()).hasSize(3);
		assertThat(form.fieldErrors()).containsKeys("first", "middle", "second");

		var reasons = new ArrayList<String>(Arrays.asList("first", null, "second"));
		var stationScore = new StationAccessibilityScoreRow("역", "지역", 80, reasons);
		var priorityReasons = new ArrayList<String>(Arrays.asList("first", null, "second"));
		var priority = new AccessibilityImprovementPriorityRow("역", "엘리베이터", 90, priorityReasons);
		reasons.clear();
		priorityReasons.clear();
		assertThat(stationScore.reasons()).containsExactly("first", null, "second");
		assertThat(priority.reasons()).containsExactly("first", null, "second");
		stationScore.reasons().removeFirst();
		priority.reasons().removeFirst();
		assertThat(stationScore.reasons()).hasSize(3);
		assertThat(priority.reasons()).hasSize(3);

		var firstQuality = new QualityCountRow("L1", "첫 번째", 1);
		var secondQuality = new QualityCountRow("L2", "두 번째", 2);
		var qualityRows = new ArrayList<>(Arrays.asList(firstQuality, null, secondQuality));
		var firstRegion = new RegionQualityRow("서울", 1, 2, 3, 4, 5, 6, 7);
		var secondRegion = new RegionQualityRow("부산", 7, 6, 5, 4, 3, 2, 1);
		var regionRows = new ArrayList<>(Arrays.asList(firstRegion, null, secondRegion));
		var secondStationScore = new StationAccessibilityScoreRow("두 번째 역", "부산", 70, List.of("두 번째 사유"));
		var stationRows = new ArrayList<>(Arrays.asList(stationScore, null, secondStationScore));
		var secondPriority = new AccessibilityImprovementPriorityRow(
			"두 번째 역", "경사로", 60, List.of("두 번째 우선순위"));
		var priorityRows = new ArrayList<>(Arrays.asList(priority, null, secondPriority));
		var report = new OperatorAccessibilityReportView(
			1, 2, 3, 4, 5, qualityRows, regionRows, stationRows, priorityRows);
		qualityRows.clear();
		regionRows.clear();
		stationRows.clear();
		priorityRows.clear();
		assertThat(report.stationQualityRows()).containsExactly(firstQuality, null, secondQuality);
		assertThat(report.regionQualityRows()).containsExactly(firstRegion, null, secondRegion);
		assertThat(report.stationAccessibilityScoreRows()).containsExactly(stationScore, null, secondStationScore);
		assertThat(report.accessibilityImprovementPriorityRows()).containsExactly(priority, null, secondPriority);
		report.stationQualityRows().clear();
		report.regionQualityRows().clear();
		report.stationAccessibilityScoreRows().clear();
		report.accessibilityImprovementPriorityRows().clear();
		assertThat(report.stationQualityRows()).containsExactly(firstQuality, null, secondQuality);
		assertThat(report.regionQualityRows()).containsExactly(firstRegion, null, secondRegion);
		assertThat(report.stationAccessibilityScoreRows()).containsExactly(stationScore, null, secondStationScore);
		assertThat(report.accessibilityImprovementPriorityRows()).containsExactly(priority, null, secondPriority);
		assertThat(report).isEqualTo(new OperatorAccessibilityReportView(
			1,
			2,
			3,
			4,
			5,
			new ArrayList<>(Arrays.asList(
				new QualityCountRow("L1", "첫 번째", 1), null, new QualityCountRow("L2", "두 번째", 2))),
			new ArrayList<>(Arrays.asList(
				new RegionQualityRow("서울", 1, 2, 3, 4, 5, 6, 7),
				null,
				new RegionQualityRow("부산", 7, 6, 5, 4, 3, 2, 1))),
			new ArrayList<>(Arrays.asList(
				new StationAccessibilityScoreRow(
					"역", "지역", 80, new ArrayList<>(Arrays.asList("first", null, "second"))),
				null,
				new StationAccessibilityScoreRow("두 번째 역", "부산", 70, List.of("두 번째 사유")))),
			new ArrayList<>(Arrays.asList(
				new AccessibilityImprovementPriorityRow(
					"역", "엘리베이터", 90, new ArrayList<>(Arrays.asList("first", null, "second"))),
				null,
				new AccessibilityImprovementPriorityRow(
					"두 번째 역", "경사로", 60, List.of("두 번째 우선순위"))))));
	}

	@Test
	@DisplayName("품질 enum map은 null value와 순서를 보존하고 region map은 unmodifiable이다")
	void qualityMapsPreserveValuesAndRegionContract() {
		var stationCounts = new LinkedHashMap<DataQualityLevel, Long>();
		stationCounts.put(DataQualityLevel.LEVEL_1, 1L);
		stationCounts.put(DataQualityLevel.LEVEL_2, null);
		stationCounts.put(DataQualityLevel.LEVEL_3, 3L);
		var exitCounts = new LinkedHashMap<DataConfidenceLevel, Long>();
		exitCounts.put(DataConfidenceLevel.HIGH, 2L);
		exitCounts.put(DataConfidenceLevel.MEDIUM, null);
		exitCounts.put(DataConfidenceLevel.LOW, 4L);
		var facilityCounts = new LinkedHashMap<DataConfidenceLevel, Long>();
		facilityCounts.put(DataConfidenceLevel.HIGH, 5L);
		facilityCounts.put(DataConfidenceLevel.MEDIUM, null);
		facilityCounts.put(DataConfidenceLevel.LOW, 7L);
		var delayedCounts = new LinkedHashMap<AccessibilityFacilityStatus, Long>();
		delayedCounts.put(AccessibilityFacilityStatus.NORMAL, 8L);
		delayedCounts.put(AccessibilityFacilityStatus.UNKNOWN, null);
		delayedCounts.put(AccessibilityFacilityStatus.ADMIN_VERIFIED, 9L);
		var expectedStationCounts = new LinkedHashMap<>(stationCounts);
		var expectedExitCounts = new LinkedHashMap<>(exitCounts);
		var expectedFacilityCounts = new LinkedHashMap<>(facilityCounts);
		var expectedDelayedCounts = new LinkedHashMap<>(delayedCounts);
		var quality = new DataQualitySummary(
			1, 2, 3, stationCounts, List.of(), exitCounts, facilityCounts,
			4, 5, delayedCounts, 6, List.of(), List.of());

		stationCounts.clear();
		exitCounts.clear();
		facilityCounts.clear();
		delayedCounts.clear();
		assertThat(quality.stationQualityCounts()).containsExactly(
			Map.entry(DataQualityLevel.LEVEL_1, 1L),
			new java.util.AbstractMap.SimpleEntry<>(DataQualityLevel.LEVEL_2, null),
			Map.entry(DataQualityLevel.LEVEL_3, 3L));
		assertThat(quality.exitConfidenceCounts()).containsExactly(
			Map.entry(DataConfidenceLevel.HIGH, 2L),
			new java.util.AbstractMap.SimpleEntry<>(DataConfidenceLevel.MEDIUM, null),
			Map.entry(DataConfidenceLevel.LOW, 4L));
		assertThat(quality.facilityConfidenceCounts()).containsExactly(
			Map.entry(DataConfidenceLevel.HIGH, 5L),
			new java.util.AbstractMap.SimpleEntry<>(DataConfidenceLevel.MEDIUM, null),
			Map.entry(DataConfidenceLevel.LOW, 7L));
		assertThat(quality.delayedFacilityStatusCounts()).containsExactly(
			Map.entry(AccessibilityFacilityStatus.NORMAL, 8L),
			new java.util.AbstractMap.SimpleEntry<>(AccessibilityFacilityStatus.UNKNOWN, null),
			Map.entry(AccessibilityFacilityStatus.ADMIN_VERIFIED, 9L));
		quality.stationQualityCounts().clear();
		quality.exitConfidenceCounts().clear();
		quality.facilityConfidenceCounts().clear();
		quality.delayedFacilityStatusCounts().clear();
		assertThat(quality.stationQualityCounts()).containsExactlyEntriesOf(expectedStationCounts);
		assertThat(quality.exitConfidenceCounts()).containsExactlyEntriesOf(expectedExitCounts);
		assertThat(quality.facilityConfidenceCounts()).containsExactlyEntriesOf(expectedFacilityCounts);
		assertThat(quality.delayedFacilityStatusCounts()).containsExactlyEntriesOf(expectedDelayedCounts);
		assertThat(quality).isEqualTo(new DataQualitySummary(
			1,
			2,
			3,
			new LinkedHashMap<>(expectedStationCounts),
			List.of(),
			new LinkedHashMap<>(expectedExitCounts),
			new LinkedHashMap<>(expectedFacilityCounts),
			4,
			5,
			new LinkedHashMap<>(expectedDelayedCounts),
			6,
			List.of(),
			List.of()));

		var regionInput = new EnumMap<DataQualityLevel, Long>(DataQualityLevel.class);
		regionInput.put(DataQualityLevel.LEVEL_1, 1L);
		regionInput.put(DataQualityLevel.LEVEL_2, 2L);
		var region = new RegionDataQualitySummary("서울", 2, regionInput);
		regionInput.clear();
		assertThat(region.stationQualityCounts())
			.containsEntry(DataQualityLevel.LEVEL_1, 1L)
			.containsEntry(DataQualityLevel.LEVEL_2, 2L);
		assertThatThrownBy(() -> region.stationQualityCounts().put(DataQualityLevel.LEVEL_3, 3L))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	@DisplayName("기존 null collection 입력은 새 경계에서도 null로 유지한다")
	void nullableCollectionsRemainNull() {
		assertThat(new JobExecutionHistory("job", "잡", null).executions()).isNull();
		assertThat(new AdminMetricSeries("metric", "지표", null).values()).isNull();
		assertThat(new AdminMetricChart(7, null, null).labels()).isNull();
		assertThat(new AdminMetricChart(7, null, null).series()).isNull();
		assertThat(new ShadowMode("mode", "metric", null).promotionCriteria()).isNull();
		assertThat(new LegacyEnvAdminFallback(null, "rollback").removalCriteria()).isNull();
		assertThat(new AdminFormErrorView(null, null).summary()).isNull();
		assertThat(new AdminFormErrorView(null, null).fieldErrors()).isNull();
		assertThat(new StationAccessibilityScoreRow("역", "지역", 1, null).reasons()).isNull();
		assertThat(new AccessibilityImprovementPriorityRow("역", "시설", 1, null).reasons()).isNull();
		var nullableReport = new OperatorAccessibilityReportView(0, 0, 0, 0, 0, null, null, null, null);
		assertThat(nullableReport.stationQualityRows()).isNull();
		assertThat(nullableReport.regionQualityRows()).isNull();
		assertThat(nullableReport.stationAccessibilityScoreRows()).isNull();
		assertThat(nullableReport.accessibilityImprovementPriorityRows()).isNull();
		var nullableQuality = new DataQualitySummary(
			0, 0, 0, null, List.of(), null, null, 0, 0, null, 0, List.of(), List.of());
		assertThat(nullableQuality.stationQualityCounts()).isNull();
		assertThat(nullableQuality.exitConfidenceCounts()).isNull();
		assertThat(nullableQuality.facilityConfidenceCounts()).isNull();
		assertThat(nullableQuality.delayedFacilityStatusCounts()).isNull();
	}
}
