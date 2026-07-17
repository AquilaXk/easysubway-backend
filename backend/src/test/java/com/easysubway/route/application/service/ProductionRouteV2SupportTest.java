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
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PlannerIdentity;
import com.easysubway.route.application.port.out.RouteV2AccessStore;
import com.easysubway.route.application.port.out.RouteV2AccessStore.RouteV2State;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteStep;
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
	@DisplayName("planner topology identity가 불완전하면 fail closed한다")
	void rejectsIncompletePlannerIdentity() {
		when(timetable.activeItxTimetableArtifactId()).thenReturn(Optional.of("itx-artifact"));
		var plan = new RouteV2Plan(
			List.of(), List.of(RouteV2Status.NO_TIMETABLE_SERVICE), "planner", null,
			RouteV2PlanSource.TIMETABLE_RAPTOR, "itx-artifact",
			new PlannerIdentity("invalid", "b".repeat(64), "c".repeat(64),
				"sha256:" + "d".repeat(64), "d".repeat(64), "e".repeat(64), "f".repeat(64))
		);

		assertThatThrownBy(() -> support.requireUsablePlan(plan))
			.isInstanceOf(ItxTimetableUnavailableException.class);
	}

	@Test
	@DisplayName("FOUND 계획에 공식 운임 또는 typed RIDE가 없으면 fail closed한다")
	void rejectsIncompletePlannerContract() {
		when(timetable.activeItxTimetableArtifactId()).thenReturn(Optional.of("itx-artifact"));
		RouteSearchResult missingFare = mock(RouteSearchResult.class);
		when(missingFare.status()).thenReturn(RouteSearchStatus.FOUND);
		when(missingFare.officialFare()).thenReturn(null);
		when(missingFare.steps()).thenReturn(List.of());

		assertThatThrownBy(() -> support.requireUsablePlan(foundPlan(missingFare)))
			.isInstanceOf(ItxTimetableUnavailableException.class);

		RouteSearchResult untypedRide = mock(RouteSearchResult.class);
		RouteStep ride = mock(RouteStep.class);
		when(untypedRide.status()).thenReturn(RouteSearchStatus.FOUND);
		when(untypedRide.officialFare()).thenReturn(officialFare());
		when(untypedRide.objectiveTags()).thenReturn(List.of("FASTEST"));
		when(untypedRide.steps()).thenReturn(List.of(ride));
		when(ride.stepType()).thenReturn("ride");
		when(ride.plannedDepartureTime()).thenReturn("2026-07-17T07:00:00+09:00");
		when(ride.plannedArrivalTime()).thenReturn("2026-07-17T07:30:00+09:00");

		assertThatThrownBy(() -> support.requireUsablePlan(foundPlan(untypedRide)))
			.isInstanceOf(ItxTimetableUnavailableException.class);
	}

	@Test
	@DisplayName("공식 운임·objective·typed RIDE가 완전한 timetable 계획만 허용한다")
	void acceptsCompletePlannerContract() {
		when(timetable.activeItxTimetableArtifactId()).thenReturn(Optional.of("itx-artifact"));
		RouteSearchResult itinerary = mock(RouteSearchResult.class);
		RouteStep ride = mock(RouteStep.class);
		when(itinerary.status()).thenReturn(RouteSearchStatus.FOUND);
		when(itinerary.officialFare()).thenReturn(officialFare());
		when(itinerary.objectiveTags()).thenReturn(List.of("FASTEST"));
		when(itinerary.steps()).thenReturn(List.of(ride));
		when(ride.stepType()).thenReturn("ride");
		when(ride.tripId()).thenReturn("trip-itx-1001-7");
		when(ride.serviceClass()).thenReturn("ITX_CHEONGCHUN");
		when(ride.servicePattern()).thenReturn("EXPRESS");
		when(ride.trainNo()).thenReturn("1001");
		when(ride.plannedDepartureTime()).thenReturn("2026-07-17T07:00:00+09:00");
		when(ride.plannedArrivalTime()).thenReturn("2026-07-17T07:30:00+09:00");

		assertThat(support.requireUsablePlan(foundPlan(itinerary))).isEqualTo("itx-artifact");
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

	private RouteV2Plan foundPlan(RouteSearchResult itinerary) {
		return new RouteV2Plan(
			List.of(itinerary),
			List.of(RouteV2Status.FOUND),
			"planner",
			null,
			RouteV2PlanSource.TIMETABLE_RAPTOR,
			"itx-artifact",
			plannerIdentity()
		);
	}

	private RouteSearchResult.OfficialFare officialFare() {
		return new RouteSearchResult.OfficialFare(
			9_800,
			"KRW",
			"SUM_OF_OFFICIAL_RIDE_OD_FARES",
			List.of("tago-train-schedule-fares"),
			List.of("snapshot-1")
		);
	}

	private PlannerIdentity plannerIdentity() {
		return new PlannerIdentity(
			"a".repeat(64),
			"b".repeat(64),
			"c".repeat(64),
			"sha256:" + "d".repeat(64),
			"d".repeat(64),
			"e".repeat(64),
			"f".repeat(64)
		);
	}
}
