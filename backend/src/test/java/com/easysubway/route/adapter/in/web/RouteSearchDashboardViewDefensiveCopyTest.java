package com.easysubway.route.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Route search dashboard immutable list boundary")
class RouteSearchDashboardViewDefensiveCopyTest {

	private static final RouteSearchDashboardView.MobilityTypeCountRow MOBILITY_ROW =
		new RouteSearchDashboardView.MobilityTypeCountRow("휠체어 사용자", 11);
	private static final RouteSearchDashboardView.RegionUsageCountRow REGION_ROW =
		new RouteSearchDashboardView.RegionUsageCountRow("수도권", 7, 5);
	private static final RouteSearchDashboardView.BlockedReasonCountRow BLOCKED_REASON_ROW =
		new RouteSearchDashboardView.BlockedReasonCountRow("STRICT_ACCESSIBILITY_BLOCK", 3);
	private static final RouteSearchDashboardView.EtaSourceCountRow ETA_SOURCE_ROW =
		new RouteSearchDashboardView.EtaSourceCountRow("REALTIME", "실시간 반영", 9);
	private static final RouteSearchDashboardView.FallbackReasonCountRow FALLBACK_REASON_ROW =
		new RouteSearchDashboardView.FallbackReasonCountRow(
			"PROVIDER_OUTAGE_OR_STALE_REALTIME",
			"provider 장애 또는 stale realtime",
			2
		);
	private static final RouteSearchDashboardView.RouteQualitySignalRow QUALITY_SIGNAL_ROW =
		new RouteSearchDashboardView.RouteQualitySignalRow("PROVIDER_OUTAGE", "provider outage/stale", 2);
	private static final RouteSearchDashboardView.AlertThresholdRow ALERT_THRESHOLD_ROW =
		new RouteSearchDashboardView.AlertThresholdRow(
			"route_not_found_rate",
			"경로 미탐색률",
			">= 2.0%",
			"경로 그래프·엄격 접근성 데이터 소스 검수"
		);

	@Test
	@DisplayName("constructor snapshots mutable dashboard row lists")
	void snapshotsMutableListInputs() {
		var mobilityRows = new ArrayList<>(List.of(MOBILITY_ROW));
		var regionRows = new ArrayList<>(List.of(REGION_ROW));
		var blockedReasonRows = new ArrayList<>(List.of(BLOCKED_REASON_ROW));
		var etaSourceRows = new ArrayList<>(List.of(ETA_SOURCE_ROW));
		var fallbackReasonRows = new ArrayList<>(List.of(FALLBACK_REASON_ROW));
		var qualitySignalRows = new ArrayList<>(List.of(QUALITY_SIGNAL_ROW));
		var alertThresholdRows = new ArrayList<>(List.of(ALERT_THRESHOLD_ROW));
		var view = view(
			mobilityRows,
			regionRows,
			blockedReasonRows,
			etaSourceRows,
			fallbackReasonRows,
			qualitySignalRows,
			alertThresholdRows
		);

		mobilityRows.clear();
		regionRows.clear();
		blockedReasonRows.clear();
		etaSourceRows.clear();
		fallbackReasonRows.clear();
		qualitySignalRows.clear();
		alertThresholdRows.clear();

		assertThat(view.totalCount()).isEqualTo(20);
		assertThat(view.foundCount()).isEqualTo(16);
		assertThat(view.blockedCount()).isEqualTo(4);
		assertThat(view.blockedRateLabel()).isEqualTo("20.0%");
		assertThat(view.routeNotFoundRateLabel()).isEqualTo("20.0%");
		assertThat(view.blockedAlertLabel()).isEqualTo("점검 필요");
		assertThat(view.blockedAlertDescription()).isEqualTo("경로 차단률 점검");
		assertThat(view.blockedAlertClass()).isEqualTo("failure");
		assertThat(view.mobilityTypeRows()).containsExactly(MOBILITY_ROW);
		assertThat(view.regionUsageRows()).containsExactly(REGION_ROW);
		assertThat(view.blockedReasonRows()).containsExactly(BLOCKED_REASON_ROW);
		assertThat(view.etaSourceRows()).containsExactly(ETA_SOURCE_ROW);
		assertThat(view.fallbackReasonRows()).containsExactly(FALLBACK_REASON_ROW);
		assertThat(view.routeQualitySignalRows()).containsExactly(QUALITY_SIGNAL_ROW);
		assertThat(view.alertThresholdRows()).containsExactly(ALERT_THRESHOLD_ROW);
	}

	@Test
	@DisplayName("dashboard row list accessors are immutable")
	void exposesImmutableLists() {
		var view = view(
			new ArrayList<>(List.of(MOBILITY_ROW)),
			new ArrayList<>(List.of(REGION_ROW)),
			new ArrayList<>(List.of(BLOCKED_REASON_ROW)),
			new ArrayList<>(List.of(ETA_SOURCE_ROW)),
			new ArrayList<>(List.of(FALLBACK_REASON_ROW)),
			new ArrayList<>(List.of(QUALITY_SIGNAL_ROW)),
			new ArrayList<>(List.of(ALERT_THRESHOLD_ROW))
		);

		assertThatThrownBy(() -> view.mobilityTypeRows().clear())
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> view.regionUsageRows().clear())
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> view.blockedReasonRows().clear())
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> view.etaSourceRows().clear())
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> view.fallbackReasonRows().clear())
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> view.routeQualitySignalRows().clear())
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> view.alertThresholdRows().clear())
			.isInstanceOf(UnsupportedOperationException.class);
	}

	private static RouteSearchDashboardView view(
		List<RouteSearchDashboardView.MobilityTypeCountRow> mobilityRows,
		List<RouteSearchDashboardView.RegionUsageCountRow> regionRows,
		List<RouteSearchDashboardView.BlockedReasonCountRow> blockedReasonRows,
		List<RouteSearchDashboardView.EtaSourceCountRow> etaSourceRows,
		List<RouteSearchDashboardView.FallbackReasonCountRow> fallbackReasonRows,
		List<RouteSearchDashboardView.RouteQualitySignalRow> qualitySignalRows,
		List<RouteSearchDashboardView.AlertThresholdRow> alertThresholdRows
	) {
		return new RouteSearchDashboardView(
			20,
			16,
			4,
			"20.0%",
			"20.0%",
			"점검 필요",
			"경로 차단률 점검",
			"failure",
			mobilityRows,
			regionRows,
			blockedReasonRows,
			etaSourceRows,
			fallbackReasonRows,
			qualitySignalRows,
			alertThresholdRows
		);
	}
}
