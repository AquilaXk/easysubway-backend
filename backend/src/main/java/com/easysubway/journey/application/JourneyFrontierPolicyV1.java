package com.easysubway.journey.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Unbounded semantic frontier for Journey profile candidates.
 *
 * <p>The caller owns the destination-label capacity. This policy deliberately has no default
 * capacity, quota, or deadline: a result that cannot contain every required representative is a
 * typed capacity failure rather than a truncated success.</p>
 */
public final class JourneyFrontierPolicyV1 {

	private static final Identity IDENTITY = new Identity("FRONTIER_POLICY_V1", "1.0.0");
	private static final Comparator<FeasibleCandidate> CANONICAL_ORDER = Comparator
		.comparing(FeasibleCandidate::journeyId)
		.thenComparing(FeasibleCandidate::departure)
		.thenComparing(FeasibleCandidate::arrivalAtDestination)
		.thenComparingLong(FeasibleCandidate::transfers)
		.thenComparingLong(FeasibleCandidate::walkingSeconds)
		.thenComparingLong(FeasibleCandidate::walkingDistanceMeters)
		.thenComparingLong(FeasibleCandidate::accessibilityBurden)
		.thenComparingLong(FeasibleCandidate::minimumConnectionSlackSeconds);

	private JourneyFrontierPolicyV1() {
	}

	public static Identity identity() {
		return IDENTITY;
	}

	/**
	 * Selects exactly the required objective representatives from the unbounded Pareto frontier.
	 * Exact duplicate journey records are counted once; conflicting records for one journey id are
	 * rejected because a frontier cannot safely choose between inconsistent journey facts.
	 */
	public static Outcome evaluate(
		List<FeasibleCandidate> generatedCandidates,
		Set<ObjectiveTag> requiredObjectiveTags,
		int maxDestinationProfileLabels
	) {
		Objects.requireNonNull(generatedCandidates, "generatedCandidates");
		Objects.requireNonNull(requiredObjectiveTags, "requiredObjectiveTags");
		if (requiredObjectiveTags.isEmpty()) {
			throw new IllegalArgumentException("requiredObjectiveTags must not be empty");
		}
		if (maxDestinationProfileLabels <= 0) {
			throw new IllegalArgumentException("maxDestinationProfileLabels must be positive");
		}

		Map<String, FeasibleCandidate> byJourneyId = new LinkedHashMap<>();
		long deduplicated = 0;
		for (FeasibleCandidate candidate : generatedCandidates) {
			FeasibleCandidate requiredCandidate = Objects.requireNonNull(candidate, "generatedCandidates contains null");
			FeasibleCandidate existing = byJourneyId.putIfAbsent(requiredCandidate.journeyId(), requiredCandidate);
			if (existing == null) continue;
			if (!existing.equals(requiredCandidate)) {
				throw new IllegalArgumentException("conflicting duplicate journeyId: " + requiredCandidate.journeyId());
			}
			deduplicated += 1;
		}

		List<FeasibleCandidate> uniqueCandidates = List.copyOf(byJourneyId.values());
		List<FeasibleCandidate> frontier = new ArrayList<>();
		long dominated = 0;
		for (FeasibleCandidate candidate : uniqueCandidates) {
			boolean isDominated = uniqueCandidates.stream()
				.anyMatch(other -> other != candidate && dominates(other, candidate));
			if (isDominated) {
				dominated += 1;
			} else {
				frontier.add(candidate);
			}
		}
		frontier.sort(CANONICAL_ORDER);

		EnumMap<ObjectiveTag, FeasibleCandidate> representatives = new EnumMap<>(ObjectiveTag.class);
		long representativeReplacements = 0;
		for (FeasibleCandidate candidate : frontier) {
			for (ObjectiveTag tag : ObjectiveTag.values()) {
				if (!requiredObjectiveTags.contains(tag)) continue;
				FeasibleCandidate current = representatives.get(tag);
				if (current == null || compareFor(tag, candidate, current) < 0) {
					if (current != null) representativeReplacements += 1;
					representatives.put(tag, candidate);
				}
			}
		}

		Map<FeasibleCandidate, EnumSet<ObjectiveTag>> tagsByRepresentative = new LinkedHashMap<>();
		for (ObjectiveTag tag : ObjectiveTag.values()) {
			if (!requiredObjectiveTags.contains(tag)) continue;
			FeasibleCandidate representative = representatives.get(tag);
			if (representative != null) {
				tagsByRepresentative.computeIfAbsent(representative, ignored -> EnumSet.noneOf(ObjectiveTag.class)).add(tag);
			}
		}
		int representativeCount = tagsByRepresentative.size();
		CapacityState capacityState = representativeCount > maxDestinationProfileLabels
			? CapacityState.EXCEEDED : CapacityState.WITHIN_CAPACITY;
		long boundPruned = capacityState == CapacityState.EXCEEDED
			? representativeCount - (long) maxDestinationProfileLabels : 0;
		Metrics metrics = new Metrics(
			generatedCandidates.size(), frontier.size(), dominated, deduplicated, boundPruned,
			representativeReplacements,
			representativeCount >= maxDestinationProfileLabels ? 1 : 0,
			frontier.size(), capacityState);
		if (capacityState == CapacityState.EXCEEDED) {
			return new CapacityExceeded(metrics, representativeCount, maxDestinationProfileLabels);
		}

		List<SelectedLabel> labels = tagsByRepresentative.entrySet().stream()
			.sorted(Map.Entry.comparingByKey(CANONICAL_ORDER))
			.map(entry -> new SelectedLabel(entry.getKey(), List.copyOf(entry.getValue())))
			.toList();
		return new Success(labels, metrics);
	}

	private static boolean dominates(FeasibleCandidate left, FeasibleCandidate right) {
		return !left.departure().isBefore(right.departure())
			&& !left.arrivalAtDestination().isAfter(right.arrivalAtDestination())
			&& left.transfers() <= right.transfers()
			&& left.walkingSeconds() <= right.walkingSeconds()
			&& left.walkingDistanceMeters() <= right.walkingDistanceMeters()
			&& left.accessibilityBurden() <= right.accessibilityBurden()
			&& left.minimumConnectionSlackSeconds() >= right.minimumConnectionSlackSeconds()
			&& (left.departure().isAfter(right.departure())
				|| left.arrivalAtDestination().isBefore(right.arrivalAtDestination())
				|| left.transfers() < right.transfers()
				|| left.walkingSeconds() < right.walkingSeconds()
				|| left.walkingDistanceMeters() < right.walkingDistanceMeters()
				|| left.accessibilityBurden() < right.accessibilityBurden()
				|| left.minimumConnectionSlackSeconds() > right.minimumConnectionSlackSeconds());
	}

	private static int compareFor(ObjectiveTag tag, FeasibleCandidate left, FeasibleCandidate right) {
		int comparison = switch (tag) {
			case FASTEST_ARRIVAL -> left.arrivalAtDestination().compareTo(right.arrivalAtDestination());
			case LATEST_DEPARTURE -> right.departure().compareTo(left.departure());
			case FEWEST_TRANSFERS -> Long.compare(left.transfers(), right.transfers());
			case LOWEST_WALKING_BURDEN -> compareWalkingBurden(left, right);
			case BEST_ACCESSIBILITY -> Long.compare(left.accessibilityBurden(), right.accessibilityBurden());
			case SAFEST_CONNECTION -> Long.compare(
				right.minimumConnectionSlackSeconds(), left.minimumConnectionSlackSeconds());
		};
		return comparison != 0 ? comparison : CANONICAL_ORDER.compare(left, right);
	}

	private static int compareWalkingBurden(FeasibleCandidate left, FeasibleCandidate right) {
		int comparison = Long.compare(left.walkingSeconds(), right.walkingSeconds());
		return comparison != 0 ? comparison
			: Long.compare(left.walkingDistanceMeters(), right.walkingDistanceMeters());
	}

	public enum ObjectiveTag {
		FASTEST_ARRIVAL,
		LATEST_DEPARTURE,
		FEWEST_TRANSFERS,
		LOWEST_WALKING_BURDEN,
		BEST_ACCESSIBILITY,
		SAFEST_CONNECTION
	}

	public enum CapacityState {
		WITHIN_CAPACITY,
		EXCEEDED
	}

	public record Identity(String frontierPolicyId, String semanticVersion) {
		public Identity {
			frontierPolicyId = requireText(frontierPolicyId, "frontierPolicyId");
			semanticVersion = requireText(semanticVersion, "semanticVersion");
		}
	}

	/** A candidate that passed all hard feasibility checks before frontier evaluation. */
	public record FeasibleCandidate(
		String journeyId,
		Instant departure,
		Instant arrivalAtDestination,
		long transfers,
		long walkingSeconds,
		long walkingDistanceMeters,
		long accessibilityBurden,
		long minimumConnectionSlackSeconds
	) {
		public FeasibleCandidate {
			journeyId = requireText(journeyId, "journeyId");
			departure = Objects.requireNonNull(departure, "departure");
			arrivalAtDestination = Objects.requireNonNull(arrivalAtDestination, "arrivalAtDestination");
			if (!arrivalAtDestination.isAfter(departure)) {
				throw new IllegalArgumentException("arrivalAtDestination must be after departure");
			}
			if (transfers < 0 || walkingSeconds < 0 || walkingDistanceMeters < 0
				|| accessibilityBurden < 0 || minimumConnectionSlackSeconds < 0) {
				throw new IllegalArgumentException("feasible candidate metrics must not be negative");
			}
		}
	}

	public record SelectedLabel(FeasibleCandidate candidate, List<ObjectiveTag> objectiveTags) {
		public SelectedLabel {
			candidate = Objects.requireNonNull(candidate, "candidate");
			objectiveTags = List.copyOf(Objects.requireNonNull(objectiveTags, "objectiveTags"));
			if (objectiveTags.isEmpty()) throw new IllegalArgumentException("objectiveTags must not be empty");
			if (EnumSet.copyOf(objectiveTags).size() != objectiveTags.size()) {
				throw new IllegalArgumentException("objectiveTags must be unique");
			}
		}
	}

	public sealed interface Outcome permits Success, CapacityExceeded {
		Metrics metrics();
	}

	public record Success(List<SelectedLabel> labels, Metrics metrics) implements Outcome {
		public Success {
			labels = List.copyOf(Objects.requireNonNull(labels, "labels"));
			metrics = Objects.requireNonNull(metrics, "metrics");
		}
	}

	/** Capacity failure deliberately exposes no selected labels. */
	public record CapacityExceeded(Metrics metrics, int requiredRepresentativeCount,
		int maxDestinationProfileLabels) implements Outcome {
		public CapacityExceeded {
			metrics = Objects.requireNonNull(metrics, "metrics");
			if (metrics.capacityState() != CapacityState.EXCEEDED) {
				throw new IllegalArgumentException("capacity failure requires exceeded metrics");
			}
			if (requiredRepresentativeCount <= maxDestinationProfileLabels || maxDestinationProfileLabels <= 0) {
				throw new IllegalArgumentException("capacity failure bounds are invalid");
			}
		}

		public List<SelectedLabel> selectedLabels() {
			return List.of();
		}
	}

	public record Metrics(
		long labelsGenerated,
		long labelsAccepted,
		long labelsDominated,
		long labelsDeduplicated,
		long labelsPrunedByBound,
		long labelsReplacedByRepresentativePolicy,
		long statesAtLabelCapacity,
		long maxLabelsObservedPerState,
		CapacityState capacityState
	) {
		public Metrics {
			if (labelsGenerated < 0 || labelsAccepted < 0 || labelsDominated < 0 || labelsDeduplicated < 0
				|| labelsPrunedByBound < 0 || labelsReplacedByRepresentativePolicy < 0
				|| statesAtLabelCapacity < 0 || maxLabelsObservedPerState < 0) {
				throw new IllegalArgumentException("frontier metrics must not be negative");
			}
			capacityState = Objects.requireNonNull(capacityState, "capacityState");
		}
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}
}
