package com.easysubway.journey.application;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.StepFreeTransitionEvaluator.DirectionOutcome;
import com.easysubway.journey.application.StepFreeTransitionEvaluator.TransitionOutcome;
import com.easysubway.journey.bundle.TransitionFacilityRequirements;
import com.easysubway.journey.bundle.TransitionFacilityRequirements.Requirement;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("무단차 전환 통과 판정(순수 함수)")
class StepFreeTransitionEvaluatorTest {

	private static final String ENTRY_A = "edge-entry-station-a-line-4";
	private static final String EXIT_A = "edge-exit-station-a-line-4";
	private static final String P_UP = "kric-mv:S1:4:448:447:1";
	private static final String P_UP_2 = "kric-mv:S1:4:448:447:2";
	private static final String P_DOWN = "kric-mv:S1:4:448:449:1";
	private static final String E1 = "smrt-elev:0448:4:1번 출입구";
	private static final String E2 = "smrt-elev:0448:4:2번 출입구";
	private static final String U1 = "smrt-elev:0448:4:상행역 방면1-1";
	private static final String W1 = "smrt-elev:0448:4:하행역 방면9-1";
	private static final String W2 = "smrt-elev:0448:4:하행역 방면10-4";
	private static final String EXIT_GROUP = TransitionFacilityRequirements.EXIT_ELEVATORS;
	private static final String DIRECTION_GROUP = TransitionFacilityRequirements.PLATFORM_DIRECTION_ELEVATORS;

	@Test
	@DisplayName("방향 A의 방향 엘리베이터 2대 중 1대가 불가면 전환은 통과한다")
	void oneOfTwoDirectionElevatorsOutStillPasses() {
		TransitionOutcome outcome = StepFreeTransitionEvaluator.evaluate(twoDirectionRows(ENTRY_A), operatingExcept(W1));

		assertThat(outcome).isEqualTo(new TransitionOutcome(true, List.of(
			new DirectionOutcome("station-down", true),
			new DirectionOutcome("station-up", true))));
	}

	@Test
	@DisplayName("방향 A의 방향 엘리베이터가 모두 불가면 다른 방향이 통과해도 전환은 막힌다")
	void allDirectionElevatorsOutBlocksTransition() {
		TransitionOutcome outcome = StepFreeTransitionEvaluator.evaluate(twoDirectionRows(ENTRY_A), operatingExcept(W1, W2));

		assertThat(outcome).isEqualTo(new TransitionOutcome(false, List.of(
			new DirectionOutcome("station-down", false),
			new DirectionOutcome("station-up", true))));
	}

	@Test
	@DisplayName("출입구 묶음이 모두 불가면 그 경로만 빠지고 같은 방향의 다른 경로로 통과한다")
	void exitGroupOutExcludesOnlyThatPath() {
		var rows = List.of(
			row(EXIT_A, P_UP, "station-up", EXIT_GROUP, E1),
			row(EXIT_A, P_UP, "station-up", DIRECTION_GROUP, U1),
			row(EXIT_A, P_UP_2, "station-up", EXIT_GROUP, E2),
			row(EXIT_A, P_UP_2, "station-up", DIRECTION_GROUP, U1));

		assertThat(StepFreeTransitionEvaluator.evaluate(rows, operatingExcept(E1)))
			.isEqualTo(new TransitionOutcome(true, List.of(new DirectionOutcome("station-up", true))));
		assertThat(StepFreeTransitionEvaluator.evaluate(rows, operatingExcept(E1, E2)))
			.isEqualTo(new TransitionOutcome(false, List.of(new DirectionOutcome("station-up", false))));
	}

	@Test
	@DisplayName("요구 행이 없는 전환은 근거가 없으므로 통과한다")
	void transitionWithoutRequirementRowsPasses() {
		assertThat(StepFreeTransitionEvaluator.evaluate(List.of(), id -> false))
			.isEqualTo(new TransitionOutcome(true, List.of()));
	}

	@Test
	@DisplayName("한 번의 판정에 여러 전환의 요구 행을 섞으면 거부한다")
	void rejectsRowsOfMoreThanOneTransition() {
		assertThatThrownBy(() -> StepFreeTransitionEvaluator.evaluate(
			List.of(row(ENTRY_A, P_UP, "station-up", EXIT_GROUP, E1), row(EXIT_A, P_UP, "station-up", EXIT_GROUP, E1)),
			id -> true))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("single transition_key");
	}

	@Test
	@DisplayName("막힌 전환 집합은 불가 시설로 통과하지 못하는 전환 키만 담고, 요구 행이 없는 전환은 담지 않는다")
	void blockedTransitionKeysListsOnlyImpassableTransitions() {
		var rows = new ArrayList<Requirement>();
		rows.addAll(twoDirectionRows(ENTRY_A));
		rows.addAll(twoDirectionRows(EXIT_A));
		rows.add(row("edge-entry-station-b-line-4", "kric-mv:S2:4:449:448:1", "station-a", EXIT_GROUP, "smrt-elev:0449:4:3번 출입구"));
		rows.add(row("edge-entry-station-b-line-4", "kric-mv:S2:4:449:448:1", "station-a", DIRECTION_GROUP, "smrt-elev:0449:4:상행 방면"));
		var requirements = TransitionFacilityRequirements.of(rows);

		assertThat(StepFreeTransitionEvaluator.blockedTransitionKeys(requirements, Set.of())).isEmpty();
		assertThat(StepFreeTransitionEvaluator.blockedTransitionKeys(requirements, Set.of(W1))).isEmpty();
		assertThat(StepFreeTransitionEvaluator.blockedTransitionKeys(requirements, Set.of(W1, W2)))
			.containsExactlyInAnyOrder(ENTRY_A, EXIT_A);
		assertThat(StepFreeTransitionEvaluator.blockedTransitionKeys(requirements, Set.of("smrt-elev:0449:4:3번 출입구")))
			.containsExactly("edge-entry-station-b-line-4");
		assertThat(StepFreeTransitionEvaluator.blockedTransitionKeys(requirements, Set.of("smrt-elev:9999:9:무관")))
			.isEmpty();
	}

	@Test
	@DisplayName("매핑이 없는 번들로는 막힌 전환 집합을 만들지 않는다(차단 0건으로 숨기지 않는다)")
	void refusesToComputeBlockedTransitionsWithoutMapping() {
		assertThatThrownBy(() -> StepFreeTransitionEvaluator.blockedTransitionKeys(
			TransitionFacilityRequirements.missing(), Set.of(W1)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("no transition facility requirement mapping");
	}

	/**
	 * data {@code evaluateStepFreeTransition}과 같은 손으로 쓴 사례를 공유 계약 파일로 고정한다. 기대값은 data 참조 구현으로
	 * 실측해 맞춘 상수이며, 이 테스트는 구현을 복제하지 않고 저장된 기대값과만 비교한다.
	 */
	@Test
	@DisplayName("공유 계약 사례에서 data 참조 구현과 같은 판정·방향 순서를 낸다")
	void matchesSharedCrossRepositoryCases() throws Exception {
		JsonNode contract = new ObjectMapper().readTree(requireNonNull(StepFreeTransitionEvaluatorTest.class
			.getResourceAsStream("/contracts/step-free-transition-evaluation-cases.json")));
		assertThat(contract.path("schemaVersion").asInt()).isEqualTo(1);
		int checked = 0;
		for (JsonNode sharedCase : contract.path("cases")) {
			String id = sharedCase.path("id").asText();
			var rows = new ArrayList<Requirement>();
			for (JsonNode row : sharedCase.path("rows")) {
				rows.add(row(row.get(0).asText(), row.get(1).asText(), row.get(2).asText(), row.get(3).asText(),
					row.get(4).asText()));
			}
			var outOfService = new HashSet<String>();
			sharedCase.path("outOfService").forEach(value -> outOfService.add(value.asText()));
			if (sharedCase.has("expectedError")) {
				assertThatThrownBy(() -> StepFreeTransitionEvaluator.evaluate(rows, facility -> !outOfService.contains(facility)))
					.as(id)
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining(sharedCase.path("expectedError").asText());
			} else {
				var directions = new ArrayList<DirectionOutcome>();
				for (JsonNode direction : sharedCase.path("expected").path("directions")) {
					directions.add(new DirectionOutcome(
						direction.path("nextStationId").asText(), direction.path("passable").asBoolean()));
				}
				assertThat(StepFreeTransitionEvaluator.evaluate(rows, facility -> !outOfService.contains(facility)))
					.as(id)
					.isEqualTo(new TransitionOutcome(sharedCase.path("expected").path("passable").asBoolean(), directions));
			}
			checked += 1;
		}
		assertThat(checked).isEqualTo(9);
	}

	private static List<Requirement> twoDirectionRows(String transitionKey) {
		return List.of(
			row(transitionKey, P_UP, "station-up", EXIT_GROUP, E1),
			row(transitionKey, P_UP, "station-up", DIRECTION_GROUP, U1),
			row(transitionKey, P_DOWN, "station-down", EXIT_GROUP, E1),
			row(transitionKey, P_DOWN, "station-down", DIRECTION_GROUP, W2),
			row(transitionKey, P_DOWN, "station-down", DIRECTION_GROUP, W1));
	}

	private static Requirement row(String transitionKey, String pathId, String nextStationId, String groupKind,
		String facilityId) {
		return new Requirement(transitionKey, pathId, nextStationId, groupKind, facilityId);
	}

	private static java.util.function.Predicate<String> operatingExcept(String... outOfService) {
		Set<String> out = Set.of(outOfService);
		return facilityId -> !out.contains(facilityId);
	}
}
