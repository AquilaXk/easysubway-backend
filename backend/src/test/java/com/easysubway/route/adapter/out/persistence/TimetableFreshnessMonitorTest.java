package com.easysubway.route.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TimetableFreshnessMonitorTest {

	private static final Instant BEFORE = Instant.parse("2026-07-16T00:00:00Z");
	private static final Instant AFTER = Instant.parse("2026-07-21T00:00:00Z");
	private static final String FRESH_UNTIL = "2026-07-20T00:00:00+09:00";
	private static final String GAUGE = "easysubway.timetable.snapshot.fresh";
	private static final String BREAK_GLASS_GAUGE = "easysubway.timetable.snapshot.break-glass";

	private JdbcTemplate jdbc;
	private CapturingAppender logAppender;
	private org.apache.logging.log4j.core.Logger monitorLogger;

	@BeforeEach
	void setUp() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:freshness-monitor;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
		jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("DROP ALL OBJECTS");
		jdbc.execute(
			"CREATE TABLE timetable_snapshot_history (snapshot_sha256 VARCHAR(64) PRIMARY KEY, "
				+ "snapshot_id VARCHAR(120), fresh_until VARCHAR(40))");
		jdbc.execute(
			"CREATE TABLE timetable_snapshot_active (singleton_id INTEGER PRIMARY KEY, snapshot_sha256 VARCHAR(64))");
		logAppender = new CapturingAppender();
		logAppender.start();
		monitorLogger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(TimetableFreshnessMonitor.class);
		// 순수 단위 테스트에는 Spring Boot의 logging.level 바인딩이 적용되지 않으므로, ambient 기본 설정과
		// 무관하게 WARN/INFO 전환 로그를 확실히 포착하도록 로거 레벨을 명시적으로 낮춘다.
		monitorLogger.setLevel(Level.ALL);
		monitorLogger.addAppender(logAppender);
	}

	@AfterEach
	void tearDown() {
		monitorLogger.removeAppender(logAppender);
		monitorLogger.setLevel(null);
		logAppender.stop();
	}

	@Test
	void reportsFreshWhenActiveSnapshotHasNotExpired() {
		insertActiveSnapshot(FRESH_UNTIL);
		MeterRegistry meterRegistry = new SimpleMeterRegistry();
		TimetableFreshnessMonitor monitor = monitor(BEFORE, meterRegistry);

		monitor.evaluate();

		assertThat(monitor.health().getStatus()).isEqualTo(Status.UP);
		assertThat(monitor.health().getDetails()).containsEntry("state", "FRESH");
		assertThat(meterRegistry.get(GAUGE).gauge().value()).isEqualTo(1.0);
	}

	@Test
	void reportsStaleWhenActiveSnapshotExpiredWithoutRestart() {
		insertActiveSnapshot(FRESH_UNTIL);
		MeterRegistry meterRegistry = new SimpleMeterRegistry();
		TimetableFreshnessMonitor monitor = monitor(AFTER, meterRegistry);

		monitor.evaluate();

		assertThat(monitor.health().getStatus()).isEqualTo(TimetableFreshnessMonitor.STALE);
		assertThat(monitor.health().getDetails())
			.containsEntry("state", "STALE")
			.containsEntry("reason", "route search serves 503 until a fresh snapshot is admitted");
		assertThat(meterRegistry.get(GAUGE).gauge().value()).isEqualTo(0.0);
	}

	@Test
	void reportsUnknownWhenNoActiveSnapshot() {
		MeterRegistry meterRegistry = new SimpleMeterRegistry();
		TimetableFreshnessMonitor monitor = monitor(BEFORE, meterRegistry);

		monitor.evaluate();

		assertThat(monitor.health().getStatus()).isEqualTo(Status.UNKNOWN);
		assertThat(monitor.health().getDetails()).containsEntry("state", "NO_ACTIVE_SNAPSHOT");
		assertThat(meterRegistry.get(GAUGE).gauge().value()).isEqualTo(0.0);
	}

	@Test
	void blankFreshUntilIsTreatedAsStale() {
		insertActiveSnapshot("");
		MeterRegistry meterRegistry = new SimpleMeterRegistry();
		TimetableFreshnessMonitor monitor = monitor(BEFORE, meterRegistry);

		monitor.evaluate();

		assertThat(monitor.health().getStatus()).isEqualTo(TimetableFreshnessMonitor.STALE);
		assertThat(monitor.health().getDetails()).containsEntry("state", "STALE");
	}

	@Test
	void unparsableFreshUntilIsTreatedAsStale() {
		insertActiveSnapshot("not-a-timestamp");
		MeterRegistry meterRegistry = new SimpleMeterRegistry();
		TimetableFreshnessMonitor monitor = monitor(BEFORE, meterRegistry);

		monitor.evaluate();

		assertThat(monitor.health().getStatus()).isEqualTo(TimetableFreshnessMonitor.STALE);
		assertThat(monitor.health().getDetails()).containsEntry("state", "STALE");
	}

	@Test
	void queryFailurePreservesPreviousFreshStateInsteadOfReportingUnknown() {
		insertActiveSnapshot(FRESH_UNTIL);
		MeterRegistry meterRegistry = new SimpleMeterRegistry();
		TimetableFreshnessMonitor monitor = monitor(BEFORE, meterRegistry);
		monitor.evaluate();
		assertThat(monitor.health().getStatus()).isEqualTo(Status.UP);

		jdbc.execute("DROP ALL OBJECTS");
		monitor.evaluate();

		// transient 쿼리 오류가 이전 FRESH 관측을 UNKNOWN으로 덮어쓰지 않는다(false alerting 방지).
		assertThat(monitor.health().getStatus()).isEqualTo(Status.UP);
		assertThat(monitor.health().getDetails()).containsEntry("state", "FRESH");
		assertThat(meterRegistry.get(GAUGE).gauge().value()).isEqualTo(1.0);
	}

	@Test
	void queryFailurePreservesPreviousStaleStateInsteadOfReportingUnknown() {
		insertActiveSnapshot(FRESH_UNTIL);
		MeterRegistry meterRegistry = new SimpleMeterRegistry();
		TimetableFreshnessMonitor monitor = monitor(AFTER, meterRegistry);
		monitor.evaluate();
		assertThat(monitor.health().getStatus()).isEqualTo(TimetableFreshnessMonitor.STALE);

		jdbc.execute("DROP ALL OBJECTS");
		monitor.evaluate();

		// transient 쿼리 오류가 이전 STALE 신호(경로검색 degraded 관측)를 삭제하지 않는다.
		assertThat(monitor.health().getStatus()).isEqualTo(TimetableFreshnessMonitor.STALE);
		assertThat(monitor.health().getDetails()).containsEntry("state", "STALE");
	}

	@Test
	void queryFailureWithoutPriorObservationReportsDistinctErrorStateAndWarnLogs() {
		jdbc.execute("DROP ALL OBJECTS");
		MeterRegistry meterRegistry = new SimpleMeterRegistry();
		TimetableFreshnessMonitor monitor = monitor(BEFORE, meterRegistry);

		monitor.evaluate();

		// "쿼리 실패"는 "활성 스냅샷 없음"과 detail로 구분되고(둘 다 Status.UNKNOWN), gauge는 0이다.
		assertThat(monitor.health().getStatus()).isEqualTo(Status.UNKNOWN);
		assertThat(monitor.health().getDetails()).containsEntry("state", "EVALUATION_ERROR");
		assertThat(meterRegistry.get(GAUGE).gauge().value()).isEqualTo(0.0);
		assertThat(logAppender.events()).anySatisfy(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.WARN);
			assertThat(event.getMessage().getFormattedMessage()).contains("freshness evaluation failed");
		});
	}

	@Test
	void evaluateOnStartupPopulatesStateBeforeFirstScheduledRun() {
		insertActiveSnapshot(FRESH_UNTIL);
		MeterRegistry meterRegistry = new SimpleMeterRegistry();
		TimetableFreshnessMonitor monitor = monitor(BEFORE, meterRegistry);

		// 부팅 직후 첫 스케줄 실행 전에도 ApplicationReadyEvent 리스너가 즉시 1회 평가해 UNKNOWN/0으로
		// 오표시되지 않는다.
		monitor.evaluateOnStartup();

		assertThat(monitor.health().getStatus()).isEqualTo(Status.UP);
		assertThat(meterRegistry.get(GAUGE).gauge().value()).isEqualTo(1.0);
	}

	@Test
	void transitionFromFreshToStaleLogsWarnRecoveryFromStaleToFreshLogsInfo() {
		insertActiveSnapshot(FRESH_UNTIL);
		MutableClock clock = new MutableClock(BEFORE);
		MeterRegistry meterRegistry = new SimpleMeterRegistry();
		TimetableFreshnessMonitor monitor = new TimetableFreshnessMonitor(jdbc, clock, meterRegistry);

		monitor.evaluate();
		assertThat(monitor.health().getStatus()).isEqualTo(Status.UP);

		clock.set(AFTER);
		monitor.evaluate();
		assertThat(monitor.health().getStatus()).isEqualTo(TimetableFreshnessMonitor.STALE);
		assertThat(logAppender.events()).anySatisfy(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.WARN);
			assertThat(event.getMessage().getFormattedMessage()).contains("became stale");
		});

		clock.set(BEFORE);
		monitor.evaluate();
		assertThat(monitor.health().getStatus()).isEqualTo(Status.UP);
		assertThat(logAppender.events()).anySatisfy(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.INFO);
			assertThat(event.getMessage().getFormattedMessage()).contains("refreshed");
		});
	}

	@Test
	void breakGlassOverrideExposesGaugeHealthDetailAndWarnWhileRemainingStale() {
		insertActiveSnapshot(FRESH_UNTIL);
		MeterRegistry meterRegistry = new SimpleMeterRegistry();
		TimetableFreshnessMonitor monitor = breakGlassMonitor(AFTER, meterRegistry, "incident-2328 operator jdoe");

		monitor.evaluate();

		// override는 freshness 판정을 바꾸지 않는다 — 여전히 STALE로 노출해 "우회 중"이 관측되게 한다.
		assertThat(monitor.health().getStatus()).isEqualTo(TimetableFreshnessMonitor.STALE);
		assertThat(monitor.health().getDetails())
			.containsEntry("state", "STALE")
			.containsEntry("breakGlass", true)
			.containsEntry("breakGlassReason", "incident-2328 operator jdoe")
			.containsEntry("reason",
				"break-glass override active: expired snapshot is being served (integrity still verified)");
		assertThat(meterRegistry.get(BREAK_GLASS_GAUGE).gauge().value()).isEqualTo(1.0);
		assertThat(meterRegistry.get(GAUGE).gauge().value()).isEqualTo(0.0);
		assertThat(logAppender.events()).anySatisfy(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.WARN);
			assertThat(event.getMessage().getFormattedMessage())
				.contains("break-glass override active")
				.contains("serving EXPIRED");
		});
	}

	@Test
	void breakGlassStartupLogsWarnAndArmsGaugeEvenWhenSnapshotStillFresh() {
		insertActiveSnapshot(FRESH_UNTIL);
		MeterRegistry meterRegistry = new SimpleMeterRegistry();
		TimetableFreshnessMonitor monitor = breakGlassMonitor(BEFORE, meterRegistry, "incident-2328 operator jdoe");

		monitor.evaluateOnStartup();

		// snapshot이 아직 fresh여도 override가 armed임을 기동 WARN·health detail·gauge로 노출한다.
		assertThat(monitor.health().getStatus()).isEqualTo(Status.UP);
		assertThat(monitor.health().getDetails())
			.containsEntry("state", "FRESH")
			.containsEntry("breakGlass", true);
		assertThat(meterRegistry.get(BREAK_GLASS_GAUGE).gauge().value()).isEqualTo(1.0);
		assertThat(meterRegistry.get(GAUGE).gauge().value()).isEqualTo(1.0);
		assertThat(logAppender.events()).anySatisfy(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.WARN);
			assertThat(event.getMessage().getFormattedMessage()).contains("BREAK-GLASS OVERRIDE ENABLED");
		});
	}

	@Test
	void breakGlassDisabledLeavesGaugeZeroAndStandardStaleReason() {
		insertActiveSnapshot(FRESH_UNTIL);
		MeterRegistry meterRegistry = new SimpleMeterRegistry();
		TimetableFreshnessMonitor monitor = monitor(AFTER, meterRegistry);

		monitor.evaluate();

		assertThat(monitor.health().getStatus()).isEqualTo(TimetableFreshnessMonitor.STALE);
		assertThat(monitor.health().getDetails())
			.containsEntry("reason", "route search serves 503 until a fresh snapshot is admitted")
			.doesNotContainKey("breakGlass");
		assertThat(meterRegistry.get(BREAK_GLASS_GAUGE).gauge().value()).isEqualTo(0.0);
	}

	@Test
	void breakGlassReasonControlCharactersAreNormalizedToPreventLogForging() {
		insertActiveSnapshot(FRESH_UNTIL);
		MeterRegistry meterRegistry = new SimpleMeterRegistry();
		// 내부 개행·제어문자가 있는 사유값(가짜 로그 라인 삽입 시도)이 공백으로 치환되는지 확인한다.
		TimetableFreshnessMonitor monitor = breakGlassMonitor(
			AFTER, meterRegistry, "ops-jdoe\r\nWARN forged line\tinjected");

		monitor.evaluate();

		assertThat(monitor.health().getDetails())
			.extracting("breakGlassReason")
			.isEqualTo("ops-jdoe WARN forged line injected");
		assertThat(logAppender.events()).anySatisfy(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.WARN);
			assertThat(event.getMessage().getFormattedMessage())
				.doesNotContain("\r")
				.doesNotContain("\n")
				.contains("ops-jdoe WARN forged line injected");
		});
	}

	@Test
	void breakGlassBlankReasonIsNormalizedInAudit() {
		insertActiveSnapshot(FRESH_UNTIL);
		MeterRegistry meterRegistry = new SimpleMeterRegistry();
		TimetableFreshnessMonitor monitor = breakGlassMonitor(AFTER, meterRegistry, "   ");

		monitor.evaluate();

		assertThat(monitor.health().getDetails()).containsEntry("breakGlassReason", "(unspecified)");
	}

	private TimetableFreshnessMonitor monitor(Instant now, MeterRegistry meterRegistry) {
		return new TimetableFreshnessMonitor(jdbc, Clock.fixed(now, ZoneOffset.UTC), meterRegistry);
	}

	private TimetableFreshnessMonitor breakGlassMonitor(Instant now, MeterRegistry meterRegistry, String reason) {
		return new TimetableFreshnessMonitor(jdbc, Clock.fixed(now, ZoneOffset.UTC), meterRegistry, true, reason);
	}

	private void insertActiveSnapshot(String freshUntil) {
		jdbc.update(
			"INSERT INTO timetable_snapshot_history (snapshot_sha256, snapshot_id, fresh_until) VALUES (?, ?, ?)",
			"a".repeat(64), "snapshot-a", freshUntil);
		jdbc.update(
			"INSERT INTO timetable_snapshot_active (singleton_id, snapshot_sha256) VALUES (1, ?)", "a".repeat(64));
	}

	/** 테스트 안에서 스냅샷을 재활성화하지 않고 "현재 시각"만 이동시켜 전환(transition) 로그를 검증하기 위한 clock. */
	private static final class MutableClock extends Clock {
		private Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		void set(Instant instant) {
			this.instant = instant;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}

	private static final class CapturingAppender extends AbstractAppender {
		private final List<LogEvent> events = new CopyOnWriteArrayList<>();

		private CapturingAppender() {
			super("timetable-freshness-monitor-test-appender", null, null, false, Property.EMPTY_ARRAY);
		}

		@Override
		public void append(LogEvent event) {
			events.add(event.toImmutable());
		}

		List<LogEvent> events() {
			return events;
		}
	}
}
