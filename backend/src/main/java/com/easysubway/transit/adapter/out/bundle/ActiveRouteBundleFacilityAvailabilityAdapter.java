package com.easysubway.transit.adapter.out.bundle;

import com.easysubway.journey.application.FacilityAvailabilityPort;
import com.easysubway.journey.application.FacilityAvailabilityView;
import com.easysubway.journey.bundle.ActiveRouteBundleSnapshot;
import com.easysubway.journey.bundle.RouteBundleActivationException;
import com.easysubway.journey.bundle.RouteBundleActivationRegistry;
import com.easysubway.journey.bundle.RouteBundleFacilityCatalog;
import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore;
import com.easysubway.transit.domain.FacilityOperationalState;
import com.easysubway.transit.domain.FacilityOperationalStatus;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 활성 경로 번들의 시설-전환간선 색인과 실시간 시설 가동 상태 저장소를 결합해 차단 간선을 계산한다.
 */
public final class ActiveRouteBundleFacilityAvailabilityAdapter implements FacilityAvailabilityPort {

	private static final Logger log = LoggerFactory.getLogger(ActiveRouteBundleFacilityAvailabilityAdapter.class);

	private final RouteBundleActivationRegistry registry;
	private final FacilityOperationalStatusStore statusStore;

	public ActiveRouteBundleFacilityAvailabilityAdapter(
		RouteBundleActivationRegistry registry,
		FacilityOperationalStatusStore statusStore
	) {
		this.registry = Objects.requireNonNull(registry, "registry");
		this.statusStore = Objects.requireNonNull(statusStore, "statusStore");
	}

	@Override
	public FacilityAvailabilityView currentView() {
		ActiveRouteBundleSnapshot snapshot;
		try {
			snapshot = registry.activeSnapshot();
		} catch (RouteBundleActivationException exception) {
			log.warn("Active route bundle unavailable for facility availability lookup: reason={}",
				exception.reason(), exception);
			return FacilityAvailabilityView.unavailable();
		} catch (RuntimeException exception) {
			log.warn("Unexpected failure loading active route bundle for facility availability lookup", exception);
			return FacilityAvailabilityView.unavailable();
		}
		if (!(snapshot.runtimeView() instanceof RouteBundleFacilityCatalog catalog)) {
			log.warn("Active route bundle runtimeView does not implement RouteBundleFacilityCatalog: {}",
				snapshot.runtimeView().getClass().getName());
			return FacilityAvailabilityView.unavailable();
		}

		Instant heartbeatAt;
		List<FacilityOperationalStatus> statuses;
		try {
			Optional<Instant> observed = statusStore.lastSuccessfulCollectionAt(
				FacilityOperationalStatusStore.SEOUL_METRO_ELEVATOR_FEED);
			if (observed.isEmpty()) {
				log.warn("Missing successful collection heartbeat for feed={} — returning unavailable facility view",
					FacilityOperationalStatusStore.SEOUL_METRO_ELEVATOR_FEED);
				return FacilityAvailabilityView.unavailable();
			}
			heartbeatAt = observed.get();
			statuses = statusStore.loadStatuses();
		} catch (RuntimeException exception) {
			log.warn("Failed to query facility operational statuses — returning unavailable facility view", exception);
			return FacilityAvailabilityView.unavailable();
		}
		if (statuses == null) {
			log.warn("FacilityOperationalStatusStore returned null status list — returning unavailable facility view");
			return FacilityAvailabilityView.unavailable();
		}

		Set<String> blockedEdgeIds = new LinkedHashSet<>();
		for (FacilityOperationalStatus status : statuses) {
			if (status.status() != FacilityOperationalState.OUT_OF_SERVICE) {
				continue;
			}
			Set<String> edgeIds = catalog.transitionEdgeIdsForFacility(status.facilityId());
			if (!edgeIds.isEmpty()) {
				blockedEdgeIds.addAll(edgeIds);
			}
		}
		if (blockedEdgeIds.isEmpty()) {
			return FacilityAvailabilityView.empty(heartbeatAt);
		}
		return FacilityAvailabilityView.blocked(heartbeatAt, Set.copyOf(blockedEdgeIds));
	}
}
