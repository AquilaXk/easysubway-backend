package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteV2Plan;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteV2PlanSource;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteV2Status;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.RouteV2AccessStore;
import com.easysubway.route.application.port.out.RouteV2AccessStore.RouteV2State;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("Production Route V2 timetable·ephemeral support")
class ProductionRouteV2SupportTest {

	private static final Instant NOW = Instant.parse("2026-07-16T09:00:00Z");

	private LoadRouteTimetablePort timetable;
	private RouteV2AccessStore store;
	private ProductionRouteV2Support support;

	@BeforeEach
	void setUp() {
		timetable = mock(LoadRouteTimetablePort.class);
		store = mock(RouteV2AccessStore.class);
		support = new ProductionRouteV2Support(
			timetable,
			store,
			new ObjectMapper(),
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
	}

	@Test
	@DisplayName("fresh ITX artifact가 없거나 timetable provenance가 아니면 fail closed한다")
	void rejectsUnavailableOrStaleTimetable() {
		when(timetable.activeItxTimetableArtifactId()).thenReturn(Optional.empty());
		assertThatThrownBy(support::requireTimetableArtifact)
			.isInstanceOf(ItxTimetableUnavailableException.class);

		when(timetable.activeItxTimetableArtifactId()).thenReturn(Optional.of("itx-artifact"));
		assertThatThrownBy(() -> support.requireUsablePlan(
			new RouteV2Plan(
				List.of(),
				List.of(RouteV2Status.STALE_TIMETABLE),
				"planner",
				null,
				RouteV2PlanSource.TIMETABLE_RAPTOR
			)
		)).isInstanceOf(ItxTimetableUnavailableException.class);
		assertThatThrownBy(() -> support.requireUsablePlan(
			new RouteV2Plan(
				List.of(),
				List.of(RouteV2Status.FOUND),
				"planner",
				null,
				RouteV2PlanSource.LEGACY_GRAPH
			)
		)).isInstanceOf(ItxTimetableUnavailableException.class);
	}

	@Test
	@DisplayName("계획 전후 timetable artifact identity가 바뀌면 fail closed한다")
	void rejectsPlanWhenTimetableArtifactChangesDuringSearch() {
		when(timetable.activeItxTimetableArtifactId()).thenReturn(Optional.of("artifact-after"));

		var plan = new RouteV2Plan(
			List.of(),
			List.of(RouteV2Status.FOUND),
			"planner",
			null,
			RouteV2PlanSource.TIMETABLE_RAPTOR,
			"artifact-before"
		);

		assertThatThrownBy(() -> support.requireUsablePlan(plan))
			.isInstanceOf(ItxTimetableUnavailableException.class);
	}

	@Test
	@DisplayName("computed itinerary만 exact TTL과 artifact identity로 저장한다")
	void storesAllowlistedEphemeralState() {
		support.saveState(
			"itinerary-1",
			"station-origin",
			"station-destination",
			Instant.parse("2026-07-16T09:05:00Z"),
			List.of("computed-itinerary"),
			"itx-artifact",
			Instant.parse("2026-07-16T11:00:00Z")
		);

		var state = ArgumentCaptor.forClass(RouteV2State.class);
		verify(store).saveState(state.capture());
		assertThat(state.getValue().transportScope()).isEqualTo("SUBWAY_AND_ITX_CHEONGCHUN");
		assertThat(state.getValue().itineraryJson()).isEqualTo("[\"computed-itinerary\"]");
		assertThat(state.getValue().createdAt()).isEqualTo(NOW);
		assertThat(state.getValue().expiresAt()).isEqualTo(Instant.parse("2026-07-16T11:30:00Z"));
	}
}
