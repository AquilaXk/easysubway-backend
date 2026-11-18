package com.easysubway.transit.adapter.out.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.easysubway.journey.bundle.ActiveRouteBundleSnapshot;
import com.easysubway.journey.bundle.RouteBundleActivationRegistry;
import com.easysubway.journey.bundle.RouteBundleFacilityCatalog;
import com.easysubway.journey.bundle.RouteBundleRuntimeView;
import com.easysubway.transit.application.port.out.LoadBundleElevatorFacilitiesPort.BundleElevatorFacility;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("활성 경로 번들 smrt-elev 시설 목록 포트")
class ActiveRouteBundleElevatorFacilityCatalogTest {

	@Test
	@DisplayName("활성 번들의 smrt-elev 시설을 그대로 읽는다")
	void readsSmrtElevatorFacilitiesFromTheActiveBundle() {
		var registry = mock(RouteBundleActivationRegistry.class);
		var active = mock(ActiveRouteBundleSnapshot.class);
		when(registry.activeSnapshot()).thenReturn(active);
		when(active.runtimeView()).thenReturn(new CatalogRuntime(List.of(
			new RouteBundleFacilityCatalog.Facility("smrt-elev:0201:2:1번 출입구", "가역 엘리베이터 1번 출입구")
		)));

		assertThat(new ActiveRouteBundleElevatorFacilityCatalog(() -> registry).loadActiveBundleElevatorFacilities())
			.contains(List.of(new BundleElevatorFacility("smrt-elev:0201:2:1번 출입구", "가역 엘리베이터 1번 출입구")));
	}

	@Test
	@DisplayName("번들 레지스트리가 없거나 활성 번들이 없으면 목록이 없다고 알린다")
	void reportsUnavailableWithoutRegistryOrActiveBundle() {
		assertThat(new ActiveRouteBundleElevatorFacilityCatalog(() -> null).loadActiveBundleElevatorFacilities()).isEmpty();
		var emptyRegistry = new RouteBundleActivationRegistry(Clock.systemUTC());
		assertThat(new ActiveRouteBundleElevatorFacilityCatalog(() -> emptyRegistry).loadActiveBundleElevatorFacilities()).isEmpty();
	}

	@Test
	@DisplayName("활성 번들 런타임이 시설 목록을 갖지 않으면 명시적으로 실패한다")
	void failsWhenActiveRuntimeHasNoFacilityCatalog() {
		var registry = mock(RouteBundleActivationRegistry.class);
		var active = mock(ActiveRouteBundleSnapshot.class);
		when(registry.activeSnapshot()).thenReturn(active);
		when(active.runtimeView()).thenReturn(new RouteBundleRuntimeView() {
		});

		assertThatThrownBy(() -> new ActiveRouteBundleElevatorFacilityCatalog(() -> registry).loadActiveBundleElevatorFacilities())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("facility catalog");
	}

	private record CatalogRuntime(List<Facility> smrtElevatorFacilities)
		implements RouteBundleRuntimeView, RouteBundleFacilityCatalog {
	}
}
