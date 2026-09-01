package com.easysubway.journey.application;

import com.easysubway.journey.application.JourneyFrontierPolicyV1.ObjectiveTag;
import com.easysubway.journey.application.JourneyFrontierPolicyV1.SelectedLabel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Versioned non-wire projection of selected frontier representatives into profile summaries.
 *
 * <p>Recommendation order is explicit and mode-specific. This policy does not rank untagged
 * candidates or manufacture a default beyond the requested public alternative count.</p>
 */
public final class JourneyProfileSummaryPolicyV1 {

	private JourneyProfileSummaryPolicyV1() {
	}

	public static Summary select(
		JourneyRaptorQuery query,
		JourneyFrontierPolicyV1.Success frontier,
		int alternativeCount
	) {
		Objects.requireNonNull(query, "query");
		Objects.requireNonNull(frontier, "frontier");
		if (alternativeCount < 1 || alternativeCount > 3) {
			throw new IllegalArgumentException("alternativeCount must be between 1 and 3");
		}
		if (query.alternativeCount() != alternativeCount) {
			throw new IllegalArgumentException("alternativeCount must match query");
		}

		Inventory inventory = inventory(frontier.labels());
		return switch (query.temporalQuery()) {
			case JourneyRaptorQuery.DepartBetween departure -> departureSummary(departure, inventory, alternativeCount);
			case JourneyRaptorQuery.ArriveBy arriveBy -> arriveBySummary(arriveBy, inventory, alternativeCount);
			case JourneyRaptorQuery.LastConnection lastConnection ->
				lastConnectionSummary(lastConnection, inventory, alternativeCount);
			case JourneyRaptorQuery.DepartAt ignored -> throw new IllegalArgumentException(
				"Journey profile summary requires a profile temporal query");
		};
	}

	private static Departure departureSummary(
		JourneyRaptorQuery.DepartBetween query,
		Inventory inventory,
		int alternativeCount
	) {
		validateDepartureWindow(query, inventory);
		String fastest = inventory.idFor(ObjectiveTag.FASTEST_ARRIVAL);
		String latest = inventory.idFor(ObjectiveTag.LATEST_DEPARTURE);
		return new Departure(fastest, latest, recommended(inventory, alternativeCount,
			ObjectiveTag.FASTEST_ARRIVAL, ObjectiveTag.LATEST_DEPARTURE, ObjectiveTag.FEWEST_TRANSFERS,
			ObjectiveTag.BEST_ACCESSIBILITY, ObjectiveTag.LOWEST_WALKING_BURDEN,
			ObjectiveTag.SAFEST_CONNECTION));
	}

	private static ArriveBy arriveBySummary(
		JourneyRaptorQuery.ArriveBy query,
		Inventory inventory,
		int alternativeCount
	) {
		validateArrivalDeadline(query, inventory);
		SelectedLabel primary = inventory.labelFor(ObjectiveTag.LATEST_DEPARTURE);
		return new ArriveBy(query.arrivalDeadline(), primary.candidate().departure(), primary.candidate().journeyId(),
			recommended(inventory, alternativeCount, ObjectiveTag.LATEST_DEPARTURE,
				ObjectiveTag.FEWEST_TRANSFERS, ObjectiveTag.BEST_ACCESSIBILITY,
				ObjectiveTag.LOWEST_WALKING_BURDEN, ObjectiveTag.SAFEST_CONNECTION,
				ObjectiveTag.FASTEST_ARRIVAL));
	}

	private static LastConnection lastConnectionSummary(
		JourneyRaptorQuery.LastConnection query,
		Inventory inventory,
		int alternativeCount
	) {
		for (SelectedLabel label : inventory.labels()) {
			if (!ServiceDayResolver.resolve(label.candidate().departure()).serviceDate().equals(query.serviceDate())) {
				throw new IllegalArgumentException("selected journey does not belong to last-connection service day");
			}
		}
		SelectedLabel last = inventory.labelFor(ObjectiveTag.LATEST_DEPARTURE);
		SelectedLabel safest = inventory.labelFor(ObjectiveTag.SAFEST_CONNECTION);
		List<String> saferAlternatives = safest.candidate().journeyId().equals(last.candidate().journeyId())
			|| safest.candidate().minimumConnectionSlackSeconds()
				<= last.candidate().minimumConnectionSlackSeconds()
			? List.of() : List.of(safest.candidate().journeyId());
		return new LastConnection(last.candidate().departure(), last.candidate().journeyId(), saferAlternatives,
			recommended(inventory, alternativeCount, ObjectiveTag.LATEST_DEPARTURE,
				ObjectiveTag.SAFEST_CONNECTION, ObjectiveTag.FEWEST_TRANSFERS,
				ObjectiveTag.BEST_ACCESSIBILITY, ObjectiveTag.LOWEST_WALKING_BURDEN,
				ObjectiveTag.FASTEST_ARRIVAL));
	}

	private static List<String> recommended(Inventory inventory, int alternativeCount, ObjectiveTag... order) {
		List<String> ids = new ArrayList<>(alternativeCount);
		for (ObjectiveTag tag : order) {
			String journeyId = inventory.idFor(tag);
			if (!ids.contains(journeyId)) ids.add(journeyId);
			if (ids.size() == alternativeCount) break;
		}
		if (ids.isEmpty()) throw new IllegalArgumentException("recommended journey ids must not be empty");
		return List.copyOf(ids);
	}

	private static void validateDepartureWindow(JourneyRaptorQuery.DepartBetween query, Inventory inventory) {
		for (SelectedLabel label : inventory.labels()) {
			Instant departure = label.candidate().departure();
			if (departure.isBefore(query.earliestReadyAt()) || departure.isAfter(query.latestReadyAt())) {
				throw new IllegalArgumentException("selected journey falls outside departure window");
			}
		}
	}

	private static void validateArrivalDeadline(JourneyRaptorQuery.ArriveBy query, Inventory inventory) {
		for (SelectedLabel label : inventory.labels()) {
			if (label.candidate().departure().isBefore(query.earliestReadyAt())
				|| label.candidate().arrivalAtDestination().isAfter(query.arrivalDeadline())) {
				throw new IllegalArgumentException("selected journey falls outside arrive-by facts");
			}
		}
	}

	private static Inventory inventory(List<SelectedLabel> labels) {
		Map<String, SelectedLabel> byJourneyId = new HashMap<>();
		Map<ObjectiveTag, SelectedLabel> byTag = new EnumMap<>(ObjectiveTag.class);
		for (SelectedLabel label : labels) {
			SelectedLabel requiredLabel = Objects.requireNonNull(label, "selected labels contains null");
			if (byJourneyId.putIfAbsent(requiredLabel.candidate().journeyId(), requiredLabel) != null) {
				throw new IllegalArgumentException("duplicate selected journeyId: " + requiredLabel.candidate().journeyId());
			}
			for (ObjectiveTag tag : requiredLabel.objectiveTags()) {
				if (byTag.putIfAbsent(tag, requiredLabel) != null) {
					throw new IllegalArgumentException("duplicate representative tag: " + tag);
				}
			}
		}
		for (ObjectiveTag tag : ObjectiveTag.values()) {
			if (!byTag.containsKey(tag)) {
				throw new IllegalArgumentException("missing required representative tag: " + tag);
			}
		}
		return new Inventory(List.copyOf(labels), Map.copyOf(byJourneyId), Map.copyOf(byTag));
	}

	public enum Kind {
		DEPART_BETWEEN,
		ARRIVE_BY,
		LAST_CONNECTION
	}

	public sealed interface Summary permits Departure, ArriveBy, LastConnection {
		Kind kind();

		List<String> recommendedJourneyIds();
	}

	public record Departure(
		String earliestArrivalJourneyId,
		String latestDepartureJourneyId,
		List<String> recommendedJourneyIds
	) implements Summary {
		public Departure {
			earliestArrivalJourneyId = requireText(earliestArrivalJourneyId, "earliestArrivalJourneyId");
			latestDepartureJourneyId = requireText(latestDepartureJourneyId, "latestDepartureJourneyId");
			recommendedJourneyIds = ids(recommendedJourneyIds, true);
		}

		@Override
		public Kind kind() {
			return Kind.DEPART_BETWEEN;
		}
	}

	public record ArriveBy(
		Instant arrivalDeadline,
		Instant latestFeasibleDeparture,
		String primaryJourneyId,
		List<String> recommendedJourneyIds
	) implements Summary {
		public ArriveBy {
			arrivalDeadline = Objects.requireNonNull(arrivalDeadline, "arrivalDeadline");
			latestFeasibleDeparture = Objects.requireNonNull(latestFeasibleDeparture, "latestFeasibleDeparture");
			if (latestFeasibleDeparture.isAfter(arrivalDeadline)) {
				throw new IllegalArgumentException("latestFeasibleDeparture must not follow arrivalDeadline");
			}
			primaryJourneyId = requireText(primaryJourneyId, "primaryJourneyId");
			recommendedJourneyIds = ids(recommendedJourneyIds, true);
		}

		@Override
		public Kind kind() {
			return Kind.ARRIVE_BY;
		}
	}

	public record LastConnection(
		Instant latestFeasibleDeparture,
		String lastConnectionJourneyId,
		List<String> saferAlternativeJourneyIds,
		List<String> recommendedJourneyIds
	) implements Summary {
		public LastConnection {
			latestFeasibleDeparture = Objects.requireNonNull(latestFeasibleDeparture, "latestFeasibleDeparture");
			lastConnectionJourneyId = requireText(lastConnectionJourneyId, "lastConnectionJourneyId");
			saferAlternativeJourneyIds = ids(saferAlternativeJourneyIds, false);
			recommendedJourneyIds = ids(recommendedJourneyIds, true);
		}

		@Override
		public Kind kind() {
			return Kind.LAST_CONNECTION;
		}
	}

	private record Inventory(
		List<SelectedLabel> labels,
		Map<String, SelectedLabel> byJourneyId,
		Map<ObjectiveTag, SelectedLabel> byTag
	) {
		private SelectedLabel labelFor(ObjectiveTag tag) {
			SelectedLabel label = byTag.get(tag);
			if (label == null || !byJourneyId.containsKey(label.candidate().journeyId())) {
				throw new IllegalArgumentException("unresolved representative tag: " + tag);
			}
			return label;
		}

		private String idFor(ObjectiveTag tag) {
			return labelFor(tag).candidate().journeyId();
		}
	}

	private static List<String> ids(List<String> values, boolean required) {
		values = List.copyOf(Objects.requireNonNull(values, "journey ids"));
		if (required && values.isEmpty()) throw new IllegalArgumentException("journey ids must not be empty");
		if (values.size() > 3) throw new IllegalArgumentException("journey ids must contain at most three values");
		if (values.stream().anyMatch(value -> value == null || value.isBlank())
			|| values.stream().distinct().count() != values.size()) {
			throw new IllegalArgumentException("journey ids must be distinct nonblank values");
		}
		return values;
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}
}
