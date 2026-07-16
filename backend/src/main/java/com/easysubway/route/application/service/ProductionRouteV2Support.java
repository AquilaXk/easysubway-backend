package com.easysubway.route.application.service;

import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteV2Plan;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteV2PlanSource;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteV2Status;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.RouteV2AccessStore;
import com.easysubway.route.application.port.out.RouteV2AccessStore.RouteV2State;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("prod | staging | release | prod-like")
public class ProductionRouteV2Support {

	private final LoadRouteTimetablePort timetablePort;
	private final RouteV2AccessStore stateStore;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	@Autowired
	public ProductionRouteV2Support(
		LoadRouteTimetablePort timetablePort,
		RouteV2AccessStore stateStore,
		ObjectMapper objectMapper
	) {
		this(timetablePort, stateStore, objectMapper, Clock.systemUTC());
	}

	ProductionRouteV2Support(
		LoadRouteTimetablePort timetablePort,
		RouteV2AccessStore stateStore,
		ObjectMapper objectMapper,
		Clock clock
	) {
		this.timetablePort = timetablePort;
		this.stateStore = stateStore;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	public String requireTimetableArtifact() {
		return timetablePort.activeItxTimetableArtifactId()
			.orElseThrow(ItxTimetableUnavailableException::new);
	}

	public String requireUsablePlan(RouteV2Plan plan) {
		if (plan.source() != RouteV2PlanSource.TIMETABLE_RAPTOR
			|| plan.statuses().contains(RouteV2Status.STALE_TIMETABLE)
			|| plan.timetableArtifactId() == null
			|| plan.timetableArtifactId().isBlank()
			|| timetablePort.activeItxTimetableArtifactId().filter(plan.timetableArtifactId()::equals).isEmpty()) {
			throw new ItxTimetableUnavailableException();
		}
		return plan.timetableArtifactId();
	}

	public void saveState(
		String routeStateId,
		String originStationId,
		String destinationStationId,
		Instant requestedDepartureAt,
		Object computedItinerary,
		String timetableArtifactId,
		Instant plannedArrivalAt
	) {
		Instant createdAt = clock.instant();
		stateStore.saveState(new RouteV2State(
			routeStateId,
			originStationId,
			destinationStationId,
			"SUBWAY_AND_ITX_CHEONGCHUN",
			requestedDepartureAt,
			json(computedItinerary),
			timetableArtifactId,
			createdAt,
			plannedArrivalAt,
			RouteV2EphemeralStateService.expiresAt(createdAt, plannedArrivalAt)
		));
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Route V2 itinerary serialization failed", exception);
		}
	}
}
