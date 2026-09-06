package com.easysubway.route.application.service;

import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.ServiceDayResolver;
import com.easysubway.journey.bundle.JourneyProfileMeasurementInputs;
import com.easysubway.journey.bundle.JourneyProfileMeasurementInputs.CompiledMeasurementInputs;
import com.easysubway.journey.bundle.JourneyProfileMeasurementInputs.Scope;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Test-only, fail-closed predeployment measurement corpus over pinned candidate inputs.
 *
 * <p>The runner neither admits a bundle for serving nor infers oracle facts from planner output.
 * Every expected frontier is solved from the raw scheduled timetable and raw access evidence.</p>
 */
public final class JourneyProfileFullCorpusRunner {
	private static final JourneyRequest.MobilityProfile PROFILE = JourneyRequest.MobilityProfile.STANDARD;
	private static final JourneyRequest.ConstraintMode CONSTRAINT = JourneyRequest.ConstraintMode.NONE;
	private static final JourneyRequest.WalkingPace WALKING_PACE = JourneyRequest.WalkingPace.STANDARD;
	private static final JourneyRequest.TimePolicy TIME_POLICY = JourneyRequest.TimePolicy.TIMETABLE_REQUIRED;

	private JourneyProfileFullCorpusRunner() {
	}

	public static CorpusResult run(
		Path candidateRoot,
		Path measurementInput,
		JourneyProfileResourcePolicy resourcePolicy,
		OracleLimits oracleLimits,
		int expectedBoardingSlackSeconds,
		long runtimeGeneration
	) throws IOException {
		JourneyProfileResourcePolicy requiredPolicy = Objects.requireNonNull(resourcePolicy, "resourcePolicy");
		OracleLimits requiredLimits = Objects.requireNonNull(oracleLimits, "oracleLimits");
		if (expectedBoardingSlackSeconds < 0) {
			throw new IllegalArgumentException("expectedBoardingSlackSeconds must not be negative");
		}
		if (runtimeGeneration < 1) {
			throw new IllegalArgumentException("runtimeGeneration must be positive");
		}
		var pinned = JourneyProfileMeasurementInputs.read(
			Objects.requireNonNull(candidateRoot, "candidateRoot"),
			Objects.requireNonNull(measurementInput, "measurementInput"));
		return run(pinned, requiredPolicy, requiredLimits, expectedBoardingSlackSeconds, runtimeGeneration);
	}

	public static CorpusResult run(
		JourneyProfileMeasurementInputs.PinnedInputs pinned,
		JourneyProfileResourcePolicy resourcePolicy,
		OracleLimits oracleLimits,
		int expectedBoardingSlackSeconds,
		long runtimeGeneration
	) {
		return run(JourneyProfileMeasurementInputs.compile(pinned, runtimeGeneration),
			JourneyProfileMeasurementInputs.scope(pinned), pinned.measurementInput().regionIds(), resourcePolicy,
			oracleLimits, expectedBoardingSlackSeconds);
	}

	static CorpusResult run(
		CompiledMeasurementInputs compiled,
		Scope scope,
		List<String> regionIds,
		JourneyProfileResourcePolicy resourcePolicy,
		OracleLimits oracleLimits,
		int expectedBoardingSlackSeconds
	) {
		Objects.requireNonNull(compiled, "compiled");
		Objects.requireNonNull(scope, "scope");
		regionIds = List.copyOf(Objects.requireNonNull(regionIds, "regionIds"));
		JourneyProfileResourcePolicy requiredPolicy = Objects.requireNonNull(resourcePolicy, "resourcePolicy");
		OracleLimits requiredLimits = Objects.requireNonNull(oracleLimits, "oracleLimits");
		if (regionIds.isEmpty() || new LinkedHashSet<>(regionIds).size() != regionIds.size()) {
			throw new IllegalArgumentException("regionIds must be nonempty and distinct");
		}
		if (expectedBoardingSlackSeconds < 0) {
			throw new IllegalArgumentException("expectedBoardingSlackSeconds must not be negative");
		}

		Instant activeFrom = compiled.identity().activeFromInstant();
		Instant freshUntil = compiled.identity().freshUntilInstant();
		Set<String> canonicalLines = scope.activeLines().stream()
			.map(JourneyProfileMeasurementInputs.Line::lineId)
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
		List<JourneyProfileCandidateEvents.Event> events = JourneyProfileCandidateEvents.events(
			compiled.runtime().compiledTimetable(), activeFrom, freshUntil, canonicalLines);
		List<JourneyProfileExactOracle.Access> accesses = JourneyProfileOracleAccessInputs.normalize(
			compiled.sourceTimetable().routeAccessData(), PROFILE, CONSTRAINT, WALKING_PACE.speedMetersPerHour(),
			requiredLimits.maxAccesses());

		var rows = new ArrayList<Map<String, Object>>();
		var regionFacts = new ArrayList<Map<String, Object>>();
		var applicableRides = rawRides(compiled, applicableServiceDates(activeFrom, freshUntil), requiredLimits);
		for (String regionId : regionIds) {
			JourneyProfileMeasurementOd.DirectOdCandidate direct = JourneyProfileMeasurementOd.selectDirectOd(
				scope, regionId, events, accesses, activeFrom, freshUntil, expectedBoardingSlackSeconds);
			List<JourneyProfileExactOracle.Ride> directRides = applicableRides;
			Instant departureLatestReadyAt = profileWindow(direct.readyAt(), freshUntil,
				requiredPolicy.maxTemporalWindow()).latestReadyAt();
			Instant pointTerminal = terminalDeadline(direct.destinationStationId(), directRides, accesses);
			Instant lastConnectionTerminal = terminalDeadline(direct.destinationStationId(),
				rawRides(compiled, List.of(direct.serviceDate()), requiredLimits), accesses);
			Instant typedFailureDeadline = typedFailureDeadline(direct, directRides, accesses, requiredLimits,
				expectedBoardingSlackSeconds);
			rows.add(pointRow(regionId, compiled, direct, directRides, accesses, requiredLimits,
				expectedBoardingSlackSeconds));
			rows.add(departureRow(regionId, compiled, direct, directRides, accesses, requiredPolicy, requiredLimits,
				expectedBoardingSlackSeconds, departureLatestReadyAt));
			rows.add(arriveByRow(regionId, compiled, direct, directRides, accesses, requiredPolicy, requiredLimits,
				expectedBoardingSlackSeconds));
			rows.add(lastConnectionRow(regionId, compiled, direct, accesses, requiredPolicy, requiredLimits,
				expectedBoardingSlackSeconds, lastConnectionTerminal));
			JourneyProfileMeasurementOd.DirectOdCandidate cutoff = crossCutoffCandidate(
				scope, regionId, events, accesses, activeFrom, freshUntil, expectedBoardingSlackSeconds);
			List<JourneyProfileExactOracle.Ride> cutoffRides = applicableRides;
			rows.add(cutoffRow(regionId, compiled, cutoff, cutoffRides, accesses, requiredPolicy, requiredLimits,
				expectedBoardingSlackSeconds));
			rows.add(typedFailureRow(regionId, compiled, direct, directRides, accesses, requiredPolicy, requiredLimits,
				expectedBoardingSlackSeconds, typedFailureDeadline));
			regionFacts.add(regionFact(regionId, direct, cutoff, departureLatestReadyAt, lastConnectionTerminal,
				typedFailureDeadline, pointTerminal, cutoffReadyAt(cutoff.readyAt(), activeFrom)));
		}
		return new CorpusResult(rows, corpusFact(compiled, scope, regionIds, requiredPolicy, requiredLimits,
			expectedBoardingSlackSeconds, regionFacts));
	}

	private static Map<String, Object> pointRow(
		String regionId,
		CompiledMeasurementInputs compiled,
		JourneyProfileMeasurementOd.DirectOdCandidate candidate,
		List<JourneyProfileExactOracle.Ride> rides,
		List<JourneyProfileExactOracle.Access> accesses,
		OracleLimits limits,
		int boardingSlackSeconds
	) {
		JourneyRaptorQuery query = query(candidate, new JourneyRaptorQuery.DepartAt(candidate.readyAt()));
		List<JourneyProfileExactOracle.Candidate> expected = new JourneyProfileExactOracle().solvePoint(
			oracleQuery(candidate, candidate.readyAt(), terminalDeadline(candidate.destinationStationId(), rides, accesses),
				limits, boardingSlackSeconds), rides, accesses);
		return JourneyProfileMeasuredExecution.pointRow(regionId,
			JourneyProfileMeasuredExecution.measurePoint(query, compiled.runtime()), expected);
	}

	private static Map<String, Object> departureRow(
		String regionId,
		CompiledMeasurementInputs compiled,
		JourneyProfileMeasurementOd.DirectOdCandidate candidate,
		List<JourneyProfileExactOracle.Ride> rides,
		List<JourneyProfileExactOracle.Access> accesses,
		JourneyProfileResourcePolicy resourcePolicy,
		OracleLimits limits,
		int boardingSlackSeconds,
		Instant latestReadyAt
	) {
		JourneyRaptorQuery query = query(candidate,
			new JourneyRaptorQuery.DepartBetween(candidate.readyAt(), latestReadyAt));
		Map<Instant, List<JourneyProfileExactOracle.Candidate>> expectedByBreakpoint = departureExpectations(
			candidate, latestReadyAt, rides, accesses, limits, boardingSlackSeconds);
		return JourneyProfileMeasuredExecution.departureRow(regionId,
			JourneyProfileMeasuredExecution.measure(query, compiled.runtime(), resourcePolicy.profilePlanningLimits()),
			expectedByBreakpoint);
	}

	private static Map<String, Object> arriveByRow(
		String regionId,
		CompiledMeasurementInputs compiled,
		JourneyProfileMeasurementOd.DirectOdCandidate candidate,
		List<JourneyProfileExactOracle.Ride> rides,
		List<JourneyProfileExactOracle.Access> accesses,
		JourneyProfileResourcePolicy resourcePolicy,
		OracleLimits limits,
		int boardingSlackSeconds
	) {
		JourneyRaptorQuery query = query(candidate,
			new JourneyRaptorQuery.ArriveBy(candidate.readyAt(), candidate.arrivalAtDestination()));
		List<JourneyProfileExactOracle.Candidate> expected = new JourneyProfileExactOracle().solve(
			oracleQuery(candidate, candidate.readyAt(), candidate.arrivalAtDestination(), limits, boardingSlackSeconds), rides, accesses);
		return JourneyProfileMeasuredExecution.reverseRow(regionId,
			JourneyProfileMeasuredExecution.measure(query, compiled.runtime(), resourcePolicy.profilePlanningLimits()), expected);
	}

	private static Map<String, Object> lastConnectionRow(
		String regionId,
		CompiledMeasurementInputs compiled,
		JourneyProfileMeasurementOd.DirectOdCandidate candidate,
		List<JourneyProfileExactOracle.Access> accesses,
		JourneyProfileResourcePolicy resourcePolicy,
		OracleLimits limits,
		int boardingSlackSeconds,
		Instant terminalDeadline
	) {
		List<JourneyProfileExactOracle.Ride> rides = rawRides(compiled, List.of(candidate.serviceDate()), limits);
		Instant earliestReadyAt = candidate.serviceDate().atStartOfDay(ServiceDayResolver.ZONE).toInstant();
		List<JourneyProfileExactOracle.Candidate> expected = new JourneyProfileExactOracle().solve(
			oracleQuery(candidate, earliestReadyAt, terminalDeadline, limits, boardingSlackSeconds), rides, accesses);
		if (expected.isEmpty()) {
			throw unavailable("raw last-connection oracle found no path");
		}
		JourneyRaptorQuery query = query(candidate, new JourneyRaptorQuery.LastConnection(candidate.serviceDate()));
		return JourneyProfileMeasuredExecution.reverseRow(regionId,
			JourneyProfileMeasuredExecution.measure(query, compiled.runtime(), resourcePolicy.profilePlanningLimits()), expected);
	}

	private static Map<String, Object> cutoffRow(
		String regionId,
		CompiledMeasurementInputs compiled,
		JourneyProfileMeasurementOd.DirectOdCandidate candidate,
		List<JourneyProfileExactOracle.Ride> rides,
		List<JourneyProfileExactOracle.Access> accesses,
		JourneyProfileResourcePolicy resourcePolicy,
		OracleLimits limits,
		int boardingSlackSeconds
	) {
		Instant earliestReadyAt = cutoffReadyAt(candidate.readyAt(), compiled.identity().activeFromInstant());
		JourneyRaptorQuery query = query(candidate,
			new JourneyRaptorQuery.ArriveBy(earliestReadyAt, candidate.arrivalAtDestination()));
		List<JourneyProfileExactOracle.Candidate> expected = new JourneyProfileExactOracle().solve(
			oracleQuery(candidate, earliestReadyAt, candidate.arrivalAtDestination(), limits, boardingSlackSeconds), rides, accesses);
		return JourneyProfileMeasuredExecution.cutoffRow(regionId,
			JourneyProfileMeasuredExecution.measure(query, compiled.runtime(), resourcePolicy.profilePlanningLimits()), expected);
	}

	private static Map<String, Object> typedFailureRow(
		String regionId,
		CompiledMeasurementInputs compiled,
		JourneyProfileMeasurementOd.DirectOdCandidate candidate,
		List<JourneyProfileExactOracle.Ride> rides,
		List<JourneyProfileExactOracle.Access> accesses,
		JourneyProfileResourcePolicy resourcePolicy,
		OracleLimits limits,
		int boardingSlackSeconds,
		Instant earlierDeadline
	) {
		JourneyProfileExactOracle oracle = new JourneyProfileExactOracle();
		List<JourneyProfileExactOracle.Candidate> expected = oracle.solve(
			oracleQuery(candidate, candidate.readyAt(), earlierDeadline, limits, boardingSlackSeconds), rides, accesses);
		if (!expected.isEmpty()) {
			throw unavailable("raw oracle did not become empty before the feasible deadline");
		}
		JourneyRaptorQuery query = query(candidate,
			new JourneyRaptorQuery.ArriveBy(candidate.readyAt(), earlierDeadline));
		return JourneyProfileMeasuredExecution.deadlineFailureRow(regionId,
			JourneyProfileMeasuredExecution.measure(query, compiled.runtime(), resourcePolicy.profilePlanningLimits()), expected);
	}

	static JourneyRaptorQuery.DepartBetween profileWindow(Instant readyAt, Instant freshUntil, Duration maximumWindow) {
		if (maximumWindow == null || maximumWindow.isZero() || maximumWindow.isNegative()) {
			throw unavailable("positive profile window is required");
		}
		Instant latest = readyAt.plus(maximumWindow);
		if (!latest.isBefore(freshUntil)) latest = freshUntil.minusSeconds(1);
		if (!latest.isAfter(readyAt)) throw unavailable("candidate validity has no profile window");
		return new JourneyRaptorQuery.DepartBetween(readyAt, latest);
	}

	private static Instant typedFailureDeadline(
		JourneyProfileMeasurementOd.DirectOdCandidate candidate,
		List<JourneyProfileExactOracle.Ride> rides,
		List<JourneyProfileExactOracle.Access> accesses,
		OracleLimits limits,
		int boardingSlackSeconds
	) {
		List<JourneyProfileExactOracle.Candidate> feasible = new JourneyProfileExactOracle().solve(
			oracleQuery(candidate, candidate.readyAt(), candidate.arrivalAtDestination(), limits, boardingSlackSeconds), rides, accesses);
		JourneyProfileExactOracle.Candidate earliest = feasible.stream()
			.min(Comparator.comparing(JourneyProfileExactOracle.Candidate::arrivalAtDestination)
				.thenComparing(JourneyProfileExactOracle.Candidate::pathIdentity))
			.orElseThrow(() -> unavailable("raw feasible candidate is required for typed failure"));
		Instant deadline = earliest.arrivalAtDestination().minusSeconds(1);
		if (!deadline.isAfter(candidate.readyAt())) {
			throw unavailable("raw feasible candidate cannot produce an earlier ordered deadline");
		}
		return deadline;
	}

	private static JourneyProfileMeasurementOd.DirectOdCandidate crossCutoffCandidate(
		Scope scope,
		String regionId,
		List<JourneyProfileCandidateEvents.Event> events,
		List<JourneyProfileExactOracle.Access> accesses,
		Instant activeFrom,
		Instant freshUntil,
		int boardingSlackSeconds
	) {
		for (JourneyProfileCandidateEvents.Event event : events) {
			var selected = JourneyProfileMeasurementOd.findDirectOd(
				scope, regionId, List.of(event), accesses, activeFrom, freshUntil, boardingSlackSeconds);
			if (selected.isEmpty()) continue;
			var candidate = selected.orElseThrow();
			if (!cutoffBoundary(candidate.readyAt()).minusSeconds(1).isBefore(activeFrom)) return candidate;
		}
		throw unavailable("no valid cross-cutoff query candidate for region " + regionId);
	}

	private static List<JourneyProfileExactOracle.Ride> rawRides(
		CompiledMeasurementInputs compiled,
		List<LocalDate> serviceDates,
		OracleLimits limits
	) {
		var dates = new ArrayList<>(new LinkedHashSet<>(serviceDates));
		dates.sort(Comparator.naturalOrder());
		var rides = new ArrayList<JourneyProfileExactOracle.Ride>();
		for (LocalDate serviceDate : dates) {
			var dayRides = JourneyProfileScheduledOracleInputs.rides(
				compiled.sourceTimetable(), serviceDate, limits.maxRides());
			if ((long) rides.size() + dayRides.size() > limits.maxRides()) {
				throw unavailable("combined service dates exceed the oracle ride limit");
			}
			rides.addAll(dayRides);
		}
		return List.copyOf(rides);
	}

	static List<LocalDate> applicableServiceDates(Instant activeFrom, Instant freshUntil) {
		if (!activeFrom.isBefore(freshUntil)) throw unavailable("candidate validity must be ordered");
		long maximumServiceSeconds = com.easysubway.route.application.port.out.LoadRouteTimetablePort
			.SERVICE_DAY_SECONDS_LIMIT_EXCLUSIVE - 1L;
		LocalDate first = activeFrom.minusSeconds(maximumServiceSeconds).atZone(ServiceDayResolver.ZONE).toLocalDate();
		LocalDate last = freshUntil.minusNanos(1).atZone(ServiceDayResolver.ZONE).toLocalDate();
		return first.datesUntil(last.plusDays(1)).toList();
	}

	private static JourneyProfileExactOracle.Query oracleQuery(
		JourneyProfileMeasurementOd.DirectOdCandidate candidate,
		Instant earliestReadyAt,
		Instant deadline,
		OracleLimits limits,
		int boardingSlackSeconds
	) {
		return new JourneyProfileExactOracle.Query(candidate.originStationId(), candidate.destinationStationId(),
			earliestReadyAt, deadline, 0, boardingSlackSeconds, limits.maxWork(), () -> false);
	}

	private static JourneyRaptorQuery query(
		JourneyProfileMeasurementOd.DirectOdCandidate candidate,
		JourneyRaptorQuery.TemporalQuery temporalQuery
	) {
		return new JourneyRaptorQuery(requestId(candidate.regionId(), candidate.originStationId(),
			candidate.destinationStationId(), temporalQuery), candidate.originStationId(), candidate.destinationStationId(),
			temporalQuery, TIME_POLICY, WALKING_PACE, PROFILE, CONSTRAINT, 0, 1, () -> false);
	}

	static Instant cutoffReadyAt(Instant tripReadyAt, Instant activeFrom) {
		// 열차 운행이 아니라 질의 범위가 직전 서비스 날짜에서 시작해야 한다.
		Instant earliest = cutoffBoundary(tripReadyAt).minusSeconds(1);
		if (earliest.isBefore(activeFrom)) throw unavailable("cutoff query begins before candidate validity");
		return earliest;
	}

	private static Instant cutoffBoundary(Instant tripReadyAt) {
		return ServiceDayResolver.resolve(tripReadyAt).serviceDate()
			.atTime(LocalTime.parse(ServiceDayResolver.CUTOFF_LOCAL_TIME)).atZone(ServiceDayResolver.ZONE).toInstant();
	}

	static String requestId(String regionId, String origin, String destination, JourneyRaptorQuery.TemporalQuery temporal) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(
				String.join("\u0000", regionId, origin, destination, temporal.toString()).getBytes(StandardCharsets.UTF_8));
			BigInteger value = new BigInteger(1, java.util.Arrays.copyOf(digest, 16));
			String alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
			char[] id = new char[26];
			for (int index = id.length - 1; index >= 0; index--) {
				id[index] = alphabet.charAt(value.intValue() & 31);
				value = value.shiftRight(5);
			}
			return new String(id);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static Map<Instant, List<JourneyProfileExactOracle.Candidate>> departureExpectations(
		JourneyProfileMeasurementOd.DirectOdCandidate candidate, Instant latestReadyAt,
		List<JourneyProfileExactOracle.Ride> rides, List<JourneyProfileExactOracle.Access> accesses,
		OracleLimits limits, int boardingSlackSeconds
	) {
		var breakpoints = new java.util.TreeSet<Instant>();
		breakpoints.add(latestReadyAt);
		for (var ride : rides) {
			if (!ride.pickupAllowed() || !candidate.originStationId().equals(ride.fromStationId())) continue;
			for (var access : accesses) {
				if (!access.usable() || access.kind() != JourneyProfileExactOracle.AccessKind.ENTRY
					|| !ride.fromStationId().equals(access.fromStationId())
					|| !ride.fromStationId().equals(access.toStationId())
					|| !ride.fromLineId().equals(access.toLineId())) continue;
				Instant readyAt = ride.departureAt().minusSeconds(access.durationSeconds()).minusSeconds(boardingSlackSeconds);
				if (!readyAt.isBefore(candidate.readyAt()) && !readyAt.isAfter(latestReadyAt)) breakpoints.add(readyAt);
			}
		}
		var ordered = new LinkedHashMap<Instant, List<JourneyProfileExactOracle.Candidate>>();
		Instant deadline = terminalDeadline(candidate.destinationStationId(), rides, accesses);
		var oracle = new JourneyProfileExactOracle();
		for (Instant readyAt : breakpoints) {
			if (!deadline.isAfter(readyAt)) continue;
			var query = oracleQuery(candidate, readyAt, deadline, limits, boardingSlackSeconds);
			var expected = readyAt.equals(latestReadyAt) ? oracle.solvePoint(query, rides, accesses)
				: oracle.solveDepartureWindow(query, latestReadyAt.isAfter(deadline) ? deadline : latestReadyAt, rides, accesses);
			if (!expected.isEmpty()) ordered.put(readyAt, expected);
		}
		if (ordered.isEmpty()) throw unavailable("raw departure-window oracle has no feasible breakpoint");
		return Collections.unmodifiableMap(ordered);
	}

	private static Instant terminalDeadline(
		String destinationStationId,
		List<JourneyProfileExactOracle.Ride> rides,
		List<JourneyProfileExactOracle.Access> accesses
	) {
		Instant latest = null;
		for (JourneyProfileExactOracle.Ride ride : rides) {
			if (!ride.dropOffAllowed() || !destinationStationId.equals(ride.toStationId())) {
				continue;
			}
			for (JourneyProfileExactOracle.Access exit : accesses) {
				if (exit.kind() == JourneyProfileExactOracle.AccessKind.EXIT && exit.usable()
					&& destinationStationId.equals(exit.fromStationId()) && destinationStationId.equals(exit.toStationId())
					&& ride.toLineId().equals(exit.fromLineId())) {
					Instant arrivalAtDestination = ride.arrivalAt().plusSeconds(exit.durationSeconds());
					if (latest == null || arrivalAtDestination.isAfter(latest)) {
						latest = arrivalAtDestination;
					}
				}
			}
		}
		if (latest == null) {
			throw unavailable("actual service-day events have no verified exit terminal");
		}
		return latest;
	}

	private static Map<String, Object> corpusFact(
		CompiledMeasurementInputs compiled,
		Scope scope,
		List<String> regionIds,
		JourneyProfileResourcePolicy policy,
		OracleLimits limits,
		int boardingSlackSeconds,
		List<Map<String, Object>> regionFacts
	) {
		var value = new LinkedHashMap<String, Object>();
		value.put("schemaVersion", 1);
		value.put("queryClasses", List.of("POINT", "DEPARTURE_PROFILE", "ARRIVE_BY", "LAST_CONNECTION", "CUTOFF", "TYPED_FAILURE"));
		value.put("regionIds", List.copyOf(regionIds));
		value.put("scope", orderedMap(Map.of("targetVersion", scope.targetVersion(), "scopeSha256", scope.scopeSha256())));
		value.put("candidateValidity", orderedMap(Map.of(
			"activeFrom", compiled.identity().activeFromInstant().toString(),
			"freshUntil", compiled.identity().freshUntilInstant().toString())));
		value.put("runtime", orderedMap(Map.of("routeBundleSha256", compiled.runtime().routeBundleSha256(),
			"generation", compiled.runtime().generation())));
		value.put("initialCase", orderedMap(Map.of(
			"walkingPace", WALKING_PACE.name(), "walkingSpeedMetersPerHour", WALKING_PACE.speedMetersPerHour(),
			"mobilityProfile", PROFILE.name(), "constraintMode", CONSTRAINT.name(), "timePolicy", TIME_POLICY.name())));
		value.put("oracleLimits", orderedMap(Map.of("maxWork", Long.toString(limits.maxWork()),
			"maxRides", Integer.toString(limits.maxRides()), "maxAccesses", Integer.toString(limits.maxAccesses()),
			"boardingSlackSeconds", Integer.toString(boardingSlackSeconds))));
		value.put("resourcePolicyIdentity", orderedMap(Map.of(
			"resourcePolicyId", policy.identity().resourcePolicyId(), "semanticVersion", policy.identity().semanticVersion(),
			"resourcePolicySha256", policy.identity().resourcePolicySha256())));
		value.put("regions", List.copyOf(regionFacts));
		return Collections.unmodifiableMap(value);
	}

	private static Map<String, Object> regionFact(
		String regionId,
		JourneyProfileMeasurementOd.DirectOdCandidate direct,
		JourneyProfileMeasurementOd.DirectOdCandidate cutoff,
		Instant departureLatestReadyAt,
		Instant lastConnectionTerminal,
		Instant typedFailureDeadline,
		Instant pointTerminal,
		Instant cutoffEarliest
	) {
		var value = new LinkedHashMap<String, Object>();
		value.put("regionId", regionId);
		value.put("standardOd", candidateFact(direct));
		value.put("cutoffOd", candidateFact(cutoff));
		value.put("queries", List.of(
			queryFact("POINT", direct.readyAt(), pointTerminal),
			queryFact("DEPARTURE_PROFILE", direct.readyAt(), departureLatestReadyAt),
			queryFact("ARRIVE_BY", direct.readyAt(), direct.arrivalAtDestination()),
			orderedMap(Map.of("queryClass", "LAST_CONNECTION", "serviceDate", direct.serviceDate().toString(),
				"rawTerminalDeadline", lastConnectionTerminal.toString())),
			queryFact("CUTOFF", cutoffEarliest, cutoff.arrivalAtDestination()),
			queryFact("TYPED_FAILURE", direct.readyAt(), typedFailureDeadline)));
		return Collections.unmodifiableMap(value);
	}

	private static Map<String, Object> queryFact(String queryClass, Instant earliestReadyAt, Instant deadline) {
		return orderedMap(Map.of("queryClass", queryClass, "earliestReadyAt", earliestReadyAt.toString(),
			"deadlineOrLatestReadyAt", deadline.toString()));
	}

	private static Map<String, Object> candidateFact(JourneyProfileMeasurementOd.DirectOdCandidate candidate) {
		var value = new LinkedHashMap<String, Object>();
		value.put("lineId", candidate.routeLineId());
		value.put("serviceDate", candidate.serviceDate().toString());
		value.put("tripId", candidate.tripId());
		value.put("originStationId", candidate.originStationId());
		value.put("destinationStationId", candidate.destinationStationId());
		value.put("readyAt", candidate.readyAt().toString());
		value.put("arrivalAtDestination", candidate.arrivalAtDestination().toString());
		value.put("entryAccessId", candidate.entryAccessId());
		value.put("exitAccessId", candidate.exitAccessId());
		return Collections.unmodifiableMap(value);
	}

	private static Map<String, Object> orderedMap(Map<String, Object> values) {
		var ordered = new LinkedHashMap<String, Object>();
		values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
		return Collections.unmodifiableMap(ordered);
	}

	private static IllegalArgumentException unavailable(String reason) {
		return new IllegalArgumentException("full measurement corpus is unavailable: " + reason);
	}

	public record OracleLimits(long maxWork, int maxRides, int maxAccesses) {
		public OracleLimits {
			if (maxWork < 1 || maxRides < 1 || maxAccesses < 1) {
				throw new IllegalArgumentException("oracle limits must be positive");
			}
		}
	}

	public record CorpusResult(List<Map<String, Object>> rows, Map<String, Object> corpus) {
		public CorpusResult {
			rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
			corpus = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(corpus, "corpus")));
		}
	}
}
