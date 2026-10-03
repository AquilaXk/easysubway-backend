package com.easysubway.journey.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
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

/** #476: 시간표 신선도 지표와 health는 활성 서버 경로 번들의 freshUntil을 따른다. */
class TimetableFreshnessMonitorTest {

	// seq126 번들의 실제 신선도 경계(2026-10-10T09:05:31.571+09:00).
	private static final Instant FRESH_UNTIL = Instant.parse("2026-10-10T00:05:31.571Z");
	private static final Instant ONE_DAY_BEFORE = FRESH_UNTIL.minusSeconds(86_400);
	private static final Instant AFTER = FRESH_UNTIL.plusSeconds(3_600);
	private static final String MANIFEST_SHA = "a".repeat(64);
	private static final String GAUGE = "easysubway.timetable.snapshot.fresh";
	private static final String REMAINING_SECONDS_GAUGE = "easysubway.timetable.snapshot.remaining.seconds";

	private CapturingAppender logAppender;
	private org.apache.logging.log4j.core.Logger monitorLogger;

	@BeforeEach
	void setUp() {
		logAppender = new CapturingAppender();
		logAppender.start();
		monitorLogger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(TimetableFreshnessMonitor.class);
		// 순수 단위 테스트에는 Spring Boot의 logging.level 바인딩이 적용되지 않으므로 전환 로그를 확실히 포착하도록 낮춘다.
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
	void reportsTheActiveBundleFreshUntilAsRemainingSeconds() {
		var clock = new MutableClock(ONE_DAY_BEFORE);
		MeterRegistry meterRegistry = new SimpleMeterRegistry();
		var monitor = new TimetableFreshnessMonitor(activeRegistry(clock), clock, meterRegistry);

		assertThat(meterRegistry.get(REMAINING_SECONDS_GAUGE).gauge().value()).isEqualTo(86_400.0);
		assertThat(meterRegistry.get(GAUGE).gauge().value()).isEqualTo(1.0);
		assertThat(monitor.health().getStatus()).isEqualTo(Status.UP);
		assertThat(monitor.health().getDetails())
			.containsEntry("state", "FRESH")
			.containsEntry("bundleId", "nationwide-route-bundle-1")
			.containsEntry("releaseSequence", 126L)
			.containsEntry("freshUntil", "2026-10-10T09:05:31.571+09:00");
	}

	@Test
	void expiredActiveBundleIsStaleWithNegativeRemainingSeconds() {
		var clock = new MutableClock(ONE_DAY_BEFORE);
		var registry = activeRegistry(clock);
		MeterRegistry meterRegistry = new SimpleMeterRegistry();
		var monitor = new TimetableFreshnessMonitor(registry, clock, meterRegistry);

		clock.set(AFTER);

		// 서빙은 만료 번들을 쓰지 않지만(503), 지표는 만료 시각을 계속 보여 줘 T-6h critical 경보가 이어진다.
		assertThat(meterRegistry.get(REMAINING_SECONDS_GAUGE).gauge().value()).isEqualTo(-3_600.0);
		assertThat(meterRegistry.get(GAUGE).gauge().value()).isEqualTo(0.0);
		assertThat(monitor.health().getStatus()).isEqualTo(TimetableFreshnessMonitor.STALE);
		assertThat(monitor.health().getDetails())
			.containsEntry("state", "STALE")
			.containsEntry("reason", "journey and station timetable search serve 503 until a fresh route bundle is activated");
	}

	@Test
	void reportsNoActiveSnapshotWhenTheRegistryHasNoActiveBundle() {
		var clock = new MutableClock(ONE_DAY_BEFORE);
		PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
		var monitor = new TimetableFreshnessMonitor(new RouteBundleActivationRegistry(clock), clock, meterRegistry);

		assertThat(monitor.health().getStatus()).isEqualTo(Status.UNKNOWN);
		assertThat(monitor.health().getDetails()).containsEntry("state", "NO_ACTIVE_SNAPSHOT");
		assertThat(meterRegistry.get(GAUGE).gauge().value()).isEqualTo(0.0);
		// 활성 번들이 없으면 잘못된 만료 경보를 막기 위해 NaN을 내보낸다.
		assertThat(meterRegistry.get(REMAINING_SECONDS_GAUGE).gauge().value()).isNaN();
		assertThat(meterRegistry.scrape()).contains("easysubway_timetable_snapshot_remaining_seconds NaN");
	}

	@Test
	void stagedButNotActivatedBundleIsNotReportedAsActive() {
		var clock = new MutableClock(ONE_DAY_BEFORE);
		var registry = new RouteBundleActivationRegistry(clock);
		registry.stage(candidate(), 0);
		MeterRegistry meterRegistry = new SimpleMeterRegistry();
		var monitor = new TimetableFreshnessMonitor(registry, clock, meterRegistry);

		assertThat(monitor.health().getDetails()).containsEntry("state", "NO_ACTIVE_SNAPSHOT");
		assertThat(meterRegistry.get(REMAINING_SECONDS_GAUGE).gauge().value()).isNaN();
	}

	@Test
	void remainingSecondsGaugeRendersTheAlertedPrometheusName() {
		var clock = new MutableClock(ONE_DAY_BEFORE);
		PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
		new TimetableFreshnessMonitor(activeRegistry(clock), clock, meterRegistry);

		// platform alerts.yml이 참조하는 시계열 이름과 정확히 일치해야 한다(단위 suffix 미부착).
		assertThat(meterRegistry.scrape()).contains("easysubway_timetable_snapshot_remaining_seconds 86400.0");
	}

	@Test
	void transitionFromFreshToStaleLogsWarnAndRecoveryLogsInfo() {
		var clock = new MutableClock(ONE_DAY_BEFORE);
		var monitor = new TimetableFreshnessMonitor(activeRegistry(clock), clock, new SimpleMeterRegistry());

		monitor.evaluate();
		clock.set(AFTER);
		monitor.evaluate();
		assertThat(logAppender.events()).anySatisfy(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.WARN);
			assertThat(event.getMessage().getFormattedMessage()).contains("became stale");
		});

		clock.set(ONE_DAY_BEFORE);
		monitor.evaluate();
		assertThat(logAppender.events()).anySatisfy(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.INFO);
			assertThat(event.getMessage().getFormattedMessage()).contains("refreshed");
		});
	}

	private static RouteBundleActivationRegistry activeRegistry(Clock clock) {
		var registry = new RouteBundleActivationRegistry(clock);
		registry.stage(candidate(), 0);
		registry.activate(MANIFEST_SHA, 0);
		return registry;
	}

	private static VerifiedRouteBundleCandidate candidate() {
		return new VerifiedRouteBundleCandidate(
			new RouteBundleIdentity(
				1,
				"server-route-bundle",
				"nationwide-route-bundle-1",
				126,
				"0".repeat(64),
				"1".repeat(64),
				"2".repeat(64),
				"b".repeat(64),
				"c".repeat(64),
				"3".repeat(64),
				"4".repeat(64),
				"5".repeat(64),
				"Asia/Seoul",
				"2026-10-03T14:27:51.411+09:00",
				"2026-10-10T09:05:31.571+09:00",
				new RouteBundleIdentity.SchemaCompatibility(3, 3),
				"launch-key",
				new RouteBundleIdentity.Signature("rsa-sha256-server-route-bundle-v1", "AQID")),
			new RouteBundleAdmissionEvidence(
				MANIFEST_SHA, "final-evidence", "promotion-evidence", "publication-receipt", "activation-request"),
			RouteBundleServingEvidence.unobservable(),
			new RouteBundleRuntimeView() {
			},
			ONE_DAY_BEFORE.minusSeconds(1));
	}

	/** 번들을 다시 활성화하지 않고 "현재 시각"만 옮겨 만료·복구를 검증한다. */
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
