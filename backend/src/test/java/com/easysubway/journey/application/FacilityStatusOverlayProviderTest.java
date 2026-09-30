package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.easysubway.journey.bundle.ActiveRouteBundleSnapshot;
import com.easysubway.journey.bundle.RouteBundleActivationRegistry;
import com.easysubway.journey.bundle.RouteBundleRuntimeView;
import com.easysubway.journey.bundle.TransitionFacilityRequirementSource;
import com.easysubway.journey.bundle.TransitionFacilityRequirements;
import com.easysubway.journey.bundle.TransitionFacilityRequirements.Requirement;
import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore;
import com.easysubway.transit.domain.FacilityOperationalState;
import com.easysubway.transit.domain.FacilityOperationalStatus;
import com.easysubway.transit.domain.FacilityStatusSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

@DisplayName("시설 고장 목록 공급자")
class FacilityStatusOverlayProviderTest {

	private static final Instant T0 = Instant.parse("2026-09-30T01:00:00Z");
	private static final String E1 = "smrt-elev:0201:2:9번 출입구";
	private static final String E2 = "smrt-elev:0201:2:10번 출입구";
	private static final String D1 = "smrt-elev:0201:2:가역 방면1-1";
	private static final String EXIT_B = "exit-b";
	private static final String ENTRY_B = "entry-b";
	private static final TransitionFacilityRequirements REQUIREMENTS = TransitionFacilityRequirements.of(List.of(
		new Requirement(EXIT_B, "path-1", "station-a", TransitionFacilityRequirements.EXIT_ELEVATORS, E1),
		new Requirement(EXIT_B, "path-1", "station-a", TransitionFacilityRequirements.PLATFORM_DIRECTION_ELEVATORS, D1),
		new Requirement(ENTRY_B, "path-1", "station-a", TransitionFacilityRequirements.EXIT_ELEVATORS, E1),
		new Requirement(ENTRY_B, "path-1", "station-a", TransitionFacilityRequirements.PLATFORM_DIRECTION_ELEVATORS, D1),
		new Requirement(ENTRY_B, "path-2", "station-a", TransitionFacilityRequirements.EXIT_ELEVATORS, E2),
		new Requirement(ENTRY_B, "path-2", "station-a", TransitionFacilityRequirements.PLATFORM_DIRECTION_ELEVATORS, D1)));

	private final MutableClock clock = new MutableClock(T0);
	private final InMemoryStatusStore store = new InMemoryStatusStore();
	private final AtomicReference<TransitionFacilityRequirements> activeRequirements = new AtomicReference<>(REQUIREMENTS);
	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
	private final FacilityStatusOverlayProvider provider =
		new FacilityStatusOverlayProvider(store, activeRequirements::get, clock, meters);

	@Test
	@DisplayName("한 번도 갱신하지 못했으면 뷰는 사용할 수 없다")
	void viewIsUnavailableBeforeFirstSuccessfulRefresh() {
		assertThat(provider.currentView().available()).isFalse();
	}

	@Test
	@DisplayName("원천 수집이 한 번도 성공하지 않았으면(심장박동 없음) 상태 행이 있어도 뷰는 사용할 수 없다")
	void viewIsUnavailableWithoutFeedHeartbeat() {
		store.rows.add(status(E1, FacilityOperationalState.OUT_OF_SERVICE, FacilityStatusSource.ADMIN_VERIFIED));

		provider.refresh();

		assertThat(provider.currentView().available()).isFalse();
	}

	@Test
	@DisplayName("불가 시설로 통과하지 못하는 전환만 막고, 관리자 확인 행도 상태 그대로 센다")
	void blocksTransitionsFromOutOfServiceRowsOfEverySource() {
		store.heartbeat = T0;
		store.rows.add(status(E1, FacilityOperationalState.OUT_OF_SERVICE, FacilityStatusSource.SEOUL_METRO_FEED));
		store.rows.add(status(D1, FacilityOperationalState.OPERATING, FacilityStatusSource.SEOUL_METRO_FEED));

		provider.refresh();

		// E1 불가: exit-b는 유일한 경로가 빠져 막히고, entry-b는 path-2(E2)로 통과한다.
		assertThat(provider.currentView().available()).isTrue();
		assertThat(provider.currentView().blockedPathwayEdgeIds()).containsExactly(EXIT_B);

		store.rows.clear();
		store.rows.add(status(E1, FacilityOperationalState.OPERATING, FacilityStatusSource.SEOUL_METRO_FEED));
		store.rows.add(status(D1, FacilityOperationalState.OUT_OF_SERVICE, FacilityStatusSource.ADMIN_VERIFIED));
		provider.refresh();

		assertThat(provider.currentView().blockedPathwayEdgeIds()).containsExactlyInAnyOrder(EXIT_B, ENTRY_B);
	}

	@Test
	@DisplayName("관측 시각은 원천 심장박동과 캐시 갱신 성공 시각 중 더 오래된 쪽이다")
	void observedAtIsTheOlderOfHeartbeatAndRefreshSuccess() {
		store.heartbeat = T0.minusSeconds(200);
		provider.refresh();
		assertThat(provider.currentView().observedAt()).isEqualTo(T0.minusSeconds(200));

		store.heartbeat = T0.plusSeconds(20);
		provider.refresh();
		assertThat(provider.currentView().observedAt()).isEqualTo(T0);
	}

	@Test
	@DisplayName("심장박동을 상태보다 먼저 읽어, 그 사이 새 수집이 커밋돼도 관측 시각이 읽은 상태보다 새로워지지 않는다")
	void readsHeartbeatBeforeStatuses() {
		store.heartbeat = T0.minusSeconds(60);
		store.onLoadStatuses = () -> store.heartbeat = T0.minusSeconds(1);

		provider.refresh();

		assertThat(provider.currentView().observedAt()).isEqualTo(T0.minusSeconds(60));
	}

	@Test
	@DisplayName("조회가 실패하면 이전 목록의 관측 시각을 앞으로 옮기지 않아 5분 뒤 신선도 판단에서 드러난다")
	void failedRefreshKeepsTheLastSuccessfulObservationTime() {
		store.heartbeat = T0;
		store.rows.add(status(E1, FacilityOperationalState.OUT_OF_SERVICE, FacilityStatusSource.SEOUL_METRO_FEED));
		provider.refresh();

		store.failure = new IllegalStateException("database down");
		clock.advance(Duration.ofMinutes(4));
		provider.refresh();

		assertThat(provider.currentView().observedAt()).isEqualTo(T0);
		assertThat(provider.currentView().blockedPathwayEdgeIds()).containsExactly(EXIT_B);
		assertThat(gauge("easysubway.journey.facility_status.seconds_since_last_success")).isEqualTo(240.0);
	}

	@Test
	@DisplayName("캐시 갱신에 성공해도 원천 심장박동이 멈추면 관측 시각은 심장박동에 머문다")
	void successfulCacheRefreshDoesNotHideStalledFeed() {
		store.heartbeat = T0;
		provider.refresh();

		clock.advance(Duration.ofMinutes(6));
		provider.refresh();

		assertThat(provider.currentView().observedAt()).isEqualTo(T0);
		assertThat(gauge("easysubway.journey.facility_status.seconds_since_last_success")).isEqualTo(360.0);
	}

	@Test
	@DisplayName("활성 번들에 전환-시설 매핑이 없으면 신선한 상태여도 뷰는 사용할 수 없다")
	void viewIsUnavailableWhenActiveBundleHasNoMapping() {
		store.heartbeat = T0;
		provider.refresh();
		activeRequirements.set(TransitionFacilityRequirements.missing());

		assertThat(provider.currentView().available()).isFalse();
		assertThat(gauge("easysubway.journey.facility_status.blocked_transitions")).isNaN();
	}

	@Test
	@DisplayName("한 요청이 캡처한 뷰는 이후 갱신에도 바뀌지 않고, 번들 교체는 다음 캡처부터 반영된다")
	void capturedViewIsImmutableAndBundleSwapAppliesToNextCapture() {
		store.heartbeat = T0;
		store.rows.add(status(E1, FacilityOperationalState.OUT_OF_SERVICE, FacilityStatusSource.SEOUL_METRO_FEED));
		provider.refresh();
		FacilityAvailabilityView captured = provider.currentView();

		store.rows.clear();
		clock.advance(Duration.ofSeconds(30));
		store.heartbeat = clock.instant();
		provider.refresh();

		assertThat(captured.blockedPathwayEdgeIds()).containsExactly(EXIT_B);
		assertThat(captured.observedAt()).isEqualTo(T0);
		assertThat(provider.currentView().blockedPathwayEdgeIds()).isEmpty();

		store.rows.add(status(E1, FacilityOperationalState.OUT_OF_SERVICE, FacilityStatusSource.SEOUL_METRO_FEED));
		provider.refresh();
		activeRequirements.set(TransitionFacilityRequirements.of(List.of(
			new Requirement(ENTRY_B, "path-1", "station-a", TransitionFacilityRequirements.EXIT_ELEVATORS, E1),
			new Requirement(ENTRY_B, "path-1", "station-a", TransitionFacilityRequirements.PLATFORM_DIRECTION_ELEVATORS, D1))));
		assertThat(provider.currentView().blockedPathwayEdgeIds()).containsExactly(ENTRY_B);
	}

	@Test
	@DisplayName("같은 목록·매핑이면 계산한 뷰를 재사용하고, 목록이 같아도 활성 매핑이 바뀌면 다시 계산한다")
	void reusesEvaluationUntilStatusOrActiveMappingChanges() {
		store.heartbeat = T0;
		store.rows.add(status(E1, FacilityOperationalState.OUT_OF_SERVICE, FacilityStatusSource.SEOUL_METRO_FEED));
		provider.refresh();

		FacilityAvailabilityView first = provider.currentView();
		assertThat(provider.currentView()).isSameAs(first);

		activeRequirements.set(TransitionFacilityRequirements.of(List.of(
			new Requirement(ENTRY_B, "path-1", "station-a", TransitionFacilityRequirements.EXIT_ELEVATORS, E1),
			new Requirement(ENTRY_B, "path-1", "station-a", TransitionFacilityRequirements.PLATFORM_DIRECTION_ELEVATORS, D1))));
		FacilityAvailabilityView swapped = provider.currentView();

		assertThat(swapped).isNotSameAs(first);
		assertThat(swapped.blockedPathwayEdgeIds()).containsExactly(ENTRY_B);
		assertThat(first.blockedPathwayEdgeIds()).containsExactly(EXIT_B);
	}

	@Test
	@DisplayName("막힌 전환 수와 마지막 성공 이후 경과 초를 지표로 낸다")
	void exposesBlockedTransitionCountAndSecondsSinceLastSuccess() {
		assertThat(gauge("easysubway.journey.facility_status.blocked_transitions")).isNaN();
		assertThat(gauge("easysubway.journey.facility_status.seconds_since_last_success")).isNaN();

		store.heartbeat = T0.minusSeconds(15);
		store.rows.add(status(D1, FacilityOperationalState.OUT_OF_SERVICE, FacilityStatusSource.SEOUL_METRO_FEED));
		provider.refresh();
		clock.advance(Duration.ofSeconds(5));

		assertThat(gauge("easysubway.journey.facility_status.blocked_transitions")).isEqualTo(2.0);
		assertThat(gauge("easysubway.journey.facility_status.seconds_since_last_success")).isEqualTo(20.0);
	}

	@Test
	@DisplayName("캐시 갱신 주기는 1분 이하이며 시작 즉시 첫 갱신을 한다")
	void refreshIsScheduledAtMostEveryMinute() throws Exception {
		Scheduled scheduled = FacilityStatusOverlayProvider.class.getMethod("refresh").getAnnotation(Scheduled.class);

		assertThat(scheduled).isNotNull();
		assertThat(scheduled.fixedDelay()).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(1).toMillis());
		assertThat(scheduled.fixedDelayString()).isEmpty();
		assertThat(scheduled.initialDelay()).isLessThanOrEqualTo(0);
		assertThat(scheduled.initialDelayString()).isEmpty();
	}

	@Test
	@DisplayName("활성 번들 매핑 공급자: 레지스트리에 활성 번들이 없거나 런타임이 매핑을 모르면 매핑 없음이다")
	void activeBundleRequirementsReadsOnlyTheActiveRuntimeMapping() {
		var registry = mock(RouteBundleActivationRegistry.class);
		var active = mock(ActiveRouteBundleSnapshot.class);
		when(registry.activeSnapshot()).thenReturn(active);
		when(active.runtimeView()).thenReturn(new RequirementRuntime(REQUIREMENTS));

		assertThat(FacilityStatusOverlayProvider.activeBundleRequirements(registry).get()).isSameAs(REQUIREMENTS);

		when(active.runtimeView()).thenReturn(new RouteBundleRuntimeView() {
		});
		assertThat(FacilityStatusOverlayProvider.activeBundleRequirements(registry).get().present()).isFalse();

		var emptyRegistry = new RouteBundleActivationRegistry(Clock.systemUTC());
		assertThat(FacilityStatusOverlayProvider.activeBundleRequirements(emptyRegistry).get().present()).isFalse();
	}

	private double gauge(String name) {
		return meters.get(name).gauge().value();
	}

	private FacilityOperationalStatus status(String facilityId, FacilityOperationalState state, FacilityStatusSource source) {
		return new FacilityOperationalStatus(facilityId, state, source, "S", clock.instant(), clock.instant());
	}

	private record RequirementRuntime(TransitionFacilityRequirements transitionFacilityRequirements)
		implements RouteBundleRuntimeView, TransitionFacilityRequirementSource {
	}

	private static final class InMemoryStatusStore implements FacilityOperationalStatusStore {
		private final List<FacilityOperationalStatus> rows = new ArrayList<>();
		private Instant heartbeat;
		private RuntimeException failure;
		private Runnable onLoadStatuses = () -> {
		};

		@Override
		public List<FacilityOperationalStatus> loadStatuses() {
			if (failure != null) throw failure;
			onLoadStatuses.run();
			return List.copyOf(rows);
		}

		@Override
		public Optional<Instant> lastSuccessfulCollectionAt(String feed) {
			if (failure != null) throw failure;
			assertThat(feed).isEqualTo(SEOUL_METRO_ELEVATOR_FEED);
			return Optional.ofNullable(heartbeat);
		}

		@Override
		public FeedApplyResult applyFeedCollection(String feed, List<FeedObservation> observations, Instant observedAt) {
			throw new UnsupportedOperationException("read-only in provider tests");
		}

		@Override
		public boolean recordAdminVerified(String facilityId, FacilityOperationalState state, Instant verifiedAt) {
			throw new UnsupportedOperationException("read-only in provider tests");
		}
	}

	private static final class MutableClock extends Clock {
		private Instant now;

		private MutableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
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
			return now;
		}
	}
}
