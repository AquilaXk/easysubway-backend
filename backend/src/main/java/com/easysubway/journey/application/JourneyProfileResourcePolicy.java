package com.easysubway.journey.application;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One caller-supplied, versioned resource-policy snapshot for Journey point and profile requests.
 *
 * <p>This type deliberately defines no default values. A future production adapter must load and
 * verify one adopted policy artifact before constructing it.</p>
 */
public record JourneyProfileResourcePolicy(
	Identity identity,
	Duration maxTemporalWindow,
	int maxServiceDayCount,
	long maxEstimatedWork,
	int maxLabelsPerState,
	int maxDestinationProfileLabels,
	int maxProfileBreakpoints,
	Duration realtimeApplicableFutureHorizon,
	Duration pointSearchDeadline,
	Duration profileSearchDeadline,
	Duration lastConnectionDeadline,
	int pointSearchCostUnits,
	int shortDepartureProfileCostUnits,
	int arriveByProfileCostUnits,
	int lastConnectionCostUnits,
	int maxCostUnitsPerSession
) {
	public JourneyProfileResourcePolicy {
		identity = Objects.requireNonNull(identity, "identity");
		maxTemporalWindow = positive(maxTemporalWindow, "maxTemporalWindow");
		positive(maxServiceDayCount, "maxServiceDayCount");
		positive(maxEstimatedWork, "maxEstimatedWork");
		positive(maxLabelsPerState, "maxLabelsPerState");
		positive(maxDestinationProfileLabels, "maxDestinationProfileLabels");
		positive(maxProfileBreakpoints, "maxProfileBreakpoints");
		realtimeApplicableFutureHorizon = positive(
			realtimeApplicableFutureHorizon, "realtimeApplicableFutureHorizon");
		pointSearchDeadline = positive(pointSearchDeadline, "pointSearchDeadline");
		profileSearchDeadline = positive(profileSearchDeadline, "profileSearchDeadline");
		lastConnectionDeadline = positive(lastConnectionDeadline, "lastConnectionDeadline");
		positive(pointSearchCostUnits, "pointSearchCostUnits");
		positive(shortDepartureProfileCostUnits, "shortDepartureProfileCostUnits");
		positive(arriveByProfileCostUnits, "arriveByProfileCostUnits");
		positive(lastConnectionCostUnits, "lastConnectionCostUnits");
		positive(maxCostUnitsPerSession, "maxCostUnitsPerSession");
		if (pointSearchCostUnits > maxCostUnitsPerSession
			|| shortDepartureProfileCostUnits > maxCostUnitsPerSession
			|| arriveByProfileCostUnits > maxCostUnitsPerSession
			|| lastConnectionCostUnits > maxCostUnitsPerSession) {
			throw new IllegalArgumentException("request costs must fit maxCostUnitsPerSession");
		}
	}

	public Duration deadlineFor(JourneyRaptorQuery.TemporalQuery temporalQuery) {
		return switch (Objects.requireNonNull(temporalQuery, "temporalQuery")) {
			case JourneyRaptorQuery.DepartAt ignored -> pointSearchDeadline;
			case JourneyRaptorQuery.DepartBetween ignored -> profileSearchDeadline;
			case JourneyRaptorQuery.ArriveBy ignored -> profileSearchDeadline;
			case JourneyRaptorQuery.LastConnection ignored -> lastConnectionDeadline;
		};
	}

	public int costUnitsFor(JourneyRaptorQuery.TemporalQuery temporalQuery) {
		return switch (Objects.requireNonNull(temporalQuery, "temporalQuery")) {
			case JourneyRaptorQuery.DepartAt ignored -> pointSearchCostUnits;
			case JourneyRaptorQuery.DepartBetween ignored -> shortDepartureProfileCostUnits;
			case JourneyRaptorQuery.ArriveBy ignored -> arriveByProfileCostUnits;
			case JourneyRaptorQuery.LastConnection ignored -> lastConnectionCostUnits;
		};
	}

	/** Exact planner limits captured with this policy; public alternatives are deliberately absent. */
	public ProfilePlanningLimits profilePlanningLimits() {
		return new ProfilePlanningLimits(maxEstimatedWork, maxLabelsPerState,
			maxDestinationProfileLabels, maxProfileBreakpoints);
	}

	public record ProfilePlanningLimits(
		long maxEstimatedWork,
		int maxLabelsPerState,
		int maxDestinationProfileLabels,
		int maxProfileBreakpoints
	) {
		public ProfilePlanningLimits {
			positive(maxEstimatedWork, "maxEstimatedWork");
			positive(maxLabelsPerState, "maxLabelsPerState");
			positive(maxDestinationProfileLabels, "maxDestinationProfileLabels");
			positive(maxProfileBreakpoints, "maxProfileBreakpoints");
		}
	}

	public record Identity(String resourcePolicyId, String semanticVersion, String resourcePolicySha256) {
		private static final Pattern SHA_256 = Pattern.compile("^[a-f0-9]{64}$");

		public Identity {
			resourcePolicyId = requireText(resourcePolicyId, "resourcePolicyId");
			semanticVersion = requireText(semanticVersion, "semanticVersion");
			resourcePolicySha256 = requireText(resourcePolicySha256, "resourcePolicySha256");
			if (!SHA_256.matcher(resourcePolicySha256).matches()) {
				throw new IllegalArgumentException("resourcePolicySha256 must be lowercase SHA-256");
			}
		}
	}

	private static Duration positive(Duration value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
		return value;
	}

	private static void positive(long value, String name) {
		if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}
}
