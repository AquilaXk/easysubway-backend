package com.easysubway.route.adapter.out.persistence;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * timetable snapshot의 시간 기반 freshness를 런타임에 재평가해 degraded 운용을 관측 가능하게 노출한다.
 *
 * <p>만료 판정은 부팅 한 번이 아니라 주기적으로(그리고 요청 시점의 {@link JdbcRouteTimetableRepository})
 * 이뤄진다. 만료되면 이 health indicator가 집계 status 문자열을 {@code "STALE"}로 바꾼다(status order상
 * {@code up}보다 상위) — 단 HTTP status는 계속 200이고, 이 컴포넌트는 readiness/liveness group에 포함되지
 * 않으므로 admin·상태·복구 엔드포인트와 배포 probe(HTTP 상태만 보는 readiness probe)는 영향받지 않는다.
 * 저장소 내에는 root `/actuator/health` status 문자열을 {@code "UP"}과 동등 비교하는 소비처가 없음을
 * 확인했다(2026-07-20 실측: infra prometheus/probe는 `/actuator/health/readiness`만 HTTP status로 확인하고,
 * admin의 {@code HealthCheckService}는 이 actuator 레지스트리와 무관한 별도 hand-rolled 상태다). 저장소 밖
 * alerting·대시보드에서 root status 문자열 동등 비교를 하는지는 별도 확인이 필요하다.
 */
@Component
@Profile("prod | staging | release | prod-like")
class TimetableFreshnessMonitor implements HealthIndicator {

	static final Status STALE = new Status("STALE");
	private static final Logger log = LoggerFactory.getLogger(TimetableFreshnessMonitor.class);

	private final JdbcTemplate jdbcTemplate;
	private final Clock clock;
	private final AtomicReference<Freshness> state = new AtomicReference<>(Freshness.unknown());

	@Autowired
	TimetableFreshnessMonitor(DataSource dataSource, MeterRegistry meterRegistry) {
		this(new JdbcTemplate(dataSource), Clock.systemUTC(), meterRegistry);
	}

	TimetableFreshnessMonitor(JdbcTemplate jdbcTemplate, Clock clock, MeterRegistry meterRegistry) {
		this.jdbcTemplate = jdbcTemplate;
		this.clock = clock;
		Gauge.builder("easysubway.timetable.snapshot.fresh", state, current -> current.get().fresh() ? 1.0 : 0.0)
			.description("Active timetable snapshot freshness: 1 when fresh, 0 when stale, absent, or unknown")
			.register(meterRegistry);
	}

	// 스케줄 첫 실행(fixedDelay) 전 창에서 fresh 스냅샷도 UNKNOWN/0으로 오표시되지 않도록 기동 직후 1회 즉시 평가한다.
	// ApplicationReadyEvent는 ApplicationRunner(TimetableSeedLoader 포함)까지 끝난 뒤 발생해 활성화 순서가 보장된다.
	@EventListener(ApplicationReadyEvent.class)
	void evaluateOnStartup() {
		evaluate();
	}

	@Scheduled(fixedDelayString = "${easysubway.timetable.freshness-check-interval-ms:60000}")
	void evaluate() {
		Freshness previous = state.get();
		Freshness observed = query();
		if (observed == null) {
			// transient 쿼리 오류: 실제 FRESH/STALE 관측이 있었다면 그 신호를 유지해 오탐(false alerting)과
			// 복구 로그 누락을 막는다. 유효 관측이 아직 없을 때만 ERROR로 표시해 "쿼리 실패"와
			// "활성 스냅샷 없음"을 구분한다.
			if (previous.state() == State.FRESH || previous.state() == State.STALE) {
				return;
			}
			state.set(Freshness.error());
			return;
		}
		state.set(observed);
		logTransition(previous, observed);
	}

	@Override
	public Health health() {
		Freshness current = state.get();
		return switch (current.state()) {
			case FRESH -> Health.up()
				.withDetail("state", "FRESH")
				.withDetail("snapshotId", current.snapshotId())
				.withDetail("freshUntil", String.valueOf(current.freshUntil()))
				.build();
			case STALE -> Health.status(STALE)
				.withDetail("state", "STALE")
				.withDetail("snapshotId", current.snapshotId())
				.withDetail("freshUntil", String.valueOf(current.freshUntil()))
				.withDetail("reason", "route search serves 503 until a fresh snapshot is admitted")
				.build();
			case NO_ACTIVE_SNAPSHOT -> Health.unknown()
				.withDetail("state", "NO_ACTIVE_SNAPSHOT")
				.build();
			case ERROR -> Health.unknown()
				.withDetail("state", "EVALUATION_ERROR")
				.withDetail("reason", "freshness query failed; see application logs")
				.build();
		};
	}

	/** 쿼리 성공 시 관측 결과를, 실패 시 {@code null}을 반환한다(호출부가 이전 상태 유지 여부를 결정한다). */
	private Freshness query() {
		try {
			return jdbcTemplate.query(
				"""
					SELECT h.snapshot_id, h.fresh_until
					FROM timetable_snapshot_active a
					JOIN timetable_snapshot_history h ON h.snapshot_sha256 = a.snapshot_sha256
					WHERE a.singleton_id = 1
					""",
				(resultSet, rowNumber) -> Freshness.of(
					resultSet.getString("snapshot_id"),
					resultSet.getString("fresh_until"),
					clock
				)
			).stream().findFirst().orElseGet(Freshness::noActiveSnapshot);
		} catch (RuntimeException exception) {
			log.warn("timetable freshness evaluation failed: {}", exception.getMessage(), exception);
			return null;
		}
	}

	private void logTransition(Freshness previous, Freshness current) {
		if (previous.state() == State.FRESH && current.state() == State.STALE) {
			log.warn(
				"transit timetable snapshot became stale at {}; route search now serves 503 (degraded) until refresh",
				current.freshUntil()
			);
		} else if (previous.state() == State.STALE && current.state() == State.FRESH) {
			log.info(
				"transit timetable snapshot refreshed (fresh until {}); route search restored",
				current.freshUntil()
			);
		}
	}

	private enum State {
		FRESH,
		STALE,
		NO_ACTIVE_SNAPSHOT,
		ERROR
	}

	private record Freshness(State state, String snapshotId, OffsetDateTime freshUntil) {

		private static Freshness of(String snapshotId, String freshUntil, Clock clock) {
			Optional<OffsetDateTime> parsed = parse(freshUntil);
			if (parsed.isEmpty()) {
				return new Freshness(State.STALE, snapshotId, null);
			}
			OffsetDateTime value = parsed.get();
			State state = value.toInstant().isAfter(clock.instant()) ? State.FRESH : State.STALE;
			return new Freshness(state, snapshotId, value);
		}

		private static Freshness noActiveSnapshot() {
			return new Freshness(State.NO_ACTIVE_SNAPSHOT, null, null);
		}

		private static Freshness unknown() {
			return new Freshness(State.NO_ACTIVE_SNAPSHOT, null, null);
		}

		private static Freshness error() {
			return new Freshness(State.ERROR, null, null);
		}

		private static Optional<OffsetDateTime> parse(String value) {
			if (value == null || value.isBlank()) {
				return Optional.empty();
			}
			try {
				return Optional.of(OffsetDateTime.parse(value));
			} catch (DateTimeParseException exception) {
				return Optional.empty();
			}
		}

		private boolean fresh() {
			return state == State.FRESH;
		}
	}
}
