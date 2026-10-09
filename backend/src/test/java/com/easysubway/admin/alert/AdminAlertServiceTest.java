package com.easysubway.admin.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.easysubway.admin.authorization.AdminPermission;
import com.easysubway.admin.metric.application.service.AdminMetricSnapshotStatusHolder;
import com.easysubway.collection.application.port.in.DataCollectionUseCase;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStatus;
import com.easysubway.datapack.application.port.in.AutomationStatusUseCase;
import com.easysubway.datapack.application.port.in.AutomationStatusUseCase.Reading;
import com.easysubway.datapack.application.port.in.DatapackReleaseBlockerSummaryUseCase;
import com.easysubway.datapack.application.port.in.DatapackReleaseBlockerSummaryUseCase.DatapackReleaseBlockerSummary;
import com.easysubway.datapack.domain.AutomationAssessment;
import com.easysubway.datapack.domain.AutomationAssessment.Level;
import com.easysubway.notification.application.port.in.PushNotificationDashboardUseCase;
import com.easysubway.notification.domain.PushNotificationDashboardSummary;
import com.easysubway.report.application.port.in.FacilityReportUseCase;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@DisplayName("관리자 알림 센터 서비스")
class AdminAlertServiceTest {

	private final FacilityReportUseCase reportUseCase = mock(FacilityReportUseCase.class);
	private final PushNotificationDashboardUseCase pushUseCase = mock(PushNotificationDashboardUseCase.class);
	private final DataCollectionUseCase collectionUseCase = mock(DataCollectionUseCase.class);
	private final DatapackReleaseBlockerSummaryUseCase datapackUseCase =
		mock(DatapackReleaseBlockerSummaryUseCase.class);
	private final AdminMetricSnapshotStatusHolder metricStatusHolder = new AdminMetricSnapshotStatusHolder();
	private final AutomationStatusUseCase automationUseCase = mock(AutomationStatusUseCase.class);
	private final AdminAlertService service = new AdminAlertService(
		reportUseCase, pushUseCase, collectionUseCase, datapackUseCase, metricStatusHolder, automationUseCase, fixedClock());

	@BeforeEach
	void healthyAutomationByDefault() {
		when(automationUseCase.read()).thenReturn(automation(Level.OK, "정상: 모든 자동화 단계가 정상입니다"));
		// DATAPACK_READ 권한은 후보 화면(릴리즈 blocker 알림)도 함께 보이게 한다.
		when(datapackUseCase.summarize()).thenReturn(blockerSummary(0));
	}

	private static Reading automation(Level level, String headline) {
		Instant now = Instant.parse("2026-10-10T03:00:00Z");
		return new Reading(Optional.empty(), now, now,
			new AutomationAssessment(level, headline, List.of(), true, null, null));
	}

	@Test
	@DisplayName("자동화 이상(이상·주의)이 있으면 자동화 상태 화면으로 딥링크하는 알림을 준다")
	void automationAnomalyAlert() {
		when(automationUseCase.read()).thenReturn(automation(Level.FAILURE, "열린 자동화 실패 이슈 2건"));
		AdminAlertSummary failure = service.summarize(authWith(AdminPermission.DATAPACK_READ));
		assertThat(failure.items()).singleElement().satisfies(item -> {
			assertThat(item.id()).isEqualTo("automation-status");
			assertThat(item.tone()).isEqualTo("failure");
			assertThat(item.detail()).isEqualTo("열린 자동화 실패 이슈 2건");
			assertThat(item.href()).isEqualTo("/admin/datapack/automation/page");
		});

		when(automationUseCase.read()).thenReturn(automation(Level.WARNING, "막힌 자동화 1건"));
		assertThat(service.summarize(authWith(AdminPermission.DATAPACK_READ)).items()).singleElement()
			.satisfies(item -> assertThat(item.tone()).isEqualTo("warning"));
	}

	@Test
	@DisplayName("정상이거나 아직 수신 전이면 자동화 알림을 만들지 않고, 화면 권한이 없으면 조회하지 않는다")
	void noAutomationAlertWhenHealthyUnknownOrNotPermitted() {
		assertThat(service.summarize(authWith(AdminPermission.DATAPACK_READ)).items()).isEmpty();

		when(automationUseCase.read()).thenReturn(automation(Level.UNKNOWN, "자동화 상태를 아직 받지 못했습니다(수신 전)"));
		assertThat(service.summarize(authWith(AdminPermission.DATAPACK_READ)).items()).isEmpty();

		org.mockito.Mockito.clearInvocations(automationUseCase);
		when(automationUseCase.read()).thenReturn(automation(Level.FAILURE, "이상"));
		assertThat(service.summarize(authWith(AdminPermission.REPORT_REVIEW)).items()).noneMatch(item -> "automation-status".equals(item.id()));
		org.mockito.Mockito.verifyNoInteractions(automationUseCase);
	}

	@Test
	@DisplayName("24시간 신고가 임계값 이상이면 신고 급증 알림을 딥링크와 함께 준다")
	void reportSurgeAlert() {
		when(reportUseCase.countReportsCreatedSince(any())).thenReturn(12L);

		AdminAlertSummary summary = service.summarize(authWith(AdminPermission.REPORT_REVIEW));

		assertThat(summary.items()).singleElement().satisfies(item -> {
			assertThat(item.id()).isEqualTo("report-surge");
			assertThat(item.href()).isEqualTo("/admin/reports/page");
			assertThat(item.tone()).isEqualTo("warning");
		});
		assertThat(summary.count()).isEqualTo(1);
		assertThat(summary.hasAlerts()).isTrue();
	}

	@Test
	@DisplayName("신고가 임계값 미만이면 급증 알림이 없다")
	void noReportSurgeBelowThreshold() {
		when(reportUseCase.countReportsCreatedSince(any())).thenReturn(3L);

		AdminAlertSummary summary = service.summarize(authWith(AdminPermission.REPORT_REVIEW));

		assertThat(summary.items()).isEmpty();
		assertThat(summary.hasAlerts()).isFalse();
	}

	@Test
	@DisplayName("푸시 발송 실패가 있으면 푸시 실패 알림을 딥링크와 함께 준다")
	void pushFailureAlert() {
		when(pushUseCase.summarizePushNotifications())
			.thenReturn(new PushNotificationDashboardSummary(5, 0, 2, 3));

		AdminAlertSummary summary = service.summarize(authWith(AdminPermission.DATA_OPERATE));

		assertThat(summary.items())
			.filteredOn(item -> item.id().equals("push-failure"))
			.singleElement()
			.satisfies(item -> {
				assertThat(item.href()).isEqualTo("/admin/notifications/push/page?status=FAILED");
				assertThat(item.tone()).isEqualTo("failure");
			});
	}

	@Test
	@DisplayName("최근 배치 실행에 실패가 있으면 배치 실패 알림을 딥링크와 함께 준다")
	void batchFailureAlert() {
		when(pushUseCase.summarizePushNotifications())
			.thenReturn(new PushNotificationDashboardSummary(0, 0, 0, 0));
		when(collectionUseCase.listRecentRuns(anyInt()))
			.thenReturn(List.of(run(DataCollectionStatus.COMPLETED), run(DataCollectionStatus.FAILED)));

		AdminAlertSummary summary = service.summarize(authWith(AdminPermission.DATA_OPERATE));

		assertThat(summary.items())
			.filteredOn(item -> item.id().equals("batch-failure"))
			.singleElement()
			.satisfies(item -> {
				assertThat(item.href()).isEqualTo("/admin/batches/page");
				assertThat(item.tone()).isEqualTo("failure");
			});
	}

	@Test
	@DisplayName("데이터팩 릴리즈 blocker가 있으면 blocker 알림을 딥링크와 함께 준다")
	void datapackBlockerAlert() {
		when(datapackUseCase.summarize()).thenReturn(blockerSummary(4));

		AdminAlertSummary summary = service.summarize(authWith(AdminPermission.DATAPACK_READ));

		assertThat(summary.items())
			.filteredOn(item -> item.id().equals("datapack-blocker"))
			.singleElement()
			.satisfies(item -> {
				assertThat(item.href()).isEqualTo("/admin/datapack/candidates/page");
				assertThat(item.tone()).isEqualTo("warning");
			});
	}

	@Test
	@DisplayName("지표 스냅샷 마지막 실행이 실패면 스냅샷 실패 알림을 딥링크와 함께 준다")
	void metricSnapshotFailureAlert() {
		metricStatusHolder.recordFailure(
			LocalDateTime.of(2026, 7, 5, 0, 10), LocalDate.of(2026, 7, 5), "집계 실패");

		AdminAlertSummary summary = service.summarize(authWith(AdminPermission.ADMIN_VIEW));

		assertThat(summary.items())
			.filteredOn(item -> item.id().equals("metric-snapshot-failure"))
			.singleElement()
			.satisfies(item -> {
				assertThat(item.href()).isEqualTo("/admin/dashboard/page");
				assertThat(item.tone()).isEqualTo("failure");
			});
	}

	@Test
	@DisplayName("신호원 화면 권한이 없으면 해당 알림은 제외된다")
	void signalsExcludedWithoutPermission() {
		// 감사 전용 계정: 제보·푸시·배치·데이터팩 화면이 안 보이므로 모든 신호 제외.
		when(reportUseCase.countReportsCreatedSince(any())).thenReturn(50L);
		when(datapackUseCase.summarize()).thenReturn(blockerSummary(9));

		AdminAlertSummary summary = service.summarize(authWith(AdminPermission.AUDIT_READ));

		assertThat(summary.items()).isEmpty();
	}

	private static Authentication authWith(AdminPermission permission) {
		return new UsernamePasswordAuthenticationToken(
			"tester", "n/a", List.of(new SimpleGrantedAuthority(permission.authority())));
	}

	private static Clock fixedClock() {
		return Clock.fixed(Instant.parse("2026-07-05T09:00:00Z"), ZoneId.of("Asia/Seoul"));
	}

	private static DataCollectionRun run(DataCollectionStatus status) {
		return new DataCollectionRun(
			"run-1",
			DataCollectionSource.TRANSIT_MASTER,
			status,
			"scheduler",
			LocalDateTime.now(),
			LocalDateTime.now(),
			0,
			status == DataCollectionStatus.FAILED ? "수집 실패" : null,
			status == DataCollectionStatus.FAILED,
			"확인");
	}

	private static DatapackReleaseBlockerSummary blockerSummary(long totalBlockers) {
		return new DatapackReleaseBlockerSummary(
			"candidate", "scope", "sourceHash", "manifestHash", "evidenceHash", "workflowUrl",
			"prodCandidate", "rollbackCandidate", "검토 필요",
			totalBlockers, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			List.of(), LocalDateTime.now());
	}
}
