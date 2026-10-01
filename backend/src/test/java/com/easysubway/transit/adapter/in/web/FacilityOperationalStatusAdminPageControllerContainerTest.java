package com.easysubway.transit.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.admin.audit.adapter.out.persistence.JdbcAdminAuditEventRepository;
import com.easysubway.admin.audit.application.AdminAuditActorContext;
import com.easysubway.admin.audit.application.AdminAuditQuery;
import com.easysubway.admin.audit.application.port.out.AdminAuditEventRepository;
import com.easysubway.admin.audit.application.service.AdminAuditWriter;
import com.easysubway.admin.audit.domain.AdminAuditEvent;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import com.easysubway.transit.adapter.in.web.FacilityOperationalStatusAdminPageController.VerificationForm;
import com.easysubway.transit.adapter.out.persistence.JdbcFacilityOperationalStatusRepository;
import com.easysubway.transit.application.port.out.LoadBundleElevatorFacilitiesPort.BundleElevatorFacility;
import com.easysubway.transit.application.service.FacilityOperationalStatusAdminService;
import com.easysubway.transit.domain.FacilityOperationalState;
import com.easysubway.transit.domain.FacilityStatusSource;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 관리자 확인 기록의 트랜잭션 경로(컨트롤러 {@code @Transactional} → 상태 저장소 → JDBC 감사 기록)를 PostgreSQL에서 그대로
 * 돌린다(#442). H2는 중복 키 오류 뒤에도 트랜잭션을 계속 쓸 수 있어, 첫 기록이 겹칠 때 PostgreSQL에서만 드러나는
 * 문제(트랜잭션 중단 후 감사 기록 실패)를 H2 테스트로는 볼 수 없다.
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("PostgreSQL 관리자 엘리베이터 가동 상태 확인 기록 트랜잭션")
class FacilityOperationalStatusAdminPageControllerContainerTest {

	private static final String EXIT_1 = "smrt-elev:0201:2:1번 출입구";
	private static final String VIEW = "admin/reports/elevator-status";
	private static final Duration WAIT = Duration.ofSeconds(10);

	@Container
	private static final PostgreSQLContainer<?> POSTGRES =
		new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

	private AnnotationConfigApplicationContext context;
	private JdbcTemplate jdbcTemplate;

	@BeforeAll
	static void migrate() {
		Flyway.configure()
			.configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
			.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration/postgresql")
			.load()
			.migrate();
	}

	@BeforeEach
	void setUp() {
		HoldingAuditRepository.reset();
		context = new AnnotationConfigApplicationContext(TransactionalControllerConfig.class);
		jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
		jdbcTemplate.update("DELETE FROM facility_operational_status");
		jdbcTemplate.update("DELETE FROM admin_audit_events WHERE target_type = 'FACILITY_OPERATIONAL_STATUS'");
	}

	@AfterEach
	void tearDown() {
		HoldingAuditRepository.release.countDown();
		context.close();
	}

	@Test
	@DisplayName("행이 없는 시설의 첫 기록이 겹치면 늦은 요청은 409로 끝나고 실패 감사가 남으며 앞선 기록만 커밋된다")
	void concurrentFirstRecordsLeaveTheLoserTransactionUsableForTheConflictAudit() throws Exception {
		var controller = context.getBean(FacilityOperationalStatusAdminPageController.class);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			HoldingAuditRepository.holdSuccessAudit = true;
			Future<Outcome> winner = executor.submit(() -> record(controller, FacilityOperationalState.OPERATING));
			assertThat(HoldingAuditRepository.holding.await(WAIT.toSeconds(), TimeUnit.SECONDS))
				.as("앞선 요청이 상태 행을 INSERT하고 커밋 전 감사 기록 단계에서 멈춘다")
				.isTrue();

			Future<Outcome> loser = executor.submit(() -> record(controller, FacilityOperationalState.OUT_OF_SERVICE));
			awaitLockWaiter();
			assertThat(loser.isDone()).as("늦은 요청은 같은 시설 INSERT에서 앞선 트랜잭션을 기다린다").isFalse();
			HoldingAuditRepository.release.countDown();

			Outcome winnerOutcome = winner.get(WAIT.toSeconds(), TimeUnit.SECONDS);
			Outcome loserOutcome = loser.get(WAIT.toSeconds(), TimeUnit.SECONDS);

			assertThat(winnerOutcome.view()).isEqualTo("redirect:" + FacilityOperationalStatusAdminPageController.PAGE);
			assertThat(loserOutcome.view()).isEqualTo(VIEW);
			assertThat(loserOutcome.status()).isEqualTo(HttpServletResponse.SC_CONFLICT);
		} finally {
			executor.shutdownNow();
		}

		var store = context.getBean(JdbcFacilityOperationalStatusRepository.class);
		assertThat(store.loadStatuses()).singleElement().satisfies(row -> {
			assertThat(row.facilityId()).isEqualTo(EXIT_1);
			assertThat(row.status()).isEqualTo(FacilityOperationalState.OPERATING);
			assertThat(row.source()).isEqualTo(FacilityStatusSource.ADMIN_VERIFIED);
		});
		var audits = context.getBean(AdminAuditEventRepository.class)
			.findRecent(AdminAuditEventType.ADMIN_ACTION, 10)
			.stream()
			.filter(event -> "FACILITY_OPERATIONAL_STATUS".equals(event.targetType()))
			.toList();
		assertThat(audits).extracting(AdminAuditEvent::outcome, AdminAuditEvent::reason, AdminAuditEvent::targetId)
			.containsExactlyInAnyOrder(
				org.assertj.core.groups.Tuple.tuple(AdminAuditOutcome.SUCCESS, "from=NONE to=OPERATING", EXIT_1),
				org.assertj.core.groups.Tuple.tuple(AdminAuditOutcome.FAILURE, "NEWER_OBSERVATION", EXIT_1)
			);
	}

	private static Outcome record(FacilityOperationalStatusAdminPageController controller, FacilityOperationalState state) {
		var form = new VerificationForm(EXIT_1, state);
		var response = new MockHttpServletResponse();
		String view = controller.recordVerifiedState(
			form,
			new BeanPropertyBindingResult(form, "verificationForm"),
			new ExtendedModelMap(),
			response,
			UsernamePasswordAuthenticationToken.authenticated(
				"admin-test", "n/a", List.of(new SimpleGrantedAuthority("admin.report.review"))
			),
			new MockHttpServletRequest(),
			new RedirectAttributesModelMap()
		);
		return new Outcome(view, response.getStatus());
	}

	private void awaitLockWaiter() throws InterruptedException {
		Instant deadline = Instant.now().plus(WAIT);
		while (Instant.now().isBefore(deadline)) {
			Integer waiting = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
				Integer.class
			);
			if (waiting != null && waiting > 0) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("늦은 요청이 " + WAIT + " 안에 행 잠금 대기에 들어가지 않았다");
	}

	private record Outcome(String view, int status) {
	}

	@Configuration
	@EnableTransactionManagement
	static class TransactionalControllerConfig {

		@Bean(destroyMethod = "close")
		HikariDataSource dataSource() {
			var dataSource = new HikariDataSource();
			dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
			dataSource.setUsername(POSTGRES.getUsername());
			dataSource.setPassword(POSTGRES.getPassword());
			dataSource.setMaximumPoolSize(4);
			return dataSource;
		}

		@Bean
		PlatformTransactionManager transactionManager(DataSource dataSource) {
			return new DataSourceTransactionManager(dataSource);
		}

		@Bean
		JdbcFacilityOperationalStatusRepository facilityOperationalStatusRepository(
			DataSource dataSource,
			PlatformTransactionManager transactionManager
		) {
			return new JdbcFacilityOperationalStatusRepository(dataSource, transactionManager);
		}

		@Bean
		AdminAuditEventRepository jdbcAdminAuditEventRepository(DataSource dataSource) {
			return new JdbcAdminAuditEventRepository(dataSource);
		}

		@Bean
		FacilityOperationalStatusAdminService facilityOperationalStatusAdminService(
			JdbcFacilityOperationalStatusRepository store
		) {
			return new FacilityOperationalStatusAdminService(
				store,
				() -> Optional.of(List.of(new BundleElevatorFacility(EXIT_1, "가역 엘리베이터 1번 출입구")))
			);
		}

		@Bean
		FacilityOperationalStatusAdminPageController controller(
			FacilityOperationalStatusAdminService adminService,
			AdminAuditEventRepository auditRepository
		) {
			return new FacilityOperationalStatusAdminPageController(
				adminService,
				new AdminAuditWriter(new HoldingAuditRepository(auditRepository))
			);
		}
	}

	/** 앞선 요청의 성공 감사 기록 직전에 트랜잭션을 붙잡아 두어, 상태 행 INSERT가 커밋되지 않은 채 경합을 만든다. */
	private static final class HoldingAuditRepository implements AdminAuditEventRepository {

		static volatile boolean holdSuccessAudit;
		static volatile CountDownLatch holding = new CountDownLatch(1);
		static volatile CountDownLatch release = new CountDownLatch(1);

		private final AdminAuditEventRepository delegate;

		HoldingAuditRepository(AdminAuditEventRepository delegate) {
			this.delegate = delegate;
		}

		static void reset() {
			holdSuccessAudit = false;
			holding = new CountDownLatch(1);
			release = new CountDownLatch(1);
		}

		@Override
		public void save(AdminAuditEvent event) {
			if (holdSuccessAudit && event.outcome() == AdminAuditOutcome.SUCCESS) {
				holding.countDown();
				try {
					if (!release.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
						throw new AssertionError("held audit was not released");
					}
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new AssertionError(exception);
				}
			}
			delegate.save(event);
		}

		@Override
		public List<AdminAuditEvent> findRecent(AdminAuditEventType eventType, int limit) {
			return delegate.findRecent(eventType, limit);
		}

		@Override
		public List<AdminAuditEvent> search(AdminAuditQuery query) {
			return delegate.search(query);
		}

		@Override
		public long count(AdminAuditQuery query) {
			return delegate.count(query);
		}

		@Override
		public List<AdminAuditEvent> findForExport(AdminAuditQuery query, int limit) {
			return delegate.findForExport(query, limit);
		}

		@Override
		public List<String> findDistinctActors(AdminAuditEventType scopeEventType) {
			return delegate.findDistinctActors(scopeEventType);
		}

		@Override
		public Optional<AdminAuditEvent> findById(long id, AdminAuditEventType scopeEventType, boolean excludePrivacyRead) {
			return delegate.findById(id, scopeEventType, excludePrivacyRead);
		}

		@Override
		public AdminAuditActorContext findActorContext(
			AdminAuditEvent pivot,
			AdminAuditEventType scopeEventType,
			boolean excludePrivacyRead,
			int radius
		) {
			return delegate.findActorContext(pivot, scopeEventType, excludePrivacyRead, radius);
		}
	}
}
