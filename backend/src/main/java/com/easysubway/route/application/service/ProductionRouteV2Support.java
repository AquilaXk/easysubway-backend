package com.easysubway.route.application.service;

import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteV2Plan;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteV2PlanSource;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.RouteV2Status;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.RouteV2AccessStore;
import com.easysubway.route.application.port.out.RouteV2AccessStore.RouteV2State;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteStep;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
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
			|| !validPlannerIdentity(plan)
			|| timetablePort.activeItxTimetableArtifactId().filter(plan.timetableArtifactId()::equals).isEmpty()
			|| plan.itineraries().stream().anyMatch(this::incompleteFoundItinerary)) {
			throw new ItxTimetableUnavailableException();
		}
		return plan.timetableArtifactId();
	}

	private boolean validPlannerIdentity(RouteV2Plan plan) {
		var identity = plan.plannerIdentity();
		return identity != null
			&& sha256(identity.timetableSnapshotSha256())
			&& sha256(identity.canonicalPackSha256())
			&& sha256(identity.canonicalPackSqliteSha256())
			&& identity.canonicalStationVersion() != null
			&& identity.canonicalStationVersion().matches("sha256:[0-9a-f]{64}")
			&& sha256(identity.canonicalStationSetSha256())
			&& sha256(identity.sourceLineageSha256())
			&& sha256(identity.evidenceHash());
	}

	private boolean sha256(String value) {
		return value != null && value.matches("[0-9a-f]{64}");
	}

	private boolean incompleteFoundItinerary(RouteSearchResult itinerary) {
		if (itinerary.status() != RouteSearchStatus.FOUND) {
			return false;
		}
		RouteSearchResult.OfficialFare fare = itinerary.officialFare();
		return fare == null
			|| fare.sourceIds().isEmpty()
			|| fare.sourceSnapshotIds().isEmpty()
			|| itinerary.objectiveTags().isEmpty()
			|| itinerary.steps().stream().noneMatch(step -> "ride".equals(step.stepType()))
			|| itinerary.steps().stream().anyMatch(this::incompletePlannerStep);
	}

	private boolean incompletePlannerStep(RouteStep step) {
		if (!validPlannedTimes(step)) {
			return true;
		}
		if (!"ride".equals(step.stepType())) {
			return step.tripId() != null || step.trainNo() != null
				|| step.serviceClass() != null || step.servicePattern() != null;
		}
		if (blank(step.tripId()) || blank(step.serviceClass()) || blank(step.servicePattern())) {
			return true;
		}
		return "ITX_CHEONGCHUN".equals(step.serviceClass()) && blank(step.trainNo());
	}

	private boolean validPlannedTimes(RouteStep step) {
		try {
			OffsetDateTime departure = OffsetDateTime.parse(step.plannedDepartureTime());
			OffsetDateTime arrival = OffsetDateTime.parse(step.plannedArrivalTime());
			return !arrival.isBefore(departure);
		} catch (RuntimeException exception) {
			return false;
		}
	}

	private boolean blank(String value) {
		return value == null || value.isBlank();
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
