package com.easysubway.admin.alert;

import com.easysubway.admin.metric.application.service.AdminMetricSnapshotStatusHolder;
import com.easysubway.admin.navigation.AdminProgram;
import com.easysubway.collection.application.port.in.DataCollectionUseCase;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionStatus;
import com.easysubway.datapack.application.port.in.DatapackReleaseBlockerSummaryUseCase;
import com.easysubway.notification.application.port.in.PushNotificationDashboardUseCase;
import com.easysubway.report.application.port.in.FacilityReportUseCase;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * 관리자 알림 센터(#1738). 흩어진 운영 신호 4종을 한곳에 집약해 topbar 벨로 노출한다.
 *
 * <p>신호원: ① 신고 급증(24시간 신규 신고 임계값 이상) ② 푸시 발송 실패(outbox failed)
 * ③ 배치 실패(최근 수집 실행 중 FAILED) ④ 데이터팩 릴리즈 blocker(&gt;0).
 *
 * <p>RBAC가 핵심이다: 각 신호는 대응 화면({@link AdminProgram})이 보이는 계정에만 노출한다.
 * 화면이 안 보이면 조회 자체를 건너뛰어 권한 없는 데이터가 새지 않게 하고 폴링 질의도 아낀다.
 * 상태 저장 없는 파생 신호라 매 폴링마다 새로 집약한다(읽음 처리는 비범위).
 */
@Service
public class AdminAlertService {

	private static final long REPORT_SURGE_LOOKBACK_HOURS = 24;
	private static final long REPORT_SURGE_THRESHOLD = 10;
	private static final int RECENT_RUN_SCAN = 20;

	private final FacilityReportUseCase facilityReportUseCase;
	private final PushNotificationDashboardUseCase pushNotificationDashboardUseCase;
	private final DataCollectionUseCase dataCollectionUseCase;
	private final DatapackReleaseBlockerSummaryUseCase datapackReleaseBlockerSummaryUseCase;
	private final AdminMetricSnapshotStatusHolder metricSnapshotStatusHolder;
	private final Clock clock;

	// Clock은 전역 빈이 아니라서(코드베이스 규약) 있으면 쓰고 없으면 시스템 기본으로 폴백한다.
	@Autowired
	public AdminAlertService(
		FacilityReportUseCase facilityReportUseCase,
		PushNotificationDashboardUseCase pushNotificationDashboardUseCase,
		DataCollectionUseCase dataCollectionUseCase,
		DatapackReleaseBlockerSummaryUseCase datapackReleaseBlockerSummaryUseCase,
		AdminMetricSnapshotStatusHolder metricSnapshotStatusHolder,
		ObjectProvider<Clock> clockProvider
	) {
		this(
			facilityReportUseCase,
			pushNotificationDashboardUseCase,
			dataCollectionUseCase,
			datapackReleaseBlockerSummaryUseCase,
			metricSnapshotStatusHolder,
			clockProvider.getIfAvailable(Clock::systemDefaultZone));
	}

	AdminAlertService(
		FacilityReportUseCase facilityReportUseCase,
		PushNotificationDashboardUseCase pushNotificationDashboardUseCase,
		DataCollectionUseCase dataCollectionUseCase,
		DatapackReleaseBlockerSummaryUseCase datapackReleaseBlockerSummaryUseCase,
		AdminMetricSnapshotStatusHolder metricSnapshotStatusHolder,
		Clock clock
	) {
		this.facilityReportUseCase = facilityReportUseCase;
		this.pushNotificationDashboardUseCase = pushNotificationDashboardUseCase;
		this.dataCollectionUseCase = dataCollectionUseCase;
		this.datapackReleaseBlockerSummaryUseCase = datapackReleaseBlockerSummaryUseCase;
		this.metricSnapshotStatusHolder = metricSnapshotStatusHolder;
		this.clock = clock;
	}

	public AdminAlertSummary summarize(Authentication authentication) {
		List<AdminProgram> visible = AdminProgram.visibleTo(authentication);
		List<AdminAlertItem> items = new ArrayList<>();
		addReportSurge(items, visible);
		addPushFailure(items, visible);
		addBatchFailure(items, visible);
		addDatapackBlocker(items, visible);
		addMetricSnapshotFailure(items, visible);
		return new AdminAlertSummary(items);
	}

	private void addReportSurge(List<AdminAlertItem> items, List<AdminProgram> visible) {
		if (!visible.contains(AdminProgram.REPORTS)) {
			return;
		}
		LocalDateTime cutoff = LocalDateTime.now(clock).minusHours(REPORT_SURGE_LOOKBACK_HOURS);
		long recent = facilityReportUseCase.countReportsCreatedSince(cutoff);
		if (recent >= REPORT_SURGE_THRESHOLD) {
			items.add(new AdminAlertItem(
				"report-surge",
				"신고 급증",
				"최근 24시간 " + recent + "건",
				"warning",
				AdminProgram.REPORTS.path()));
		}
	}

	private void addPushFailure(List<AdminAlertItem> items, List<AdminProgram> visible) {
		if (!visible.contains(AdminProgram.PUSH)) {
			return;
		}
		long failed = pushNotificationDashboardUseCase.summarizePushNotifications().failedCount();
		if (failed > 0) {
			items.add(new AdminAlertItem(
				"push-failure",
				"푸시 발송 실패",
				failed + "건 실패",
				"failure",
				// 실패 신호는 푸시 화면의 실패 필터 이력으로 바로 딥링크한다(#1746).
				AdminProgram.PUSH.path() + "?status=FAILED"));
		}
	}

	private void addBatchFailure(List<AdminAlertItem> items, List<AdminProgram> visible) {
		if (!visible.contains(AdminProgram.BATCHES)) {
			return;
		}
		long failed = dataCollectionUseCase.listRecentRuns(RECENT_RUN_SCAN).stream()
			.map(DataCollectionRun::status)
			.filter(DataCollectionStatus.FAILED::equals)
			.count();
		if (failed > 0) {
			items.add(new AdminAlertItem(
				"batch-failure",
				"배치 실패",
				"최근 실행 " + failed + "건 실패",
				"failure",
				AdminProgram.BATCHES.path()));
		}
	}

	private void addDatapackBlocker(List<AdminAlertItem> items, List<AdminProgram> visible) {
		if (!visible.contains(AdminProgram.DATAPACK_CANDIDATES)) {
			return;
		}
		long blockers = datapackReleaseBlockerSummaryUseCase.summarize().totalBlockers();
		if (blockers > 0) {
			items.add(new AdminAlertItem(
				"datapack-blocker",
				"데이터팩 릴리즈 blocker",
				blockers + "건",
				"warning",
				AdminProgram.DATAPACK_CANDIDATES.path()));
		}
	}

	// 지표 스냅샷(#1739) 잡의 마지막 실행이 실패면 대시보드 화면이 보이는 계정에 노출한다.
	private void addMetricSnapshotFailure(List<AdminAlertItem> items, List<AdminProgram> visible) {
		if (!visible.contains(AdminProgram.DASHBOARD)) {
			return;
		}
		if (metricSnapshotStatusHolder.isFailing()) {
			items.add(new AdminAlertItem(
				"metric-snapshot-failure",
				"지표 스냅샷 실패",
				"최근 집계가 실패했습니다",
				"failure",
				AdminProgram.DASHBOARD.path()));
		}
	}
}
