package com.easysubway.route.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.profile.domain.MobilityType;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Route search result immutable list boundary")
class RouteSearchResultDefensiveCopyTest {

	@Test
	@DisplayName("constructor snapshots every mutable route list input")
	void snapshotsMutableListInputs() {
		var step = routeStep();
		var warning = new RouteWarning(RouteWarningCode.LOW_DATA_CONFIDENCE);
		var steps = new ArrayList<>(List.of(step));
		var warnings = new ArrayList<>(List.of(warning));
		var blockedReasons = new ArrayList<>(List.of("blocked"));
		var result = result(steps, warnings, blockedReasons);

		steps.clear();
		warnings.clear();
		blockedReasons.clear();

		assertThat(result.steps()).containsExactly(step);
		assertThat(result.warnings()).containsExactly(warning);
		assertThat(result.blockedReasons()).containsExactly("blocked");
	}

	@Test
	@DisplayName("route list accessors are immutable")
	void exposesImmutableLists() {
		var result = result(
			new ArrayList<>(List.of(routeStep())),
			new ArrayList<>(List.of(new RouteWarning(RouteWarningCode.LOW_DATA_CONFIDENCE))),
			new ArrayList<>(List.of("blocked"))
		);

		assertThatThrownBy(() -> result.steps().clear())
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> result.warnings().clear())
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> result.blockedReasons().clear())
			.isInstanceOf(UnsupportedOperationException.class);
	}

	private static RouteSearchResult result(
		List<RouteStep> steps,
		List<RouteWarning> warnings,
		List<String> blockedReasons
	) {
		return new RouteSearchResult(
			"route-1",
			"station-1",
			"출발역",
			"station-2",
			"도착역",
			MobilityType.WHEELCHAIR,
			RouteSearchStatus.FOUND,
			"line-1",
			"1호선",
			100,
			steps,
			warnings,
			blockedReasons,
			LocalDateTime.of(2026, 8, 13, 9, 0)
		);
	}

	private static RouteStep routeStep() {
		return new RouteStep(
			1,
			"ride",
			"1호선 이동",
			"출발역에서 도착역까지 이동",
			"line-1",
			"1호선",
			"station-1",
			"station-2",
			5,
			1000,
			false,
			false
		);
	}
}
