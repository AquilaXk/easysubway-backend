package com.easysubway.route.adapter.in.web;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.domain.EtaSource;
import com.easysubway.route.domain.RouteSearchDashboardSummary;
import java.util.List;
import java.util.Locale;

public record RouteSearchDashboardView(
	long totalCount,
	long foundCount,
	long blockedCount,
	String blockedRateLabel,
	String routeNotFoundRateLabel,
	String blockedAlertLabel,
	String blockedAlertDescription,
	String blockedAlertClass,
	List<MobilityTypeCountRow> mobilityTypeRows,
	List<RegionUsageCountRow> regionUsageRows,
	List<BlockedReasonCountRow> blockedReasonRows,
	List<EtaSourceCountRow> etaSourceRows,
	List<FallbackReasonCountRow> fallbackReasonRows,
	List<RouteQualitySignalRow> routeQualitySignalRows,
	List<AlertThresholdRow> alertThresholdRows
) {

	static RouteSearchDashboardView from(RouteSearchDashboardSummary summary) {
		return new RouteSearchDashboardView(
			summary.totalCount(),
			summary.foundCount(),
			summary.blockedCount(),
			percentageLabel(summary.blockedCount(), summary.totalCount()),
			percentageLabel(summary.blockedCount(), summary.totalCount()),
			blockedAlertLabel(summary.blockedCount(), summary.totalCount()),
			blockedAlertDescription(summary.blockedCount(), summary.totalCount()),
			blockedAlertClass(summary.blockedCount(), summary.totalCount()),
			summary.mobilityTypeCounts()
				.stream()
				.map(row -> new MobilityTypeCountRow(mobilityTypeLabel(row.mobilityType()), row.count()))
				.toList(),
			summary.regionUsageCounts()
				.stream()
				.map(row -> new RegionUsageCountRow(row.region(), row.originCount(), row.destinationCount()))
				.toList(),
			summary.blockedReasonCounts()
				.stream()
				.map(row -> new BlockedReasonCountRow(row.reason(), row.count()))
				.toList(),
			summary.etaSourceCounts()
				.stream()
				.map(row -> new EtaSourceCountRow(row.etaSource().name(), etaSourceLabel(row.etaSource()), row.count()))
				.toList(),
			summary.fallbackReasonCounts()
				.stream()
				.map(row -> new FallbackReasonCountRow(row.reason(), fallbackReasonLabel(row.reason()), row.count()))
				.toList(),
			summary.routeQualitySignalCounts()
				.stream()
				.map(row -> new RouteQualitySignalRow(row.signal(), routeQualitySignalLabel(row.signal()), row.count()))
				.toList(),
			defaultAlertThresholdRows()
		);
	}

	public record MobilityTypeCountRow(String label, long count) {
	}

	public record RegionUsageCountRow(String region, long originCount, long destinationCount) {
	}

	public record BlockedReasonCountRow(String reason, long count) {
	}

	public record EtaSourceCountRow(String code, String label, long count) {
	}

	public record FallbackReasonCountRow(String reason, String label, long count) {
	}

	public record RouteQualitySignalRow(String signal, String label, long count) {
	}

	public record AlertThresholdRow(String metric, String threshold, String action) {
	}

	private static String percentageLabel(long numerator, long denominator) {
		if (denominator == 0) {
			return "0.0%";
		}
		return String.format(Locale.ROOT, "%.1f%%", numerator * 100.0 / denominator);
	}

	private static String blockedAlertLabel(long blockedCount, long totalCount) {
		if (totalCount == 0) {
			return "기록 없음";
		}
		if (blockedCount == 0) {
			return "정상";
		}
		return blockedCount * 100 >= totalCount * 20 ? "점검 필요" : "주의";
	}

	private static String blockedAlertDescription(long blockedCount, long totalCount) {
		if (totalCount == 0) {
			return "아직 경로 검색 기록이 없습니다.";
		}
		if (blockedCount == 0) {
			return "차단된 경로 검색 없이 처리되고 있습니다.";
		}
		if (blockedCount * 100 >= totalCount * 20) {
			return "경로 차단율이 높아 이동 정보와 접근성 경로 조건을 확인하세요.";
		}
		return "일부 경로가 차단되어 차단 사유를 확인하세요.";
	}

	private static String blockedAlertClass(long blockedCount, long totalCount) {
		if (totalCount == 0) {
			return "pending";
		}
		if (blockedCount == 0) {
			return "ok";
		}
		return blockedCount * 100 >= totalCount * 20 ? "failure" : "warning";
	}

	private static String mobilityTypeLabel(MobilityType mobilityType) {
		return switch (mobilityType) {
			case SENIOR -> "고령자";
			case STROLLER -> "유모차 동반";
			case WHEELCHAIR -> "휠체어 사용자";
			case PREGNANT -> "임산부";
			case TEMPORARY_INJURY -> "일시 부상";
			case LUGGAGE -> "큰 짐 동반";
		};
	}

	private static String etaSourceLabel(EtaSource etaSource) {
		return switch (etaSource) {
			case REALTIME -> "실시간 반영";
			case MIXED -> "일부 실시간 반영";
			case PLANNED -> "시간표 기준";
			case FALLBACK -> "provider 지연/장애 fallback";
		};
	}

	private static String fallbackReasonLabel(String reason) {
		return switch (reason) {
			case "PROVIDER_OUTAGE_OR_STALE_REALTIME" -> "provider 장애 또는 stale realtime";
			case "ROUTE_GRAPH_OR_STRICT_ACCESSIBILITY_BLOCK" -> "route graph/accessibility data quality failure";
			case "LOW_DATA_CONFIDENCE" -> "낮은 데이터 신뢰도";
			case "STALE_ACCESSIBILITY_DATA" -> "오래된 접근성 데이터";
			case "STRICT_STAIR_ONLY_ACCESS" -> "strict 조건 계단 전용 경로";
			default -> reason;
		};
	}

	private static String routeQualitySignalLabel(String signal) {
		return switch (signal) {
			case "PROVIDER_OUTAGE" -> "provider outage/stale";
			case "ROUTE_GRAPH_DATA_QUALITY" -> "route graph/data quality";
			case "STRICT_ACCESSIBILITY_BLOCK" -> "strict accessibility block";
			default -> signal;
		};
	}

	private static List<AlertThresholdRow> defaultAlertThresholdRows() {
		return List.of(
			new AlertThresholdRow(
				"route_not_found_rate",
				">= 2.0%",
				"route graph/strict accessibility source review"
			),
			new AlertThresholdRow(
				"PROVIDER_OUTAGE_OR_STALE_REALTIME",
				"> 0",
				"realtime provider health 확인"
			),
			new AlertThresholdRow(
				"LOW_DATA_CONFIDENCE",
				"> 0",
				"data quality source 검수"
			)
		);
	}
}
