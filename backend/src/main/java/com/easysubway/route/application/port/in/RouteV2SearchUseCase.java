package com.easysubway.route.application.port.in;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.ProfileWalkTimeCalculator;
import com.easysubway.route.domain.ProfileWalkTimeCalculator.MobilityPreset;
import com.easysubway.route.domain.RouteSearchResult;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

public interface RouteV2SearchUseCase {

	RouteV2Plan search(SearchRouteV2Command command);

	record SearchRouteV2Command(
		String originStationId,
		String destinationStationId,
		OffsetDateTime departureTime,
		MobilityType mobilityType,
		MobilityPreset mobilityPreset,
		ConstraintMode constraintMode,
		boolean useRealtime,
		int maxTransfers,
		int alternativeCount
	) {
		public SearchRouteV2Command(
			String originStationId,
			String destinationStationId,
			OffsetDateTime departureTime,
			MobilityType mobilityType,
			ConstraintMode constraintMode,
			boolean useRealtime,
			int maxTransfers,
			int alternativeCount
		) {
			this(
				originStationId,
				destinationStationId,
				departureTime,
				mobilityType,
				null,
				constraintMode,
				useRealtime,
				maxTransfers,
				alternativeCount
			);
		}

		public SearchRouteV2Command {
			requireText(originStationId, "originStationId");
			requireText(destinationStationId, "destinationStationId");
			Objects.requireNonNull(departureTime, "departureTime must not be null");
			Objects.requireNonNull(mobilityType, "mobilityType must not be null");
			mobilityPreset = mobilityPreset == null
				? ProfileWalkTimeCalculator.presetFor(mobilityType)
				: mobilityPreset;
			Objects.requireNonNull(constraintMode, "constraintMode must not be null");
			if (maxTransfers < 0) {
				throw new IllegalArgumentException("maxTransfers must not be negative");
			}
			if (maxTransfers > 3) {
				throw new IllegalArgumentException("maxTransfers must be 3 or less");
			}
			if (alternativeCount < 1) {
				throw new IllegalArgumentException("alternativeCount must be at least 1");
			}
			if (alternativeCount > 3) {
				throw new IllegalArgumentException("alternativeCount must be 3 or less");
			}
		}
	}

	record RouteV2Plan(
		List<RouteSearchResult> itineraries,
		List<RouteV2Status> statuses,
		String plannerAdr,
		OffsetDateTime nextServiceTime
	) {
		public RouteV2Plan(
			List<RouteSearchResult> itineraries,
			List<RouteV2Status> statuses,
			String plannerAdr
		) {
			this(itineraries, statuses, plannerAdr, null);
		}

		public RouteV2Plan {
			itineraries = List.copyOf(Objects.requireNonNull(itineraries, "itineraries must not be null"));
			statuses = List.copyOf(Objects.requireNonNull(statuses, "statuses must not be null"));
			Objects.requireNonNull(plannerAdr, "plannerAdr must not be null");
		}
	}

	enum RouteV2Status {
		FOUND,
		BLOCKED_ACCESSIBILITY,
		NO_TIMETABLE_SERVICE,
		STALE_TIMETABLE,
		REALTIME_UNAVAILABLE_PLANNED_USED
	}

	private static void requireText(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
	}
}
