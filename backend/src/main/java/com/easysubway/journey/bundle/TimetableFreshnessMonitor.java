package com.easysubway.journey.bundle;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 활성 서버 경로 번들의 시간 기반 신선도를 지표와 health로 노출한다(#476).
 *
 * <p>Journey 경로 탐색과 역 시간표는 모두 이 번들 한 세대에서 읽고, {@code freshUntil}이 지나면 둘 다 503으로 명시적으로
 * 실패한다. 지표 이름은 platform {@code alerts.yml}의 T-24h/T-6h 경보가 참조하는
 * {@code easysubway_timetable_snapshot_remaining_seconds}를 그대로 유지한다. 값은 scrape 시점에 활성 번들의
 * {@code freshUntil}에서 현재 시각을 뺀 초이며, 만료 뒤에는 음수가 되어 critical 경보가 이어진다. 활성 번들이 없으면
 * NaN을 내보내 잘못된 만료 경보를 막는다.</p>
 *
 * <p>STALE은 health 집계 status 문자열만 바꾸고 HTTP status는 200으로 둔다. 이 컴포넌트는 readiness/liveness group에
 * 들지 않으며, 관리자 시스템 상태 화면({@code HealthCheckService})과도 무관하다.</p>
 */
@Component
@Profile("(prod | staging | release | prod-like) & !capacity-evidence")
class TimetableFreshnessMonitor implements HealthIndicator {

	static final Status STALE = new Status("STALE");
	private static final Logger log = LoggerFactory.getLogger(TimetableFreshnessMonitor.class);

	private final RouteBundleActivationRegistry registry;
	private final Clock clock;
	private final AtomicReference<State> lastObserved = new AtomicReference<>();

	@Autowired
	TimetableFreshnessMonitor(RouteBundleActivationRegistry registry, MeterRegistry meterRegistry) {
		this(registry, Clock.systemUTC(), meterRegistry);
	}

	TimetableFreshnessMonitor(RouteBundleActivationRegistry registry, Clock clock, MeterRegistry meterRegistry) {
		this.registry = Objects.requireNonNull(registry, "registry");
		this.clock = Objects.requireNonNull(clock, "clock");
		Gauge.builder("easysubway.timetable.snapshot.fresh", this, monitor -> monitor.observe().state() == State.FRESH ? 1.0 : 0.0)
			.description("Active route bundle timetable freshness: 1 when fresh, 0 when stale or absent")
			.register(meterRegistry);
		Gauge.builder("easysubway.timetable.snapshot.remaining.seconds", this, TimetableFreshnessMonitor::remainingSeconds)
			.description("Seconds until the active route bundle freshUntil deadline (negative once expired); "
				+ "NaN when no route bundle is active")
			.register(meterRegistry);
	}

	/** 만료·복구 전환을 로그로 남긴다. 지표와 health는 호출 시점에 직접 계산하므로 이 주기와 무관하다. */
	@Scheduled(fixedDelayString = "${easysubway.timetable.freshness-check-interval-ms:60000}")
	void evaluate() {
		Observation current = observe();
		State previous = lastObserved.getAndSet(current.state());
		if (previous == State.FRESH && current.state() == State.STALE) {
			log.warn("route bundle timetable became stale at {}; journey and station timetable search now serve 503",
				current.identity().freshUntil());
		} else if (previous == State.STALE && current.state() == State.FRESH) {
			log.info("route bundle timetable refreshed (fresh until {}); journey and station timetable search restored",
				current.identity().freshUntil());
		}
	}

	@Override
	public Health health() {
		Observation current = observe();
		return switch (current.state()) {
			case FRESH -> withIdentity(Health.up(), current).build();
			case STALE -> withIdentity(Health.status(STALE), current)
				.withDetail("reason", "journey and station timetable search serve 503 until a fresh route bundle is activated")
				.build();
			case NO_ACTIVE_SNAPSHOT -> Health.unknown().withDetail("state", "NO_ACTIVE_SNAPSHOT").build();
		};
	}

	private static Health.Builder withIdentity(Health.Builder builder, Observation observation) {
		return builder
			.withDetail("state", observation.state().name())
			.withDetail("bundleId", observation.identity().bundleId())
			.withDetail("releaseSequence", observation.identity().releaseSequence())
			.withDetail("freshUntil", observation.identity().freshUntil());
	}

	private double remainingSeconds() {
		Observation current = observe();
		if (current.identity() == null) return Double.NaN;
		return (double) (current.identity().freshUntilInstant().getEpochSecond() - clock.instant().getEpochSecond());
	}

	private Observation observe() {
		return registry.activeIdentityForFreshnessObservation()
			.map(identity -> new Observation(
				clock.instant().isBefore(identity.freshUntilInstant()) ? State.FRESH : State.STALE, identity))
			.orElseGet(() -> new Observation(State.NO_ACTIVE_SNAPSHOT, null));
	}

	private enum State {
		FRESH,
		STALE,
		NO_ACTIVE_SNAPSHOT
	}

	private record Observation(State state, RouteBundleIdentity identity) {
	}
}
