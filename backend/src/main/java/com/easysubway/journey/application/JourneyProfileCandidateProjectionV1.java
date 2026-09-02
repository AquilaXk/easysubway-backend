package com.easysubway.journey.application;

import com.easysubway.journey.application.JourneyFrontierPolicyV1.FeasibleCandidate;
import com.easysubway.journey.application.JourneyFrontierPolicyV1.ObjectiveTag;
import com.easysubway.journey.application.JourneyFrontierPolicyV1.SelectedLabel;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Closed temporal-profile projection from native planner facts to Profile V1 candidates.
 *
 * <p>Every candidate binds a native physical itinerary to its planned readiness fact, and every
 * published segment and summary reference resolves against one immutable inventory.</p>
 */
public final class JourneyProfileCandidateProjectionV1 {

	private static final Set<ObjectiveTag> REQUIRED_TAGS = EnumSet.allOf(ObjectiveTag.class);
	private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator.comparing(Candidate::candidateId);

	private JourneyProfileCandidateProjectionV1() {
	}

	public static Outcome projectDepartureWindow(
		JourneyRaptorQuery query,
		JourneyProfileRaptorPort.DepartureWindowPlan plan,
		int maxDestinationProfileLabels
	) {
		JourneyRaptorQuery requiredQuery = Objects.requireNonNull(query, "query");
		JourneyProfileRaptorPort.DepartureWindowPlan requiredPlan = Objects.requireNonNull(plan, "plan");
		if (!(requiredQuery.temporalQuery() instanceof JourneyRaptorQuery.DepartBetween departureWindow)
			|| !departureWindow.equals(requiredPlan.temporalQuery())) {
			throw new IllegalArgumentException("query temporal facts must equal DepartureWindowPlan.temporalQuery");
		}
		if (maxDestinationProfileLabels <= 0) {
			throw new IllegalArgumentException("maxDestinationProfileLabels must be positive");
		}

		Map<String, RawCandidate> candidatesById = new LinkedHashMap<>();
		Map<String, EnumSet<ObjectiveTag>> tagsByCandidateId = new LinkedHashMap<>();
		List<JourneyProfileSegmentPolicyV1.Breakpoint> breakpoints = new ArrayList<>();
		for (JourneyProfileRaptorPort.DeparturePoint point : requiredPlan.points()) {
			List<RawCandidate> pointCandidates = new ArrayList<>();
			for (JourneyProfileRaptorPort.Itinerary itinerary : point.itineraries()) {
				RawCandidate candidate = rawCandidate(
					requiredQuery, point.serviceDate(), point.readyAt(), itinerary);
				register(candidatesById, candidate);
				pointCandidates.add(candidate);
			}
			JourneyFrontierPolicyV1.Outcome outcome = JourneyFrontierPolicyV1.evaluate(
				pointCandidates.stream().map(RawCandidate::feasibleCandidate).toList(), REQUIRED_TAGS,
				maxDestinationProfileLabels);
			if (outcome instanceof JourneyFrontierPolicyV1.CapacityExceeded exceeded) {
				return new CapacityExceeded(exceeded.metrics(), exceeded.requiredRepresentativeCount(),
					exceeded.maxDestinationProfileLabels());
			}
			JourneyFrontierPolicyV1.Success selected = (JourneyFrontierPolicyV1.Success) outcome;
			mergeTags(tagsByCandidateId, selected.labels());
			breakpoints.add(new JourneyProfileSegmentPolicyV1.Breakpoint(point.readyAt(),
				selected.labels().stream().map(label -> label.candidate().journeyId()).toList()));
		}

		JourneyFrontierPolicyV1.Outcome globalOutcome = JourneyFrontierPolicyV1.evaluate(
			candidatesById.values().stream().map(RawCandidate::feasibleCandidate).toList(), REQUIRED_TAGS,
			maxDestinationProfileLabels);
		if (globalOutcome instanceof JourneyFrontierPolicyV1.CapacityExceeded exceeded) {
			return new CapacityExceeded(exceeded.metrics(), exceeded.requiredRepresentativeCount(),
				exceeded.maxDestinationProfileLabels());
		}
		JourneyFrontierPolicyV1.Success global = (JourneyFrontierPolicyV1.Success) globalOutcome;
		mergeTags(tagsByCandidateId, global.labels());

		JourneyProfileSegmentPolicyV1.Result compressed = JourneyProfileSegmentPolicyV1.compress(
			departureWindow, breakpoints);
		if (compressed.allEmpty()) return new NoService(compressed.segments());

		List<Candidate> inventory = tagsByCandidateId.entrySet().stream()
			.map(entry -> candidatesById.get(entry.getKey()).project(entry.getValue()))
			.sorted(CANDIDATE_ORDER)
			.toList();
		JourneyProfileSummaryPolicyV1.Departure summary = (JourneyProfileSummaryPolicyV1.Departure)
			JourneyProfileSummaryPolicyV1.select(requiredQuery, global, requiredQuery.alternativeCount());
		requireResolvableReferences(inventory, compressed.segments(), summary);
		return new Projected(new DepartureWindowProjection(departureWindow, inventory, compressed.segments(), summary));
	}

	public static Outcome projectArriveBy(
		JourneyRaptorQuery query,
		JourneyProfileRaptorPort.ArriveByPlan plan,
		int maxDestinationProfileLabels
	) {
		JourneyRaptorQuery requiredQuery = Objects.requireNonNull(query, "query");
		JourneyProfileRaptorPort.ArriveByPlan requiredPlan = Objects.requireNonNull(plan, "plan");
		if (!(requiredQuery.temporalQuery() instanceof JourneyRaptorQuery.ArriveBy arriveBy)
			|| !arriveBy.equals(requiredPlan.temporalQuery())) {
			throw new IllegalArgumentException("query temporal facts must equal ArriveByPlan.temporalQuery");
		}
		ReverseProjection reverse = projectReverse(requiredQuery, requireFound(requiredPlan.result()),
			maxDestinationProfileLabels, itinerary -> {
				if (itinerary.plannedReadyAt().isBefore(arriveBy.earliestReadyAt())
					|| itinerary.plannedArrivalAtDestination().isAfter(arriveBy.arrivalDeadline())) {
					throw new IllegalArgumentException("reverse itinerary falls outside arrive-by facts");
				}
			});
		if (reverse instanceof ReverseCapacityExceeded exceeded) return exceeded.outcome();
		ReverseSuccess success = (ReverseSuccess) reverse;
		JourneyProfileSummaryPolicyV1.ArriveBy summary = (JourneyProfileSummaryPolicyV1.ArriveBy)
			JourneyProfileSummaryPolicyV1.select(requiredQuery, success.frontier(), requiredQuery.alternativeCount());
		requireResolvableReferences(success.inventory(), summary);
		return new ArriveByProjected(new ArriveByProjection(arriveBy, success.inventory(), summary));
	}

	public static Outcome projectLastConnection(
		JourneyRaptorQuery query,
		JourneyProfileRaptorPort.LastConnectionPlan plan,
		int maxDestinationProfileLabels
	) {
		JourneyRaptorQuery requiredQuery = Objects.requireNonNull(query, "query");
		JourneyProfileRaptorPort.LastConnectionPlan requiredPlan = Objects.requireNonNull(plan, "plan");
		if (!(requiredQuery.temporalQuery() instanceof JourneyRaptorQuery.LastConnection lastConnection)
			|| !lastConnection.equals(requiredPlan.temporalQuery())) {
			throw new IllegalArgumentException("query temporal facts must equal LastConnectionPlan.temporalQuery");
		}
		Instant terminalArrival = Objects.requireNonNull(requiredPlan.terminalArrivalAtDestination(),
			"last-connection found plan requires terminalArrivalAtDestination");
		ReverseProjection reverse = projectReverse(requiredQuery, requireFound(requiredPlan.result()),
			maxDestinationProfileLabels, itinerary -> {
				if (!itinerary.serviceDate().equals(lastConnection.serviceDate())
					|| itinerary.plannedArrivalAtDestination().isAfter(terminalArrival)) {
					throw new IllegalArgumentException("reverse itinerary falls outside last-connection service-day or terminal facts");
				}
			});
		if (reverse instanceof ReverseCapacityExceeded exceeded) return exceeded.outcome();
		ReverseSuccess success = (ReverseSuccess) reverse;
		JourneyProfileSummaryPolicyV1.LastConnection summary = (JourneyProfileSummaryPolicyV1.LastConnection)
			JourneyProfileSummaryPolicyV1.select(requiredQuery, success.frontier(), requiredQuery.alternativeCount());
		requireResolvableReferences(success.inventory(), summary);
		return new LastConnectionProjected(new LastConnectionProjection(lastConnection, success.inventory(), summary,
			terminalArrival));
	}

	private static ReverseProjection projectReverse(
		JourneyRaptorQuery query,
		List<JourneyProfileRaptorPort.Itinerary> itineraries,
		int maxDestinationProfileLabels,
		Consumer<JourneyProfileRaptorPort.Itinerary> validateItinerary
	) {
		if (maxDestinationProfileLabels <= 0) {
			throw new IllegalArgumentException("maxDestinationProfileLabels must be positive");
		}
		Map<String, RawCandidate> candidatesById = new LinkedHashMap<>();
		for (JourneyProfileRaptorPort.Itinerary itinerary : itineraries) {
			validateItinerary.accept(itinerary);
			register(candidatesById, rawCandidate(query, itinerary.serviceDate(), itinerary.plannedReadyAt(), itinerary));
		}
		JourneyFrontierPolicyV1.Outcome outcome = JourneyFrontierPolicyV1.evaluate(
			candidatesById.values().stream().map(RawCandidate::feasibleCandidate).toList(), REQUIRED_TAGS,
			maxDestinationProfileLabels);
		if (outcome instanceof JourneyFrontierPolicyV1.CapacityExceeded exceeded) {
			return new ReverseCapacityExceeded(new CapacityExceeded(exceeded.metrics(), exceeded.requiredRepresentativeCount(),
				exceeded.maxDestinationProfileLabels()));
		}
		JourneyFrontierPolicyV1.Success frontier = (JourneyFrontierPolicyV1.Success) outcome;
		Map<String, EnumSet<ObjectiveTag>> tagsByCandidateId = new LinkedHashMap<>();
		mergeTags(tagsByCandidateId, frontier.labels());
		List<Candidate> inventory = tagsByCandidateId.entrySet().stream()
			.map(entry -> candidatesById.get(entry.getKey()).project(entry.getValue()))
			.sorted(CANDIDATE_ORDER)
			.toList();
		return new ReverseSuccess(inventory, frontier);
	}

	private static List<JourneyProfileRaptorPort.Itinerary> requireFound(
		JourneyProfileRaptorPort.ReversePlan result
	) {
		if (!(result instanceof JourneyProfileRaptorPort.ReversePlan.Found found)) {
			throw new IllegalArgumentException("reverse projection requires ReversePlan.Found");
		}
		return found.itineraries();
	}

	private static RawCandidate rawCandidate(
		JourneyRaptorQuery query,
		LocalDate serviceDate,
		Instant pointReadyAt,
		JourneyProfileRaptorPort.Itinerary itinerary
	) {
		JourneyProfileRaptorPort.Itinerary requiredItinerary = Objects.requireNonNull(itinerary, "itinerary");
		if (!requiredItinerary.serviceDate().equals(serviceDate)
			|| !ServiceDayResolver.resolve(pointReadyAt).serviceDate().equals(serviceDate)) {
			throw new IllegalArgumentException("profile point, ready instant, and itinerary service day must match");
		}
		String physicalItineraryId = JourneyProfileCandidateIdentity.physicalItineraryId(
			query.originStationId(), query.destinationStationId(), requiredItinerary);
		String candidateId = JourneyProfileCandidateIdentity.candidateId(physicalItineraryId, pointReadyAt);
		JourneyProfileRaptorPort.RideLeg firstRide = null;
		JourneyProfileRaptorPort.RideLeg lastRide = null;
		for (JourneyProfileRaptorPort.Leg leg : requiredItinerary.legs()) {
			if (leg instanceof JourneyProfileRaptorPort.RideLeg ride) {
				if (firstRide == null) firstRide = ride;
				lastRide = ride;
			}
		}
		if (firstRide == null || lastRide == null) {
			throw new IllegalArgumentException("native itinerary must contain a ride leg");
		}
		validateTimeline(pointReadyAt, requiredItinerary.plannedReadyAt(), firstRide.plannedDepartureTime(),
			lastRide.plannedArrivalTime(), requiredItinerary.plannedArrivalAtDestination());
		var metrics = requiredItinerary.metrics();
		return new RawCandidate(candidateId, physicalItineraryId, pointReadyAt, requiredItinerary.plannedReadyAt(),
			firstRide.plannedDepartureTime(), lastRide.plannedArrivalTime(),
			requiredItinerary.plannedArrivalAtDestination(), requiredItinerary,
			new FeasibleCandidate(candidateId, pointReadyAt, requiredItinerary.plannedArrivalAtDestination(),
				metrics.transfersUsed(), metrics.accessMovementSeconds(), metrics.accessDistanceMeters(),
				metrics.accessibilityBurden(), metrics.connectionSlack()));
	}

	private static void register(Map<String, RawCandidate> candidatesById, RawCandidate candidate) {
		RawCandidate existing = candidatesById.putIfAbsent(candidate.candidateId(), candidate);
		if (existing != null && !existing.equals(candidate)) {
			throw new IllegalArgumentException("conflicting duplicate candidateId: " + candidate.candidateId());
		}
	}

	private static void mergeTags(
		Map<String, EnumSet<ObjectiveTag>> tagsByCandidateId,
		List<SelectedLabel> labels
	) {
		for (SelectedLabel label : labels) {
			tagsByCandidateId.computeIfAbsent(label.candidate().journeyId(),
				ignored -> EnumSet.noneOf(ObjectiveTag.class)).addAll(label.objectiveTags());
		}
	}

	private static void requireResolvableReferences(
		List<Candidate> inventory,
		List<JourneyProfileSegmentPolicyV1.Segment> segments,
		JourneyProfileSummaryPolicyV1.Departure summary
	) {
		Map<String, Candidate> byCandidateId = new LinkedHashMap<>();
		for (Candidate candidate : inventory) {
			if (byCandidateId.putIfAbsent(candidate.candidateId(), candidate) != null) {
				throw new IllegalArgumentException("duplicate inventory candidateId: " + candidate.candidateId());
			}
		}
		for (JourneyProfileSegmentPolicyV1.Segment segment : segments) {
			for (String candidateId : segment.journeyIds()) requireResolved(byCandidateId, candidateId);
		}
		requireResolved(byCandidateId, summary.earliestArrivalJourneyId());
		requireResolved(byCandidateId, summary.latestDepartureJourneyId());
		requireResolvableReferences(byCandidateId, summary);
	}

	private static void requireResolvableReferences(
		List<Candidate> inventory,
		JourneyProfileSummaryPolicyV1.Summary summary
	) {
		Map<String, Candidate> byCandidateId = new LinkedHashMap<>();
		for (Candidate candidate : inventory) {
			if (byCandidateId.putIfAbsent(candidate.candidateId(), candidate) != null) {
				throw new IllegalArgumentException("duplicate inventory candidateId: " + candidate.candidateId());
			}
		}
		requireResolvableReferences(byCandidateId, summary);
	}

	private static void requireResolvableReferences(
		Map<String, Candidate> inventory,
		JourneyProfileSummaryPolicyV1.Summary summary
	) {
		switch (summary) {
			case JourneyProfileSummaryPolicyV1.Departure departure -> {
				requireResolved(inventory, departure.earliestArrivalJourneyId());
				requireResolved(inventory, departure.latestDepartureJourneyId());
			}
			case JourneyProfileSummaryPolicyV1.ArriveBy arriveBy ->
				requireResolved(inventory, arriveBy.primaryJourneyId());
			case JourneyProfileSummaryPolicyV1.LastConnection lastConnection -> {
				requireResolved(inventory, lastConnection.lastConnectionJourneyId());
				for (String candidateId : lastConnection.saferAlternativeJourneyIds()) {
					requireResolved(inventory, candidateId);
				}
			}
		}
		for (String candidateId : summary.recommendedJourneyIds()) requireResolved(inventory, candidateId);
	}

	private static void requireResolved(Map<String, Candidate> inventory, String candidateId) {
		if (!inventory.containsKey(candidateId)) {
			throw new IllegalArgumentException("published candidate reference is unresolved: " + candidateId);
		}
	}

	public sealed interface Outcome permits Projected, ArriveByProjected, LastConnectionProjected,
		CapacityExceeded, NoService {
	}

	public record Projected(DepartureWindowProjection projection) implements Outcome {
		public Projected {
			projection = Objects.requireNonNull(projection, "projection");
		}
	}

	public record ArriveByProjected(ArriveByProjection projection) implements Outcome {
		public ArriveByProjected {
			projection = Objects.requireNonNull(projection, "projection");
		}
	}

	public record LastConnectionProjected(LastConnectionProjection projection) implements Outcome {
		public LastConnectionProjected {
			projection = Objects.requireNonNull(projection, "projection");
		}
	}

	/** Capacity failure intentionally exposes no candidate inventory or profile projection. */
	public record CapacityExceeded(
		JourneyFrontierPolicyV1.Metrics metrics,
		int observed,
		int max
	) implements Outcome {
		public CapacityExceeded {
			metrics = Objects.requireNonNull(metrics, "metrics");
			if (metrics.capacityState() != JourneyFrontierPolicyV1.CapacityState.EXCEEDED
				|| observed <= max || max <= 0) {
				throw new IllegalArgumentException("capacity exceedance facts are invalid");
			}
		}
	}

	public record NoService(List<JourneyProfileSegmentPolicyV1.Segment> segments) implements Outcome {
		public NoService {
			segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
			if (segments.isEmpty() || segments.stream().anyMatch(segment -> !segment.journeyIds().isEmpty())) {
				throw new IllegalArgumentException("no-service projection requires only empty segments");
			}
		}
	}

	public record DepartureWindowProjection(
		JourneyRaptorQuery.DepartBetween temporalQuery,
		List<Candidate> candidates,
		List<JourneyProfileSegmentPolicyV1.Segment> segments,
		JourneyProfileSummaryPolicyV1.Departure summary
	) {
		public DepartureWindowProjection {
			temporalQuery = Objects.requireNonNull(temporalQuery, "temporalQuery");
			candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
			segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
			summary = Objects.requireNonNull(summary, "summary");
			if (candidates.isEmpty() || segments.isEmpty()) {
				throw new IllegalArgumentException("projected departure window must not be empty");
			}
		}
	}

	public record ArriveByProjection(
		JourneyRaptorQuery.ArriveBy temporalQuery,
		List<Candidate> candidates,
		JourneyProfileSummaryPolicyV1.ArriveBy summary
	) {
		public ArriveByProjection {
			temporalQuery = Objects.requireNonNull(temporalQuery, "temporalQuery");
			candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
			summary = Objects.requireNonNull(summary, "summary");
			if (candidates.isEmpty()) throw new IllegalArgumentException("arrive-by projection must not be empty");
		}
	}

	public record LastConnectionProjection(
		JourneyRaptorQuery.LastConnection temporalQuery,
		List<Candidate> candidates,
		JourneyProfileSummaryPolicyV1.LastConnection summary,
		Instant terminalArrivalAtDestination
	) {
		public LastConnectionProjection {
			temporalQuery = Objects.requireNonNull(temporalQuery, "temporalQuery");
			candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
			summary = Objects.requireNonNull(summary, "summary");
			terminalArrivalAtDestination = Objects.requireNonNull(terminalArrivalAtDestination,
				"terminalArrivalAtDestination");
			if (candidates.isEmpty()) throw new IllegalArgumentException("last-connection projection must not be empty");
		}
	}

	public record Candidate(
		String candidateId,
		String physicalItineraryId,
		Instant readyAt,
		Instant journeyStartTime,
		Instant firstBoardingTime,
		Instant finalPlatformArrivalTime,
		Instant arrivalAtDestination,
		List<ObjectiveTag> objectiveTags,
		JourneyProfileRaptorPort.Itinerary itinerary
	) {
		public Candidate {
			candidateId = requireText(candidateId, "candidateId");
			physicalItineraryId = requireText(physicalItineraryId, "physicalItineraryId");
			readyAt = Objects.requireNonNull(readyAt, "readyAt");
			journeyStartTime = Objects.requireNonNull(journeyStartTime, "journeyStartTime");
			firstBoardingTime = Objects.requireNonNull(firstBoardingTime, "firstBoardingTime");
			finalPlatformArrivalTime = Objects.requireNonNull(finalPlatformArrivalTime, "finalPlatformArrivalTime");
			arrivalAtDestination = Objects.requireNonNull(arrivalAtDestination, "arrivalAtDestination");
			validateTimeline(readyAt, journeyStartTime, firstBoardingTime, finalPlatformArrivalTime,
				arrivalAtDestination);
			objectiveTags = List.copyOf(Objects.requireNonNull(objectiveTags, "objectiveTags"));
			if (objectiveTags.isEmpty() || EnumSet.copyOf(objectiveTags).size() != objectiveTags.size()) {
				throw new IllegalArgumentException("objectiveTags must be nonempty and unique");
			}
			itinerary = Objects.requireNonNull(itinerary, "itinerary");
		}
	}

	private sealed interface ReverseProjection permits ReverseSuccess, ReverseCapacityExceeded {
	}

	private record ReverseSuccess(
		List<Candidate> inventory,
		JourneyFrontierPolicyV1.Success frontier
	) implements ReverseProjection {
	}

	private record ReverseCapacityExceeded(CapacityExceeded outcome) implements ReverseProjection {
	}

	private record RawCandidate(
		String candidateId,
		String physicalItineraryId,
		Instant readyAt,
		Instant journeyStartTime,
		Instant firstBoardingTime,
		Instant finalPlatformArrivalTime,
		Instant arrivalAtDestination,
		JourneyProfileRaptorPort.Itinerary itinerary,
		FeasibleCandidate feasibleCandidate
	) {
		private Candidate project(EnumSet<ObjectiveTag> tags) {
			return new Candidate(candidateId, physicalItineraryId, readyAt, journeyStartTime, firstBoardingTime,
				finalPlatformArrivalTime, arrivalAtDestination, List.copyOf(tags), itinerary);
		}
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}

	private static void validateTimeline(
		Instant readyAt,
		Instant journeyStartTime,
		Instant firstBoardingTime,
		Instant finalPlatformArrivalTime,
		Instant arrivalAtDestination
	) {
		if (!readyAt.equals(journeyStartTime)) {
			throw new IllegalArgumentException("readyAt must equal journeyStartTime");
		}
		if (firstBoardingTime.isBefore(journeyStartTime)
			|| finalPlatformArrivalTime.isBefore(firstBoardingTime)
			|| arrivalAtDestination.isBefore(finalPlatformArrivalTime)) {
			throw new IllegalArgumentException("candidate journey times must be ordered");
		}
	}
}
