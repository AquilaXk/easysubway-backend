package com.easysubway.journey.application;

import com.easysubway.journey.bundle.RouteBundleActivationException;
import com.easysubway.journey.bundle.RouteBundleActivationRegistry;
import com.easysubway.journey.bundle.TransitionFacilityRequirementSource;
import com.easysubway.journey.bundle.TransitionFacilityRequirements;
import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore;
import com.easysubway.transit.domain.FacilityOperationalState;
import com.easysubway.transit.domain.FacilityOperationalStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 운영 상태 테이블의 불가 시설 목록과 활성 번들의 전환-시설 요구 매핑으로 막힌 전환을 공급한다(#418).
 *
 * <ul>
 *   <li>불가 시설은 {@code facility_operational_status}에서 {@code OUT_OF_SERVICE}인 행이다. 출처(원천 수집·관리자 확인)와
 *       관계없이 상태 그대로 센다. 마스터 데이터 시설 상태는 읽지 않는다(QA 결정 추가 3).</li>
 *   <li>관측 시각은 원천 심장박동({@code facility_status_feed_heartbeat.last_success_at})과 캐시 갱신 성공 시각 중 더 오래된
 *       쪽이다. 심장박동을 상태보다 먼저 읽어, 그 사이 커밋된 새 수집이 읽은 상태를 실제보다 새롭게 보이게 하지 않는다.</li>
 *   <li>조회가 실패하면 이전 목록과 그 관측 시각을 그대로 둔다. 관측 시각을 앞으로 옮기지 않으므로 5분이 지나면 무단차 요청은
 *       {@code FACILITY_STATUS_UNAVAILABLE}이 된다. 심장박동이 없으면(원천 수집 미성공) 뷰는 사용할 수 없다.</li>
 *   <li>활성 번들에 매핑이 없거나 활성 번들이 없으면 뷰는 사용할 수 없다(차단 0건으로 숨기지 않는다).</li>
 * </ul>
 *
 * <p>막힌 전환은 뷰를 요청할 때 그 시점의 활성 번들 매핑으로 계산하고, 같은 목록·매핑 조합이면 다시 계산하지 않는다.
 * 반환한 뷰는 불변이라 한 요청은 한 번 캡처한 뷰로 끝까지 계산한다.</p>
 */
public final class FacilityStatusOverlayProvider implements FacilityAvailabilityPort {

	/** 캐시 갱신 간격(1분 이하, #418 완료 조건). 시작 즉시 첫 갱신을 한다. */
	static final long REFRESH_INTERVAL_MILLIS = 30_000;
	static final String BLOCKED_TRANSITIONS_METRIC = "easysubway.journey.facility_status.blocked_transitions";
	static final String SECONDS_SINCE_LAST_SUCCESS_METRIC = "easysubway.journey.facility_status.seconds_since_last_success";
	private static final Logger log = LoggerFactory.getLogger(FacilityStatusOverlayProvider.class);

	private final FacilityOperationalStatusStore store;
	private final Supplier<TransitionFacilityRequirements> activeRequirements;
	private final Clock clock;
	private volatile StatusSnapshot status;
	private volatile Evaluation evaluation;

	public FacilityStatusOverlayProvider(
		FacilityOperationalStatusStore store,
		Supplier<TransitionFacilityRequirements> activeRequirements,
		Clock clock,
		MeterRegistry meterRegistry
	) {
		this.store = Objects.requireNonNull(store, "store");
		this.activeRequirements = Objects.requireNonNull(activeRequirements, "activeRequirements");
		this.clock = Objects.requireNonNull(clock, "clock");
		Objects.requireNonNull(meterRegistry, "meterRegistry");
		Gauge.builder(BLOCKED_TRANSITIONS_METRIC, this, FacilityStatusOverlayProvider::blockedTransitionCount)
			.description("Transitions blocked for step-free journeys by out-of-service facilities; NaN while facility status is unusable")
			.register(meterRegistry);
		Gauge.builder(SECONDS_SINCE_LAST_SUCCESS_METRIC, this, FacilityStatusOverlayProvider::secondsSinceLastSuccess)
			.description("Seconds since the facility status observation time (older of feed heartbeat and cache refresh); NaN before the first success")
			.register(meterRegistry);
	}

	/** 활성 번들 런타임의 매핑을 읽는다. 활성 번들이 없거나 런타임이 매핑을 모르면 매핑 없음이다. */
	public static Supplier<TransitionFacilityRequirements> activeBundleRequirements(RouteBundleActivationRegistry registry) {
		Objects.requireNonNull(registry, "registry");
		return () -> {
			try {
				if (registry.activeSnapshot().runtimeView() instanceof TransitionFacilityRequirementSource source) {
					return source.transitionFacilityRequirements();
				}
				return TransitionFacilityRequirements.missing();
			} catch (RouteBundleActivationException exception) {
				return TransitionFacilityRequirements.missing();
			}
		};
	}

	@Scheduled(fixedDelay = REFRESH_INTERVAL_MILLIS)
	public void refresh() {
		try {
			Optional<Instant> heartbeat = store.lastSuccessfulCollectionAt(FacilityOperationalStatusStore.SEOUL_METRO_ELEVATOR_FEED);
			Set<String> outOfService = store.loadStatuses().stream()
				.filter(row -> row.status() == FacilityOperationalState.OUT_OF_SERVICE)
				.map(FacilityOperationalStatus::facilityId)
				.collect(Collectors.toUnmodifiableSet());
			Instant refreshedAt = clock.instant();
			if (heartbeat.isEmpty()) {
				status = null;
				log.warn("Facility status feed has never succeeded; step-free facility status is unavailable");
				return;
			}
			Instant feedAt = heartbeat.get();
			status = new StatusSnapshot(outOfService, feedAt.isBefore(refreshedAt) ? feedAt : refreshedAt);
		} catch (RuntimeException exception) {
			log.warn("Facility status refresh failed; keeping the last successful observation time", exception);
		}
	}

	@Override
	public FacilityAvailabilityView currentView() {
		StatusSnapshot currentStatus = status;
		if (currentStatus == null) {
			return FacilityAvailabilityView.unavailable();
		}
		TransitionFacilityRequirements requirements = activeRequirements.get();
		if (!requirements.present()) {
			return FacilityAvailabilityView.unavailable();
		}
		Evaluation cached = evaluation;
		if (cached != null && cached.status() == currentStatus && cached.requirements() == requirements) {
			return cached.view();
		}
		FacilityAvailabilityView view = FacilityAvailabilityView.blocked(
			currentStatus.observedAt(),
			StepFreeTransitionEvaluator.blockedTransitionKeys(requirements, currentStatus.outOfServiceFacilityIds()));
		evaluation = new Evaluation(currentStatus, requirements, view);
		return view;
	}

	private double blockedTransitionCount() {
		FacilityAvailabilityView view = currentView();
		return view.available() ? view.blockedPathwayEdgeIds().size() : Double.NaN;
	}

	private double secondsSinceLastSuccess() {
		StatusSnapshot currentStatus = status;
		return currentStatus == null
			? Double.NaN
			: Duration.between(currentStatus.observedAt(), clock.instant()).toSeconds();
	}

	private record StatusSnapshot(Set<String> outOfServiceFacilityIds, Instant observedAt) {
	}

	private record Evaluation(StatusSnapshot status, TransitionFacilityRequirements requirements, FacilityAvailabilityView view) {
	}
}
