package com.easysubway.journey.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Closed inventory of pruning semantics currently active only in Journey temporal-profile RAPTOR
 * planning. This is an evidence contract; it does not itself decide whether a planner label is
 * pruned.
 */
public final class JourneyRaptorPruningInventoryV1 {

	public static final AlgorithmSemanticIdentity FORWARD_RANGE_RAPTOR =
		new AlgorithmSemanticIdentity("EASYSUBWAY_RAPTOR_SUITE_V2", "FORWARD_RANGE_RAPTOR", "1.0.0");
	public static final AlgorithmSemanticIdentity REVERSE_RANGE_RAPTOR =
		new AlgorithmSemanticIdentity("EASYSUBWAY_RAPTOR_SUITE_V2", "REVERSE_RANGE_RAPTOR", "1.0.0");

	private static final List<Definition> DEFINITIONS = List.of(
		definition("HARD_TRANSFER_ACCESS_ELIGIBILITY_V1", both(),
			"Only a directionally valid, profile-eligible access transition may extend a profile trace."),
		definition("FORWARD_STATE_DOMINANCE_V1", forward(),
			"A forward state retains no label dominated on every profile-relevant state dimension."),
		definition("FORWARD_STATE_EQUAL_VECTOR_CANONICAL_TRACE_V1", forward(),
			"Equal forward state vectors retain only the canonical lowest trace."),
		definition("FORWARD_DESTINATION_DOMINANCE_V1", forward(),
			"A forward destination frontier retains no itinerary dominated after verified exit completion."),
		definition("REVERSE_STATE_DOMINANCE_V1", reverse(),
			"A reverse state retains no candidate dominated on every latest-ready profile dimension."),
		definition("REVERSE_STATE_EQUAL_VECTOR_CANONICAL_TRACE_V1", reverse(),
			"Equal reverse state vectors retain only the canonical lowest trace."),
		definition("REVERSE_DESTINATION_DOMINANCE_V1", reverse(),
			"A reverse destination frontier retains no candidate dominated after verified exit completion."),
		definition("REVERSE_DESTINATION_EQUAL_VECTOR_CANONICAL_TRACE_V1", reverse(),
			"Equal reverse destination vectors retain only the canonical lowest trace."),
		definition("FAIL_CLOSED_FRONTIER_CAPACITY_V1", both(),
			"A frontier above its configured capacity fails the request and never returns a truncated success.")
	);
	private static final Set<AlgorithmSemanticIdentity> ALGORITHM_IDENTITIES = Set.of(
		FORWARD_RANGE_RAPTOR, REVERSE_RANGE_RAPTOR);

	private JourneyRaptorPruningInventoryV1() {
	}

	public static List<Definition> definitions() {
		return DEFINITIONS;
	}

	public static Set<String> activeRuleIds(AlgorithmSemanticIdentity algorithmIdentity) {
		AlgorithmSemanticIdentity requiredIdentity = requireKnownAlgorithm(algorithmIdentity);
		return DEFINITIONS.stream()
			.filter(definition -> definition.algorithmSemanticIdentities().contains(requiredIdentity))
			.map(Definition::pruningRuleId)
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	public record AlgorithmSemanticIdentity(String algorithmSuiteId, String queryAlgorithmId, String semanticVersion) {
		public AlgorithmSemanticIdentity {
			algorithmSuiteId = requireText(algorithmSuiteId, "algorithmSuiteId");
			queryAlgorithmId = requireText(queryAlgorithmId, "queryAlgorithmId");
			semanticVersion = requireText(semanticVersion, "semanticVersion");
		}
	}

	public record Definition(
		String pruningRuleId,
		List<AlgorithmSemanticIdentity> algorithmSemanticIdentities,
		String correctnessInvariant,
		String observableCounterKey
	) {
		public Definition {
			pruningRuleId = requireText(pruningRuleId, "pruningRuleId");
			algorithmSemanticIdentities = List.copyOf(Objects.requireNonNull(
				algorithmSemanticIdentities, "algorithmSemanticIdentities"));
			if (algorithmSemanticIdentities.isEmpty()) {
				throw new IllegalArgumentException("algorithmSemanticIdentities must not be empty");
			}
			if (Set.copyOf(algorithmSemanticIdentities).size() != algorithmSemanticIdentities.size()) {
				throw new IllegalArgumentException("algorithmSemanticIdentities must be unique");
			}
			correctnessInvariant = requireText(correctnessInvariant, "correctnessInvariant");
			observableCounterKey = requireText(observableCounterKey, "observableCounterKey");
		}
	}

	public record RuleCount(String pruningRuleId, long count) {
		public RuleCount {
			pruningRuleId = requireText(pruningRuleId, "pruningRuleId");
			if (count < 0) throw new IllegalArgumentException("count must not be negative");
		}
	}

	/** Immutable count coverage for one profile request, keyed only by active rule id. */
	public record CountSnapshot(
		String requestId,
		AlgorithmSemanticIdentity algorithmIdentity,
		Map<String, Long> countsByRuleId
	) {
		public CountSnapshot {
			requestId = requireText(requestId, "requestId");
			algorithmIdentity = requireKnownAlgorithm(algorithmIdentity);
			countsByRuleId = immutableCounts(countsByRuleId);
			requireExactActiveRules(algorithmIdentity, countsByRuleId.keySet(), "count observations");
		}

		public static CountSnapshot observed(
			String requestId,
			AlgorithmSemanticIdentity algorithmIdentity,
			RuleCount... observations
		) {
			Objects.requireNonNull(observations, "observations");
			Map<String, Long> counts = new LinkedHashMap<>();
			for (RuleCount observation : observations) {
				RuleCount required = Objects.requireNonNull(observation, "observations contains null");
				if (counts.putIfAbsent(required.pruningRuleId(), required.count()) != null) {
					throw new IllegalArgumentException("duplicate count observation: " + required.pruningRuleId());
				}
			}
			return new CountSnapshot(requestId, algorithmIdentity, counts);
		}
	}

	public record OracleParityObservation(
		String requestId,
		AlgorithmSemanticIdentity algorithmIdentity,
		String pruningRuleId,
		boolean exactOracleParity
	) {
		public OracleParityObservation {
			requestId = requireText(requestId, "requestId");
			algorithmIdentity = requireKnownAlgorithm(algorithmIdentity);
			pruningRuleId = requireText(pruningRuleId, "pruningRuleId");
		}
	}

	/**
	 * Evidence is accepted only when every active rule has an explicit exact-oracle-parity result.
	 */
	public record VerifiedEvidence(CountSnapshot countSnapshot, List<OracleParityObservation> oracleParity) {
		public VerifiedEvidence {
			countSnapshot = Objects.requireNonNull(countSnapshot, "countSnapshot");
			oracleParity = List.copyOf(Objects.requireNonNull(oracleParity, "oracleParity"));
			Map<String, Boolean> parityByRuleId = new LinkedHashMap<>();
			for (OracleParityObservation observation : oracleParity) {
				OracleParityObservation required = Objects.requireNonNull(observation, "oracleParity contains null");
				if (!required.requestId().equals(countSnapshot.requestId())
					|| !required.algorithmIdentity().equals(countSnapshot.algorithmIdentity())) {
					throw new IllegalArgumentException("oracle parity must match count request and algorithm identity");
				}
				if (parityByRuleId.putIfAbsent(required.pruningRuleId(), required.exactOracleParity()) != null) {
					throw new IllegalArgumentException("duplicate oracle parity observation: " + required.pruningRuleId());
				}
			}
			requireExactActiveRules(countSnapshot.algorithmIdentity(), parityByRuleId.keySet(),
				"oracle parity observations");
			if (parityByRuleId.containsValue(false)) {
				throw new IllegalArgumentException("every active pruning rule requires exact oracle parity");
			}
		}
	}

	private static Definition definition(
		String pruningRuleId,
		List<AlgorithmSemanticIdentity> identities,
		String correctnessInvariant
	) {
		return new Definition(pruningRuleId, identities, correctnessInvariant,
			"journey.raptor.pruning." + pruningRuleId + ".count");
	}

	private static List<AlgorithmSemanticIdentity> forward() {
		return List.of(FORWARD_RANGE_RAPTOR);
	}

	private static List<AlgorithmSemanticIdentity> reverse() {
		return List.of(REVERSE_RANGE_RAPTOR);
	}

	private static List<AlgorithmSemanticIdentity> both() {
		return List.of(FORWARD_RANGE_RAPTOR, REVERSE_RANGE_RAPTOR);
	}

	private static Map<String, Long> immutableCounts(Map<String, Long> countsByRuleId) {
		Objects.requireNonNull(countsByRuleId, "countsByRuleId");
		Map<String, Long> copied = new LinkedHashMap<>();
		for (Map.Entry<String, Long> entry : countsByRuleId.entrySet()) {
			String ruleId = requireText(entry.getKey(), "countsByRuleId key");
			Long count = Objects.requireNonNull(entry.getValue(), "countsByRuleId value");
			if (count < 0) throw new IllegalArgumentException("count must not be negative");
			copied.put(ruleId, count);
		}
		return Map.copyOf(copied);
	}

	private static void requireExactActiveRules(
		AlgorithmSemanticIdentity algorithmIdentity,
		Set<String> observedRuleIds,
		String name
	) {
		if (!activeRuleIds(algorithmIdentity).equals(observedRuleIds)) {
			throw new IllegalArgumentException(name + " must cover exactly the active pruning rule set");
		}
	}

	private static AlgorithmSemanticIdentity requireKnownAlgorithm(AlgorithmSemanticIdentity algorithmIdentity) {
		AlgorithmSemanticIdentity required = Objects.requireNonNull(algorithmIdentity, "algorithmIdentity");
		if (!ALGORITHM_IDENTITIES.contains(required)) {
			throw new IllegalArgumentException("algorithmIdentity is not active in pruning inventory V1");
		}
		return required;
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}
}
