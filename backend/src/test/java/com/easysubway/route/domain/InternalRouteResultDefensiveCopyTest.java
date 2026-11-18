package com.easysubway.route.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.transit.domain.RouteEdgeType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Internal route result immutable list boundary")
class InternalRouteResultDefensiveCopyTest {

	@Test
	@DisplayName("constructor snapshots every mutable list input")
	void snapshotsMutableListInputs() {
		var step = internalStep();
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
	@DisplayName("accessors expose immutable lists")
	void exposesImmutableLists() {
		var result = result(
			new ArrayList<>(List.of(internalStep())),
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

	private static InternalRouteResult result(
		List<InternalRouteStep> steps,
		List<RouteWarning> warnings,
		List<String> blockedReasons
	) {
		return new InternalRouteResult(
			"station-1",
			"테스트역",
			"node-a",
			"출발",
			"node-b",
			"도착",
			MobilityType.WHEELCHAIR,
			RouteSearchStatus.FOUND,
			100,
			60,
			steps,
			warnings,
			blockedReasons
		);
	}

	private static InternalRouteStep internalStep() {
		return new InternalRouteStep(
			1,
			"edge-1",
			"node-a",
			"출발",
			"node-b",
			"도착",
			RouteEdgeType.ELEVATOR,
			100,
			60,
			false,
			true,
			false,
			0,
			2,
			100,
			"엘리베이터를 이용하세요."
		);
	}
}
