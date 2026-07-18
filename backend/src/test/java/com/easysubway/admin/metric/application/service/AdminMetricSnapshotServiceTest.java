package com.easysubway.admin.metric.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.easysubway.admin.metric.adapter.out.persistence.InMemoryAdminMetricDailyRepository;
import com.easysubway.admin.metric.application.port.out.AdminMetricDailyRepository;
import com.easysubway.admin.metric.domain.AdminMetricDaily;
import com.easysubway.admin.metric.domain.AdminMetricKeys;
import com.easysubway.quality.application.port.in.DataQualityUseCase;
import com.easysubway.quality.domain.DataQualitySummary;
import com.easysubway.notification.application.port.in.PushNotificationDashboardUseCase;
import com.easysubway.notification.domain.PushNotificationDashboardSummary;
import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.domain.RouteSearchDashboardSummary.MobilityTypeCount;
import com.easysubway.report.application.port.in.FacilityReportUseCase;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.ReportProcessingTimeSummary;
import com.easysubway.route.application.port.in.RouteFeedbackDashboardUseCase;
import com.easysubway.route.application.port.in.RouteSearchDashboardUseCase;
import com.easysubway.route.domain.RouteFeedbackDashboardSummary;
import com.easysubway.route.domain.RouteSearchDashboardSummary;
import com.easysubway.usage.application.port.in.UserActivityDashboardUseCase;
import com.easysubway.usage.domain.UserActivityDashboardSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

@DisplayName("일별 지표 스냅샷 집계 서비스")
class AdminMetricSnapshotServiceTest {

	private static final LocalDate DATE = LocalDate.of(2026, 7, 5);

	private final FacilityReportUseCase reportUseCase = mock(FacilityReportUseCase.class);
	private final DataQualityUseCase qualityUseCase = mock(DataQualityUseCase.class);
	private final RouteSearchDashboardUseCase routeUseCase = mock(RouteSearchDashboardUseCase.class);
	private final RouteFeedbackDashboardUseCase feedbackUseCase = mock(RouteFeedbackDashboardUseCase.class);
	private final PushNotificationDashboardUseCase pushUseCase = mock(PushNotificationDashboardUseCase.class);
	private final UserActivityDashboardUseCase usageUseCase = mock(UserActivityDashboardUseCase.class);
	private final InMemoryAdminMetricDailyRepository repository = new InMemoryAdminMetricDailyRepository();
	private final AdminMetricSnapshotStatusHolder statusHolder = new AdminMetricSnapshotStatusHolder();
	private final AdminMetricSnapshotService service = new AdminMetricSnapshotService(
		reportUseCase, qualityUseCase, routeUseCase, feedbackUseCase, pushUseCase, usageUseCase,
		repository, statusHolder, fixedClock());

	@Test
	@DisplayName("모든 지표를 대시보드 즉석 계산과 같은 값으로 적재한다")
	void snapshotsAllMetricsWithDashboardValues() {
		stubHappyPath();

		service.snapshot(DATE);

		assertThat(value(AdminMetricKeys.REPORTS_RECENT_24H)).isEqualTo(12);
		assertThat(value(AdminMetricKeys.REPORTS_PENDING)).isEqualTo(8); // SUBMITTED 5 + UNDER_REVIEW 3
		assertThat(value(AdminMetricKeys.REPORTS_PROCESSING_AVG_MINUTES)).isEqualTo(45);
		assertThat(value(AdminMetricKeys.FACILITIES_NEEDS_VERIFICATION)).isEqualTo(7);
		assertThat(value(AdminMetricKeys.FACILITIES_DELAYED)).isEqualTo(2);
		assertThat(value(AdminMetricKeys.ROUTE_SEARCHES)).isEqualTo(100);
		assertThat(value(AdminMetricKeys.ROUTE_BLOCKED_RATE)).isEqualTo(25.0); // 25/100
		assertThat(value(AdminMetricKeys.ROUTE_FEEDBACK_HELPFUL)).isEqualTo(40);
		assertThat(value(AdminMetricKeys.ROUTE_FEEDBACK_NOT_HELPFUL)).isEqualTo(8);
		assertThat(value(AdminMetricKeys.ROUTE_FEEDBACK_BLOCKED)).isEqualTo(2);
		assertThat(value(AdminMetricKeys.PUSH_ATTEMPTED)).isEqualTo(50);
		assertThat(value(AdminMetricKeys.PUSH_FAILED)).isEqualTo(4);
		assertThat(value(AdminMetricKeys.API_ERROR_RATE)).isEqualTo(1.0); // 10/1000
		assertThat(value(AdminMetricKeys.USERS_ACTIVE)).isEqualTo(30);
		assertThat(statusHolder.isFailing()).isFalse();
		assertThat(statusHolder.latest()).hasValueSatisfying(s -> assertThat(s.success()).isTrue());
	}

	@Test
	@DisplayName("같은 날짜 재실행은 값을 덮어쓰고 행을 늘리지 않는다(멱등)")
	void reRunIsIdempotent() {
		stubHappyPath();
		service.snapshot(DATE);
		when(reportUseCase.countReportsCreatedSince(any())).thenReturn(99L);

		service.snapshot(DATE);

		assertThat(value(AdminMetricKeys.REPORTS_RECENT_24H)).isEqualTo(99);
		assertThat(repository.findByKeysAndDateRange(AdminMetricKeys.all(), DATE, DATE))
			.hasSize(AdminMetricKeys.all().size());
	}

	@Test
	@DisplayName("집계 실패는 상태 홀더에 기록하고 예외를 다시 던진다")
	void failureRecordsStatusAndRethrows() {
		when(reportUseCase.countReportsByStatus()).thenThrow(new IllegalStateException("집계 실패"));

		assertThatThrownBy(() -> service.snapshot(DATE)).isInstanceOf(IllegalStateException.class);

		assertThat(statusHolder.isFailing()).isTrue();
		assertThat(statusHolder.latest()).hasValueSatisfying(s -> {
			assertThat(s.success()).isFalse();
			assertThat(s.message()).contains("집계 실패");
		});
	}

	@Test
	@DisplayName("스냅샷 중간 저장이 실패하면 같은 실행의 앞선 write까지 전부 rollback한다")
	void partialFailureRollsBackWholeSnapshot() {
		stubHappyPath();
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:admin-metric-rollback;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS admin_metric_daily");
		jdbcTemplate.execute("""
			CREATE TABLE admin_metric_daily (
				metric_key VARCHAR(80) NOT NULL,
				metric_date DATE NOT NULL,
				metric_value DOUBLE PRECISION NOT NULL,
				dimensions VARCHAR(2000),
				PRIMARY KEY (metric_key, metric_date)
			)
			""");
		JdbcTemplateBackedRepository jdbcRepository = new JdbcTemplateBackedRepository(jdbcTemplate);
		// 6번째 저장에서 실패시켜, 앞서 커밋되지 않은 5건이 rollback되는지 확인한다.
		FailOnNthSaveRepository failingRepository = new FailOnNthSaveRepository(jdbcRepository, 6);
		AdminMetricSnapshotService transactionalService = transactionalProxy(
			new AdminMetricSnapshotService(
				reportUseCase, qualityUseCase, routeUseCase, feedbackUseCase, pushUseCase, usageUseCase,
				failingRepository, statusHolder, fixedClock()),
			dataSource);

		assertThatThrownBy(() -> transactionalService.snapshot(DATE)).isInstanceOf(IllegalStateException.class);

		assertThat(jdbcRepository.findByKeysAndDateRange(AdminMetricKeys.all(), DATE, DATE)).isEmpty();
		assertThat(statusHolder.isFailing()).isTrue();
	}

	private static AdminMetricSnapshotService transactionalProxy(
		AdminMetricSnapshotService target, DriverManagerDataSource dataSource) {
		TransactionInterceptor interceptor = new TransactionInterceptor(
			new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource());
		ProxyFactory proxyFactory = new ProxyFactory(target);
		proxyFactory.setProxyTargetClass(true);
		proxyFactory.addAdvice(interceptor);
		return (AdminMetricSnapshotService) proxyFactory.getProxy();
	}

	/** N번째 save에서 실패시켜 트랜잭션 rollback 경계를 검증하는 저장소 데코레이터. 나머지는 위임한다. */
	private static final class FailOnNthSaveRepository implements AdminMetricDailyRepository {

		private final AdminMetricDailyRepository delegate;
		private final int failOnCall;
		private final AtomicInteger saveCalls = new AtomicInteger();

		private FailOnNthSaveRepository(AdminMetricDailyRepository delegate, int failOnCall) {
			this.delegate = delegate;
			this.failOnCall = failOnCall;
		}

		@Override
		public void save(AdminMetricDaily metric) {
			if (saveCalls.incrementAndGet() == failOnCall) {
				throw new IllegalStateException("스냅샷 저장 실패(테스트 주입)");
			}
			delegate.save(metric);
		}

		@Override
		public Optional<AdminMetricDaily> find(String metricKey, LocalDate metricDate) {
			return delegate.find(metricKey, metricDate);
		}

		@Override
		public List<AdminMetricDaily> findByKeysAndDateRange(
			Collection<String> metricKeys, LocalDate fromInclusive, LocalDate toInclusive) {
			return delegate.findByKeysAndDateRange(metricKeys, fromInclusive, toInclusive);
		}
	}

	/**
	 * 공유 dataSource 위에서 동작하는 최소 JDBC 저장소. save는 단순 INSERT라 Spring이 관리하는
	 * 트랜잭션에 참여하며, 실패 시 rollback 여부를 SELECT로 관찰할 수 있다.
	 */
	private static final class JdbcTemplateBackedRepository implements AdminMetricDailyRepository {

		private final JdbcTemplate jdbcTemplate;

		private JdbcTemplateBackedRepository(JdbcTemplate jdbcTemplate) {
			this.jdbcTemplate = jdbcTemplate;
		}

		@Override
		public void save(AdminMetricDaily metric) {
			jdbcTemplate.update(
				"INSERT INTO admin_metric_daily (metric_key, metric_date, metric_value, dimensions) "
					+ "VALUES (?, ?, ?, ?)",
				metric.metricKey(),
				metric.metricDate(),
				metric.value(),
				metric.dimensions()
			);
		}

		@Override
		public Optional<AdminMetricDaily> find(String metricKey, LocalDate metricDate) {
			return findByKeysAndDateRange(List.of(metricKey), metricDate, metricDate).stream().findFirst();
		}

		@Override
		public List<AdminMetricDaily> findByKeysAndDateRange(
			Collection<String> metricKeys, LocalDate fromInclusive, LocalDate toInclusive) {
			if (metricKeys.isEmpty()) {
				return List.of();
			}
			String placeholders = String.join(", ", metricKeys.stream().map(key -> "?").toList());
			Object[] args = new Object[metricKeys.size() + 2];
			int index = 0;
			for (String key : metricKeys) {
				args[index++] = key;
			}
			args[index++] = fromInclusive;
			args[index] = toInclusive;
			return jdbcTemplate.query(
				"SELECT metric_key, metric_date, metric_value, dimensions FROM admin_metric_daily "
					+ "WHERE metric_key IN (" + placeholders + ") AND metric_date BETWEEN ? AND ?",
				(rs, rowNum) -> new AdminMetricDaily(
					rs.getString("metric_key"),
					rs.getObject("metric_date", LocalDate.class),
					rs.getDouble("metric_value"),
					rs.getString("dimensions")),
				args
			);
		}
	}

	private void stubHappyPath() {
		when(reportUseCase.countReportsByStatus()).thenReturn(Map.of(
			FacilityReportStatus.SUBMITTED, 5L, FacilityReportStatus.UNDER_REVIEW, 3L));
		when(reportUseCase.countReportsCreatedSince(any())).thenReturn(12L);
		when(reportUseCase.summarizeReportProcessingTime()).thenReturn(new ReportProcessingTimeSummary(6, 45));
		when(qualityUseCase.summarizeDataQuality()).thenReturn(qualitySummary(7, 2));
		when(routeUseCase.summarizeRouteSearches())
			.thenReturn(new RouteSearchDashboardSummary(100, 75, 25,
				List.of(new MobilityTypeCount(MobilityType.values()[0], 100))));
		when(feedbackUseCase.summarizeRouteFeedbacks())
			.thenReturn(new RouteFeedbackDashboardSummary(50, 40, 8, 2, List.of()));
		when(pushUseCase.summarizePushNotifications())
			.thenReturn(new PushNotificationDashboardSummary(50, 6, 40, 4));
		when(usageUseCase.summarizeUserActivity())
			.thenReturn(new UserActivityDashboardSummary(30, 1000, 10, 5000, List.of()));
	}

	private double value(String metricKey) {
		Optional<AdminMetricDaily> metric = repository.find(metricKey, DATE);
		assertThat(metric).as(metricKey).isPresent();
		return metric.orElseThrow().value();
	}

	private static DataQualitySummary qualitySummary(long needsVerification, long delayed) {
		return new DataQualitySummary(
			0, 0, 0, Map.of(), List.of(), Map.of(), Map.of(),
			needsVerification, delayed, Map.of(), 0, List.of(), List.of());
	}

	private static Clock fixedClock() {
		return Clock.fixed(Instant.parse("2026-07-05T00:30:00Z"), ZoneId.of("Asia/Seoul"));
	}
}
