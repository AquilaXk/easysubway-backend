package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.bundle.TransitionFacilityRequirements;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FacilityStatusOverlayProviderTest {

	private static final Instant T0 = Instant.parse("2026-06-30T12:00:00Z");

	private static class MutableClock extends Clock {
		private Instant now;

		MutableClock(Instant initial) {
			this.now = initial;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	@Test
	@DisplayName("초기 갱신 시 캐시 및 observedAt 정상 기록되고 Micrometer 지표 반영")
	void initialRefreshPopulatesCacheAndMetrics() {
		var clock = new MutableClock(T0);
		var meterRegistry = new SimpleMeterRegistry();
		var requirements = TransitionFacilityRequirements.of(Map.of(
			"t1", List.of(Set.of("fac-1")),
			"t2", List.of(Set.of("fac-2"))
		));
		var statuses = Map.of(
			"fac-1", AccessibilityFacilityStatus.BROKEN,
			"fac-2", AccessibilityFacilityStatus.NORMAL
		);

		var provider = new FacilityStatusOverlayProvider(
			() -> statuses,
			() -> requirements,
			clock,
			meterRegistry
		);

		provider.refresh();

		var view = provider.currentView();
		assertThat(view.available()).isTrue();
		assertThat(view.observedAt()).isEqualTo(T0);
		assertThat(view.blockedPathwayEdgeIds()).containsExactly("t1");

		// Check metrics
		var blockedGauge = meterRegistry.find("easysubway.journey.facility-status.blocked-transitions").gauge();
		assertThat(blockedGauge).isNotNull();
		assertThat(blockedGauge.value()).isEqualTo(1.0);

		var secondsGauge = meterRegistry.find("easysubway.journey.facility-status.seconds-since-last-update").gauge();
		assertThat(secondsGauge).isNotNull();
		assertThat(secondsGauge.value()).isEqualTo(0.0);

		// Advance clock 30 seconds
		clock.advance(Duration.ofSeconds(30));
		assertThat(secondsGauge.value()).isEqualTo(30.0);
	}

	@Test
	@DisplayName("조회 실패 시 observedAt은 마지막 성공 시각을 유지하고 갱신되지 않음 (5분 후 만료 유도)")
	void lookupFailureRetainsPreviousObservedAt() {
		var clock = new MutableClock(T0);
		var meterRegistry = new SimpleMeterRegistry();
		var requirements = TransitionFacilityRequirements.of(Map.of(
			"t1", List.of(Set.of("fac-1"))
		));
		var statuses = new AtomicReference<Map<String, AccessibilityFacilityStatus>>(Map.of(
			"fac-1", AccessibilityFacilityStatus.BROKEN
		));
		var shouldFail = new AtomicBoolean(false);

		var provider = new FacilityStatusOverlayProvider(
			() -> {
				if (shouldFail.get()) {
					throw new RuntimeException("DB connection error");
				}
				return statuses.get();
			},
			() -> requirements,
			clock,
			meterRegistry
		);

		// 1. Initial success at T0
		provider.refresh();
		assertThat(provider.currentView().observedAt()).isEqualTo(T0);

		// 2. Advance 2 minutes, then refresh fails
		clock.advance(Duration.ofMinutes(2));
		shouldFail.set(true);
		provider.refresh();

		// observedAt MUST remain T0 (the last successful update), NOT T0 + 2min
		assertThat(provider.currentView().observedAt()).isEqualTo(T0);

		// 3. Advance to T0 + 5m1s (total 5m 1s from last success)
		clock.advance(Duration.ofMinutes(3).plusSeconds(1));
		provider.refresh();

		// Still T0
		assertThat(provider.currentView().observedAt()).isEqualTo(T0);
		// Freshness check: T0 is older than (now - 5 min), so now - observedAt > 5 min
		assertThat(provider.currentView().observedAt().isBefore(clock.instant().minus(Duration.ofMinutes(5)))).isTrue();
	}

	@Test
	@DisplayName("1분 캐시 갱신 주기 경계에서 새로운 시설 상태가 정상 반영됨")
	void refreshIntervalBoundaryUpdatesStatuses() {
		var clock = new MutableClock(T0);
		var meterRegistry = new SimpleMeterRegistry();
		var requirements = TransitionFacilityRequirements.of(Map.of(
			"t1", List.of(Set.of("fac-1"))
		));
		var currentStatuses = new HashMap<String, AccessibilityFacilityStatus>();
		currentStatuses.put("fac-1", AccessibilityFacilityStatus.BROKEN);

		var provider = new FacilityStatusOverlayProvider(
			() -> Map.copyOf(currentStatuses),
			() -> requirements,
			clock,
			meterRegistry
		);

		provider.refresh();
		assertThat(provider.currentView().blockedPathwayEdgeIds()).containsExactly("t1");

		// Advance 1 minute, facility repaired to NORMAL
		clock.advance(Duration.ofMinutes(1));
		currentStatuses.put("fac-1", AccessibilityFacilityStatus.NORMAL);
		provider.refresh();

		var view = provider.currentView();
		assertThat(view.observedAt()).isEqualTo(T0.plus(Duration.ofMinutes(1)));
		assertThat(view.blockedPathwayEdgeIds()).isEmpty();
		assertThat(meterRegistry.find("easysubway.journey.facility-status.blocked-transitions").gauge().value()).isEqualTo(0.0);
	}

	@Test
	@DisplayName("원천 수집 심장박동이 5분을 초과해 지연된 경우, DB 갱신 시각 대신 오래된 원천 수집 시각을 써 만료를 유도한다 (#419)")
	void heartbeatOlderThanFiveMinutesForcesExpirationEvenIfDbRefreshed() {
		var clock = new MutableClock(T0);
		var meterRegistry = new SimpleMeterRegistry();
		var requirements = TransitionFacilityRequirements.of(Map.of(
			"t1", List.of(Set.of("fac-1"))
		));
		var statuses = Map.of("fac-1", AccessibilityFacilityStatus.NORMAL);

		// Source collection succeeded 6 minutes ago
		Instant sourceHeartbeat = T0.minus(Duration.ofMinutes(6));
		var heartbeatPort = (com.easysubway.transit.application.port.out.SourceCollectionHeartbeatPort) () -> sourceHeartbeat;

		var provider = new FacilityStatusOverlayProvider(
			() -> statuses,
			() -> requirements,
			heartbeatPort,
			clock,
			meterRegistry
		);

		provider.refresh();

		var view = provider.currentView();
		assertThat(view.available()).isTrue();
		// ObservedAt must reflect the older source collection timestamp (T0 - 6 min), NOT DB refresh time T0!
		assertThat(view.observedAt()).isEqualTo(sourceHeartbeat);
		// Freshness check against now: older than 5 minutes!
		assertThat(view.observedAt().isBefore(clock.instant().minus(Duration.ofMinutes(5)))).isTrue();
	}

	@Test
	@DisplayName("원천 수집이 한 번도 성공하지 않은 경우 observedAt이 비어있어 미가용으로 판정 (#419)")
	void uninitializedHeartbeatRendersFacilityStatusUnavailable() {
		var clock = new MutableClock(T0);
		var meterRegistry = new SimpleMeterRegistry();
		var requirements = TransitionFacilityRequirements.of(Map.of(
			"t1", List.of(Set.of("fac-1"))
		));
		var statuses = Map.of("fac-1", AccessibilityFacilityStatus.NORMAL);

		// Source collection never succeeded (heartbeat returns null)
		var heartbeatPort = (com.easysubway.transit.application.port.out.SourceCollectionHeartbeatPort) () -> null;

		var provider = new FacilityStatusOverlayProvider(
			() -> statuses,
			() -> requirements,
			heartbeatPort,
			clock,
			meterRegistry
		);

		provider.refresh();

		var view = provider.currentView();
		// Must not be available or observedAt must be null so Raptor rejects step-free queries
		assertThat(view.available() && view.observedAt() != null).isFalse();
	}

	@Test
	@DisplayName("초기 갱신 전 메트릭 및 포트 기반 생성자 동작 검증")
	void verifiesMetricsBeforeRefreshAndPortBasedConstructor() {
		var clock = new MutableClock(T0);
		var meterRegistry = new SimpleMeterRegistry();
		var provider = new FacilityStatusOverlayProvider(
			() -> Map.of(),
			null,
			clock,
			meterRegistry
		);

		assertThat(provider.lastSuccessfulUpdate()).isNull();
		var secondsGauge = meterRegistry.find("easysubway.journey.facility-status.seconds-since-last-update").gauge();
		assertThat(secondsGauge).isNotNull();
		assertThat(Double.isNaN(secondsGauge.value())).isTrue();

		var portProvider = new FacilityStatusOverlayProvider(
			(com.easysubway.transit.application.port.out.LoadTransitMasterPort) null,
			(com.easysubway.journey.bundle.RouteBundleActivationRegistry) null,
			clock,
			new SimpleMeterRegistry()
		);
		portProvider.refresh();
		assertThat(portProvider.currentView().available()).isTrue();
	}
}

