package com.easysubway.transit.adapter.out.bundle;

import com.easysubway.journey.bundle.ActiveRouteBundleSnapshot;
import com.easysubway.journey.bundle.RouteBundleActivationException;
import com.easysubway.journey.bundle.RouteBundleActivationRegistry;
import com.easysubway.journey.bundle.RouteBundleFacilityCatalog;
import com.easysubway.transit.application.port.out.LoadBundleElevatorFacilitiesPort;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 활성 경로 번들에서 {@code smrt-elev:} 시설 목록을 읽는 어댑터(#419). 번들 레지스트리는 운영 프로필에만 있으므로, 레지스트리가
 * 없거나 활성 번들이 없으면(미적재·만료) 목록이 없다고 알린다. 다른 원천의 목록으로 대신하지 않는다.
 */
@Component
class ActiveRouteBundleElevatorFacilityCatalog implements LoadBundleElevatorFacilitiesPort {

	private final Supplier<RouteBundleActivationRegistry> registry;

	@Autowired
	ActiveRouteBundleElevatorFacilityCatalog(ObjectProvider<RouteBundleActivationRegistry> registry) {
		this(registry::getIfAvailable);
	}

	ActiveRouteBundleElevatorFacilityCatalog(Supplier<RouteBundleActivationRegistry> registry) {
		this.registry = registry;
	}

	@Override
	public Optional<List<BundleElevatorFacility>> loadActiveBundleElevatorFacilities() {
		RouteBundleActivationRegistry current = registry.get();
		if (current == null) {
			return Optional.empty();
		}
		ActiveRouteBundleSnapshot active;
		try {
			active = current.activeSnapshot();
		} catch (RouteBundleActivationException exception) {
			return Optional.empty();
		}
		if (!(active.runtimeView() instanceof RouteBundleFacilityCatalog catalog)) {
			throw new IllegalStateException("active route-bundle runtime has no facility catalog");
		}
		return Optional.of(catalog.smrtElevatorFacilities().stream()
			.map(facility -> new BundleElevatorFacility(facility.id(), facility.name()))
			.toList());
	}
}
