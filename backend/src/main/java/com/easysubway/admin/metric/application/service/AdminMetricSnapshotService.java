package com.easysubway.admin.metric.application.service;

import com.easysubway.admin.metric.application.port.out.AdminMetricDailyRepository;
import com.easysubway.admin.metric.domain.AdminMetricDaily;
import com.easysubway.admin.metric.domain.AdminMetricKeys;
import com.easysubway.quality.application.port.in.DataQualityUseCase;
import com.easysubway.quality.domain.DataQualitySummary;
import com.easysubway.notification.application.port.in.PushNotificationDashboardUseCase;
import com.easysubway.notification.domain.PushNotificationDashboardSummary;
import com.easysubway.report.application.port.in.FacilityReportUseCase;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.route.application.port.in.RouteFeedbackDashboardUseCase;
import com.easysubway.route.application.port.in.RouteSearchDashboardUseCase;
import com.easysubway.route.domain.RouteFeedbackDashboardSummary;
import com.easysubway.route.domain.RouteSearchDashboardSummary;
import com.easysubway.usage.application.port.in.UserActivityDashboardUseCase;
import com.easysubway.usage.domain.UserActivityDashboardSummary;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일별 지표 스냅샷 집계(#1739). 대시보드가 즉석 계산하던 값과 <b>같은 use case</b>에서 뽑아
 * 하루 1회 {@link AdminMetricDaily}로 적재한다(정합 보장). 같은 날짜 재실행은 upsert로 멱등하다.
 *
 * <p>수집 도메인(DataCollectionSource/Run)과 결이 달라 독립 스케줄 잡으로 둔다. 스케줄러·수동
 * 재실행이 이 서비스를 호출하고, 성공/실패 상태는 {@link AdminMetricSnapshotStatusHolder}에 기록해
 * 대시보드·알림 센터가 읽는다.
 */
@Service
public class AdminMetricSnapshotService {

	private static final int RECENT_REPORT_LOOKBACK_HOURS = 24;

	private final FacilityReportUseCase facilityReportUseCase;
	private final DataQualityUseCase dataQualityUseCase;
	private final RouteSearchDashboardUseCase routeSearchDashboardUseCase;
	private final RouteFeedbackDashboardUseCase routeFeedbackDashboardUseCase;
	private final PushNotificationDashboardUseCase pushNotificationDashboardUseCase;
	private final UserActivityDashboardUseCase userActivityDashboardUseCase;
	private final AdminMetricDailyRepository repository;
	private final AdminMetricSnapshotStatusHolder statusHolder;
	private final Clock clock;

	@Autowired
	public AdminMetricSnapshotService(
		FacilityReportUseCase facilityReportUseCase,
		DataQualityUseCase dataQualityUseCase,
		RouteSearchDashboardUseCase routeSearchDashboardUseCase,
		RouteFeedbackDashboardUseCase routeFeedbackDashboardUseCase,
		PushNotificationDashboardUseCase pushNotificationDashboardUseCase,
		UserActivityDashboardUseCase userActivityDashboardUseCase,
		AdminMetricDailyRepository repository,
		AdminMetricSnapshotStatusHolder statusHolder,
		ObjectProvider<Clock> clockProvider
	) {
		this(
			facilityReportUseCase,
			dataQualityUseCase,
			routeSearchDashboardUseCase,
			routeFeedbackDashboardUseCase,
			pushNotificationDashboardUseCase,
			userActivityDashboardUseCase,
			repository,
			statusHolder,
			clockProvider.getIfAvailable(Clock::systemDefaultZone));
	}

	AdminMetricSnapshotService(
		FacilityReportUseCase facilityReportUseCase,
		DataQualityUseCase dataQualityUseCase,
		RouteSearchDashboardUseCase routeSearchDashboardUseCase,
		RouteFeedbackDashboardUseCase routeFeedbackDashboardUseCase,
		PushNotificationDashboardUseCase pushNotificationDashboardUseCase,
		UserActivityDashboardUseCase userActivityDashboardUseCase,
		AdminMetricDailyRepository repository,
		AdminMetricSnapshotStatusHolder statusHolder,
		Clock clock
	) {
		this.facilityReportUseCase = facilityReportUseCase;
		this.dataQualityUseCase = dataQualityUseCase;
		this.routeSearchDashboardUseCase = routeSearchDashboardUseCase;
		this.routeFeedbackDashboardUseCase = routeFeedbackDashboardUseCase;
		this.pushNotificationDashboardUseCase = pushNotificationDashboardUseCase;
		this.userActivityDashboardUseCase = userActivityDashboardUseCase;
		this.repository = repository;
		this.statusHolder = statusHolder;
		this.clock = clock;
	}

	/**
	 * 오늘 날짜로 스냅샷을 집계한다(스케줄러·수동 재실행 진입점).
	 *
	 * <p>{@link #snapshot(LocalDate)}를 내부 호출하므로, 프록시 자기호출로 트랜잭션이 새지 않도록
	 * 이 진입점에도 {@link Transactional}을 둔다(#2273). 한 실행의 모든 지표 write가 한 트랜잭션이다.
	 */
	@Transactional
	public void snapshotToday() {
		snapshot(LocalDate.now(clock));
	}

	/**
	 * 주어진 날짜로 지표를 집계해 upsert한다. 실패는 상태 홀더에 기록하고 예외를 다시 던진다
	 * (스케줄러·엔드포인트가 로깅/응답을 처리).
	 *
	 * <p>한 실행의 모든 지표 write를 한 트랜잭션으로 묶어, 한 지표 저장이 실패하면 같은 실행에서
	 * 앞서 저장한 지표까지 전부 rollback한다(#2273). 상태 홀더 기록은 DB 밖이라 rollback되지 않는다.
	 */
	@Transactional
	public void snapshot(LocalDate date) {
		try {
			Map<FacilityReportStatus, Long> reportCounts = facilityReportUseCase.countReportsByStatus();
			long pending = count(reportCounts, FacilityReportStatus.SUBMITTED)
				+ count(reportCounts, FacilityReportStatus.UNDER_REVIEW);
			long recent = facilityReportUseCase.countReportsCreatedSince(
				LocalDateTime.now(clock).minusHours(RECENT_REPORT_LOOKBACK_HOURS));
			long avgProcessingMinutes = facilityReportUseCase.summarizeReportProcessingTime().averageProcessingMinutes();

			DataQualitySummary quality = dataQualityUseCase.summarizeDataQuality();
			RouteSearchDashboardSummary routes = routeSearchDashboardUseCase.summarizeRouteSearches();
			RouteFeedbackDashboardSummary feedback = routeFeedbackDashboardUseCase.summarizeRouteFeedbacks();
			PushNotificationDashboardSummary push = pushNotificationDashboardUseCase.summarizePushNotifications();
			UserActivityDashboardSummary usage = userActivityDashboardUseCase.summarizeUserActivity();

			save(date, AdminMetricKeys.REPORTS_RECENT_24H, recent);
			save(date, AdminMetricKeys.REPORTS_PENDING, pending);
			save(date, AdminMetricKeys.REPORTS_PROCESSING_AVG_MINUTES, avgProcessingMinutes);
			save(date, AdminMetricKeys.FACILITIES_NEEDS_VERIFICATION, quality.needsVerificationFacilityCount());
			save(date, AdminMetricKeys.FACILITIES_DELAYED, quality.delayedFacilityStatusCount());
			save(date, AdminMetricKeys.ROUTE_SEARCHES, routes.totalCount());
			save(date, AdminMetricKeys.ROUTE_BLOCKED_RATE, percent(routes.blockedCount(), routes.totalCount()));
			save(date, AdminMetricKeys.ROUTE_FEEDBACK_HELPFUL, feedback.helpfulCount());
			save(date, AdminMetricKeys.ROUTE_FEEDBACK_NOT_HELPFUL, feedback.notHelpfulCount());
			save(date, AdminMetricKeys.ROUTE_FEEDBACK_BLOCKED, feedback.blockedByRealWorldCount());
			save(date, AdminMetricKeys.PUSH_ATTEMPTED, push.totalCount());
			save(date, AdminMetricKeys.PUSH_FAILED, push.failedCount());
			save(date, AdminMetricKeys.API_ERROR_RATE, percent(usage.totalApiErrors(), usage.totalApiRequests()));
			save(date, AdminMetricKeys.USERS_ACTIVE, usage.totalActiveUsers());

			statusHolder.recordSuccess(LocalDateTime.now(clock), date);
		} catch (RuntimeException exception) {
			statusHolder.recordFailure(LocalDateTime.now(clock), date, exception.getMessage());
			throw exception;
		}
	}

	private void save(LocalDate date, String metricKey, double value) {
		repository.save(AdminMetricDaily.scalar(metricKey, date, value));
	}

	private static long count(Map<FacilityReportStatus, Long> counts, FacilityReportStatus status) {
		return counts.getOrDefault(status, 0L);
	}

	private static double percent(long part, long total) {
		return total == 0 ? 0.0 : (double) part * 100 / total;
	}
}
