package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JourneyRaptorPruningInventoryV1Test {

	private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";

	@Test
	void publishesExactlyTheActiveProfileRulesWithAdoptedAlgorithmIdentities() {
		assertThat(JourneyRaptorPruningInventoryV1.FORWARD_RANGE_RAPTOR)
			.isEqualTo(new JourneyRaptorPruningInventoryV1.AlgorithmSemanticIdentity(
				"EASYSUBWAY_RAPTOR_SUITE_V2", "FORWARD_RANGE_RAPTOR", "1.0.0"));
		assertThat(JourneyRaptorPruningInventoryV1.REVERSE_RANGE_RAPTOR)
			.isEqualTo(new JourneyRaptorPruningInventoryV1.AlgorithmSemanticIdentity(
				"EASYSUBWAY_RAPTOR_SUITE_V2", "REVERSE_RANGE_RAPTOR", "1.0.0"));

		assertThat(JourneyRaptorPruningInventoryV1.definitions())
			.extracting(JourneyRaptorPruningInventoryV1.Definition::pruningRuleId)
			.containsExactly(
				"HARD_TRANSFER_ACCESS_ELIGIBILITY_V1",
				"FORWARD_STATE_DOMINANCE_V1",
				"FORWARD_STATE_EQUAL_VECTOR_CANONICAL_TRACE_V1",
				"FORWARD_DESTINATION_DOMINANCE_V1",
				"REVERSE_STATE_DOMINANCE_V1",
				"REVERSE_STATE_EQUAL_VECTOR_CANONICAL_TRACE_V1",
				"REVERSE_DESTINATION_DOMINANCE_V1",
				"REVERSE_DESTINATION_EQUAL_VECTOR_CANONICAL_TRACE_V1",
				"FAIL_CLOSED_FRONTIER_CAPACITY_V1");
		assertThat(JourneyRaptorPruningInventoryV1.definitions()).allSatisfy(definition -> {
			assertThat(definition.algorithmSemanticIdentities()).isNotEmpty();
			assertThat(definition.correctnessInvariant()).isNotBlank();
			assertThat(definition.observableCounterKey())
				.isEqualTo("journey.raptor.pruning." + definition.pruningRuleId() + ".count");
		});
	}

	@Test
	void countSnapshotRequiresImmutableExactNonnegativeCoverage() {
		var snapshot = counts(JourneyRaptorPruningInventoryV1.FORWARD_RANGE_RAPTOR, 2L);
		String[] forwardRuleIds = JourneyRaptorPruningInventoryV1.activeRuleIds(
			JourneyRaptorPruningInventoryV1.FORWARD_RANGE_RAPTOR).toArray(String[]::new);

		assertThat(snapshot.countsByRuleId())
			.containsOnlyKeys(forwardRuleIds)
			.allSatisfy((rule, count) -> assertThat(count).isEqualTo(2L));
		assertThatThrownBy(() -> snapshot.countsByRuleId().put("FORWARD_STATE_DOMINANCE_V1", 3L))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatIllegalArgumentException().isThrownBy(() -> new JourneyRaptorPruningInventoryV1.RuleCount(
			"FORWARD_STATE_DOMINANCE_V1", -1L));
		assertThatIllegalArgumentException().isThrownBy(() -> new JourneyRaptorPruningInventoryV1.CountSnapshot(
			REQUEST_ID,
			JourneyRaptorPruningInventoryV1.FORWARD_RANGE_RAPTOR,
			Map.of("FORWARD_STATE_DOMINANCE_V1", -1L)));
	}

	@Test
	void countSnapshotRejectsMissingExtraAndDuplicateObservations() {
		var algorithm = JourneyRaptorPruningInventoryV1.FORWARD_RANGE_RAPTOR;
		List<JourneyRaptorPruningInventoryV1.RuleCount> all = ruleCounts(algorithm, 0L);

		assertThatIllegalArgumentException().isThrownBy(() -> JourneyRaptorPruningInventoryV1.CountSnapshot.observed(
			REQUEST_ID, algorithm,
			all.subList(1, all.size()).toArray(JourneyRaptorPruningInventoryV1.RuleCount[]::new)));
		Map<String, Long> extra = new LinkedHashMap<>(counts(algorithm, 0L).countsByRuleId());
		extra.put("INACTIVE_EARLY_PRUNING_V1", 0L);
		assertThatIllegalArgumentException().isThrownBy(() -> new JourneyRaptorPruningInventoryV1.CountSnapshot(
			REQUEST_ID, algorithm, extra));
		List<JourneyRaptorPruningInventoryV1.RuleCount> duplicated = new ArrayList<>(all);
		duplicated.add(all.getFirst());
		assertThatIllegalArgumentException().isThrownBy(() -> JourneyRaptorPruningInventoryV1.CountSnapshot.observed(
			REQUEST_ID, algorithm, duplicated.toArray(JourneyRaptorPruningInventoryV1.RuleCount[]::new)));
		assertThatIllegalArgumentException().isThrownBy(() -> new JourneyRaptorPruningInventoryV1.CountSnapshot(
			REQUEST_ID, algorithm, counts(JourneyRaptorPruningInventoryV1.REVERSE_RANGE_RAPTOR, 0L).countsByRuleId()));
	}

	@Test
	void verifiedEvidenceRequiresExactTrueOracleParityForEveryRule() {
		var algorithm = JourneyRaptorPruningInventoryV1.FORWARD_RANGE_RAPTOR;
		var counts = counts(algorithm, 0L);
		var trueParity = parity(algorithm, true);

		assertThat(new JourneyRaptorPruningInventoryV1.VerifiedEvidence(counts, trueParity).countSnapshot())
			.isSameAs(counts);
		List<JourneyRaptorPruningInventoryV1.OracleParityObservation> falseParity = new ArrayList<>(trueParity);
		falseParity.set(0, new JourneyRaptorPruningInventoryV1.OracleParityObservation(
			REQUEST_ID, algorithm, falseParity.getFirst().pruningRuleId(), false));
		assertThatIllegalArgumentException().isThrownBy(() -> new JourneyRaptorPruningInventoryV1.VerifiedEvidence(
			counts, falseParity));
		assertThatIllegalArgumentException().isThrownBy(() -> new JourneyRaptorPruningInventoryV1.VerifiedEvidence(
			counts, trueParity.subList(1, trueParity.size())));
		List<JourneyRaptorPruningInventoryV1.OracleParityObservation> duplicate = new ArrayList<>(trueParity);
		duplicate.add(trueParity.getFirst());
		assertThatIllegalArgumentException().isThrownBy(() -> new JourneyRaptorPruningInventoryV1.VerifiedEvidence(
			counts, duplicate));
		List<JourneyRaptorPruningInventoryV1.OracleParityObservation> otherRequest = new ArrayList<>(trueParity);
		otherRequest.set(0, new JourneyRaptorPruningInventoryV1.OracleParityObservation(
			"01ARZ3NDEKTSV4RRFFQ69G5FAA", algorithm, otherRequest.getFirst().pruningRuleId(), true));
		assertThatIllegalArgumentException().isThrownBy(() -> new JourneyRaptorPruningInventoryV1.VerifiedEvidence(
			counts, otherRequest));
	}

	private static JourneyRaptorPruningInventoryV1.CountSnapshot counts(
		JourneyRaptorPruningInventoryV1.AlgorithmSemanticIdentity algorithm,
		long count
	) {
		return JourneyRaptorPruningInventoryV1.CountSnapshot.observed(
			REQUEST_ID, algorithm,
			ruleCounts(algorithm, count).toArray(JourneyRaptorPruningInventoryV1.RuleCount[]::new));
	}

	private static List<JourneyRaptorPruningInventoryV1.RuleCount> ruleCounts(
		JourneyRaptorPruningInventoryV1.AlgorithmSemanticIdentity algorithm,
		long count
	) {
		return JourneyRaptorPruningInventoryV1.definitions().stream()
			.filter(definition -> definition.algorithmSemanticIdentities().contains(algorithm))
			.map(definition -> new JourneyRaptorPruningInventoryV1.RuleCount(definition.pruningRuleId(), count))
			.toList();
	}

	private static List<JourneyRaptorPruningInventoryV1.OracleParityObservation> parity(
		JourneyRaptorPruningInventoryV1.AlgorithmSemanticIdentity algorithm,
		boolean exactOracleParity
	) {
		return JourneyRaptorPruningInventoryV1.definitions().stream()
			.filter(definition -> definition.algorithmSemanticIdentities().contains(algorithm))
			.map(definition -> new JourneyRaptorPruningInventoryV1.OracleParityObservation(
				REQUEST_ID, algorithm, definition.pruningRuleId(), exactOracleParity))
			.toList();
	}
}
