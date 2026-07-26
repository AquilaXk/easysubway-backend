package com.easysubway.route.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.profile.domain.MobilityType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("계단 접근성 판정 단일 원천")
class StairAccessTest {

	@Nested
	@DisplayName("스텝 판정")
	class StepJudgment {

		@Test
		@DisplayName("승차 스텝은 계단 개념이 적용되지 않아 NOT_APPLICABLE이다")
		void rideStepIsNotApplicable() {
			assertThat(StairAccess.ofStep(rideStep())).isEqualTo(StairAccess.NOT_APPLICABLE);
		}

		@Test
		@DisplayName("승차 스텝의 stairAccessState=UNKNOWN은 미확인이 아니라 판정 대상 밖이다")
		void rideStepUnknownStateIsNotAnUnverifiedSignal() {
			RouteStep ride = rideStep();

			assertThat(ride.stairAccessState()).isEqualTo("UNKNOWN");
			assertThat(StairAccess.ofStep(ride).demotedIfUnverified(true)).isEqualTo(StairAccess.NOT_APPLICABLE);
		}

		@Test
		@DisplayName("계단이 있는 전이는 STAIR_ONLY다")
		void stairTransitionIsStairOnly() {
			assertThat(StairAccess.ofStep(transitionStep(true, true))).isEqualTo(StairAccess.STAIR_ONLY);
		}

		@Test
		@DisplayName("검증된 무단차 전이는 STEP_FREE다")
		void verifiedTransitionIsStepFree() {
			assertThat(StairAccess.ofStep(transitionStep(false, true))).isEqualTo(StairAccess.STEP_FREE);
		}

		@Test
		@DisplayName("미검증 전이는 UNKNOWN이다")
		void unverifiedTransitionIsUnknown() {
			assertThat(StairAccess.ofStep(transitionStep(false, false))).isEqualTo(StairAccess.UNKNOWN);
		}

		@Test
		@DisplayName("확인되지 않은 근거가 있으면 STEP_FREE를 UNKNOWN으로 내린다")
		void unverifiedEvidenceDemotesStepFree() {
			assertThat(StairAccess.ofStep(transitionStep(false, true)).demotedIfUnverified(true))
				.isEqualTo(StairAccess.UNKNOWN);
		}

		@Test
		@DisplayName("확인되지 않은 근거는 STAIR_ONLY를 흔들지 않는다")
		void unverifiedEvidenceKeepsStairOnly() {
			assertThat(StairAccess.ofStep(transitionStep(true, true)).demotedIfUnverified(true))
				.isEqualTo(StairAccess.STAIR_ONLY);
		}
	}

	@Nested
	@DisplayName("경로 판정")
	class ItineraryJudgment {

		@Test
		@DisplayName("승차 스텝만 있어도 무단차 판정을 막지 않는다")
		void rideOnlyItineraryIsStepFree() {
			assertThat(StairAccess.ofItinerary(itinerary(List.of(rideStep()), List.of())))
				.isEqualTo(StairAccess.STEP_FREE);
		}

		@Test
		@DisplayName("검증된 전이와 승차만 있으면 STEP_FREE다")
		void verifiedTransitionsWithRideAreStepFree() {
			List<RouteStep> steps = List.of(transitionStep(false, true), rideStep(), transitionStep(false, true));

			assertThat(StairAccess.ofItinerary(itinerary(steps, List.of()))).isEqualTo(StairAccess.STEP_FREE);
		}

		@Test
		@DisplayName("계단 전이가 하나라도 있으면 STAIR_ONLY다")
		void stairTransitionMakesItineraryStairOnly() {
			List<RouteStep> steps = List.of(transitionStep(true, true), rideStep());

			assertThat(StairAccess.ofItinerary(itinerary(steps, List.of()))).isEqualTo(StairAccess.STAIR_ONLY);
		}

		@Test
		@DisplayName("STAIR_ONLY_ACCESS 경고는 스텝에 계단 표시가 없어도 STAIR_ONLY로 확정한다")
		void stairWarningMakesItineraryStairOnly() {
			List<RouteStep> steps = List.of(transitionStep(false, true), rideStep());
			List<RouteWarning> warnings = List.of(new RouteWarning(RouteWarningCode.STAIR_ONLY_ACCESS));

			assertThat(StairAccess.ofItinerary(itinerary(steps, warnings))).isEqualTo(StairAccess.STAIR_ONLY);
		}

		@Test
		@DisplayName("미검증 전이가 있으면 UNKNOWN이다")
		void unverifiedTransitionMakesItineraryUnknown() {
			List<RouteStep> steps = List.of(transitionStep(false, false), rideStep());

			assertThat(StairAccess.ofItinerary(itinerary(steps, warningsOnly()))).isEqualTo(StairAccess.UNKNOWN);
		}

		@Test
		@DisplayName("데이터 신뢰도 경고는 #2560 태깅 술어를 흔들지 않는다")
		void dataConfidenceWarningsDoNotChangeTaggingPredicate() {
			List<RouteStep> steps = List.of(transitionStep(false, true), rideStep());

			assertThat(StairAccess.ofItinerary(itinerary(steps, warningsOnly()))).isEqualTo(StairAccess.STEP_FREE);
		}

	}

	@Nested
	@DisplayName("표시용 경로 판정")
	class DisplayJudgment {

		@Test
		@DisplayName("데이터 신뢰도 경고가 있으면 무단차로 단언하지 않는다")
		void dataConfidenceWarningsDemoteDisplayJudgment() {
			// 검증된 전이와 신뢰도 경고를 함께 둔 조합은 현재 플래너가 낼 수 없다 — RAPTOR의 경로
			// 경고는 지나온 전이의 warningCodes를 OR한 것이라 LOW/STALE을 낸 전이는 반드시 미검증이다.
			// 관측된 응답이 아니라, 그런 생산자가 생겼을 때 판정이 조용히 열리지 않게 하는 fail closed
			// 규칙을 고정하는 테스트다.
			List<RouteStep> steps = List.of(transitionStep(false, true), rideStep());

			assertThat(StairAccess.ofItineraryDisplay(itinerary(steps, warningsOnly()))).isEqualTo(StairAccess.UNKNOWN);
		}

		@Test
		@DisplayName("신뢰도 경고는 계단 사실을 뒤집지 않는다")
		void dataConfidenceWarningsKeepStairOnly() {
			List<RouteStep> steps = List.of(transitionStep(true, true), rideStep());

			assertThat(StairAccess.ofItineraryDisplay(itinerary(steps, warningsOnly()))).isEqualTo(StairAccess.STAIR_ONLY);
		}

		@Test
		@DisplayName("스텝이 없으면 무단차라 말할 근거가 없어 UNKNOWN이다")
		void emptyItineraryFailsClosed() {
			assertThat(StairAccess.ofItineraryDisplay(itinerary(List.of(), List.of()))).isEqualTo(StairAccess.UNKNOWN);
			// 태깅 술어는 후보 집합을 흔들지 않도록 종전 결론을 유지한다.
			assertThat(StairAccess.ofItinerary(itinerary(List.of(), List.of()))).isEqualTo(StairAccess.STEP_FREE);
		}

		@Test
		@DisplayName("스텝 판정을 접은 값보다 덜 신중해지지 않는다")
		void isNeverLessCautiousThanFoldedSteps() {
			List<List<RouteStep>> stepCases = List.of(
				List.of(rideStep()),
				List.of(transitionStep(false, true), rideStep(), transitionStep(false, true)),
				List.of(transitionStep(false, false), rideStep(), transitionStep(false, true)),
				List.of(transitionStep(true, true), rideStep(), transitionStep(false, true))
			);

			for (List<RouteStep> steps : stepCases) {
				for (List<RouteWarning> warnings : List.of(
					List.<RouteWarning>of(),
					warningsOnly(),
					List.of(new RouteWarning(RouteWarningCode.STAIR_ONLY_ACCESS))
				)) {
					StairAccess folded = StairAccess.ofStepJudgments(steps.stream().map(StairAccess::ofStep).toList());
					StairAccess judged = StairAccess.ofItineraryDisplay(itinerary(steps, warnings));

					assertThat(judged.merge(folded)).as("%s / %s", steps, warnings).isEqualTo(judged);
				}
			}
		}

		@Test
		@DisplayName("검증된 구간의 판정은 경로 단위 경고에 흔들리지 않는다")
		void stepJudgmentIgnoresItineraryWarnings() {
			RouteStep verified = transitionStep(false, true);

			assertThat(StairAccess.ofStep(verified)).isEqualTo(StairAccess.STEP_FREE);
			assertThat(StairAccess.ofItineraryDisplay(itinerary(List.of(verified, rideStep()), warningsOnly())))
				.isEqualTo(StairAccess.UNKNOWN);
		}
	}

	@Nested
	@DisplayName("#2560 태깅 술어 동치")
	class TaggingPredicateEquivalence {

		@Test
		@DisplayName("판정을 옮긴 뒤에도 구 RouteV2Planner.stairAccess()와 결론이 같다")
		void matchesLegacyPredicate() {
			List<RouteSearchResult> cases = List.of(
				itinerary(List.of(), List.of()),
				itinerary(List.of(), List.of(new RouteWarning(RouteWarningCode.STAIR_ONLY_ACCESS))),
				itinerary(List.of(rideStep()), warningsOnly()),
				itinerary(List.of(transitionStep(true, true), transitionStep(false, false), rideStep()), List.of()),
				itinerary(List.of(transitionStep(false, true), rideStep(), transitionStep(false, true)), List.of()),
				itinerary(List.of(transitionStep(false, false), rideStep()), List.of()),
				itinerary(
					List.of(transitionStep(false, true), rideStep()),
					List.of(new RouteWarning(RouteWarningCode.STAIR_ONLY_ACCESS))
				)
			);

			assertThat(cases).allSatisfy(itinerary ->
				assertThat(StairAccess.ofItinerary(itinerary)).isEqualTo(legacyStairAccess(itinerary)));
		}

		/**
		 * #2590 이전 {@code RouteV2Planner.stairAccess()}. 무단차 대안의 후보 집합은 이 술어로
		 * 정해지므로, 판정을 도메인으로 옮기면서 집합이 바뀌지 않았음을 원본과 대조해 고정한다.
		 */
		private StairAccess legacyStairAccess(RouteSearchResult itinerary) {
			if (itinerary.steps().stream().anyMatch(RouteStep::includesStairs)
				|| itinerary.warnings().stream()
					.anyMatch(warning -> warning.code() == RouteWarningCode.STAIR_ONLY_ACCESS)) {
				return StairAccess.STAIR_ONLY;
			}
			return itinerary.steps().stream().anyMatch(RouteStep::requiresAccessibilityCheck)
				? StairAccess.UNKNOWN
				: StairAccess.STEP_FREE;
		}
	}

	@Nested
	@DisplayName("판정 병합과 신뢰도 분류")
	class MergeAndEvidence {

		@Test
		@DisplayName("병합은 선언 순서가 아니라 명시된 신중함 등급을 따른다")
		void mergeFollowsExplicitCautionRank() {
			List<StairAccess> ascending = List.of(
				StairAccess.NOT_APPLICABLE, StairAccess.STEP_FREE, StairAccess.UNKNOWN, StairAccess.STAIR_ONLY);

			for (int left = 0; left < ascending.size(); left += 1) {
				for (int right = 0; right < ascending.size(); right += 1) {
					StairAccess expected = ascending.get(Math.max(left, right));
					assertThat(ascending.get(left).merge(ascending.get(right))).isEqualTo(expected);
					assertThat(ascending.get(right).merge(ascending.get(left))).isEqualTo(expected);
				}
			}
		}

		@Test
		@DisplayName("계단 사실을 말하는 경고는 미확인 근거가 아니다")
		void stairWarningIsNotUnverifiedEvidence() {
			assertThat(StairAccess.hasUnverifiedEvidence(List.of())).isFalse();
			assertThat(StairAccess.hasUnverifiedEvidence(
				List.of(new RouteWarning(RouteWarningCode.STAIR_ONLY_ACCESS)))).isFalse();
		}

		@Test
		@DisplayName("신뢰도 경고는 미확인 근거로 분류한다")
		void confidenceWarningsAreUnverifiedEvidence() {
			assertThat(StairAccess.hasUnverifiedEvidence(
				List.of(new RouteWarning(RouteWarningCode.STALE_ACCESSIBILITY_DATA)))).isTrue();
			assertThat(StairAccess.hasUnverifiedEvidence(
				List.of(new RouteWarning(RouteWarningCode.LOW_DATA_CONFIDENCE)))).isTrue();
		}
	}

	private static List<RouteWarning> warningsOnly() {
		return List.of(
			new RouteWarning(RouteWarningCode.STALE_ACCESSIBILITY_DATA),
			new RouteWarning(RouteWarningCode.LOW_DATA_CONFIDENCE)
		);
	}

	private static RouteStep rideStep() {
		return new RouteStep(
			2, "ride", "2호선 승차", "시간표 기준 이동", "L2", "2호선", "S1", "S2",
			5, 0, false, "UNKNOWN", false,
			EtaSource.PLANNED.name(), "TIMETABLE", "시간표", List.of(), null, null, null, null
		);
	}

	private static RouteStep transitionStep(boolean includesStairs, boolean verified) {
		return new RouteStep(
			1, "entry", "2호선 접근 동선 확인", "승하차 접근성 확인", "L2", "2호선", "S1", "S1",
			2, 40, includesStairs,
			includesStairs ? "STAIR_ONLY" : verified ? "STEP_FREE" : "UNKNOWN",
			!verified,
			EtaSource.PLANNED.name(), "TIMETABLE", verified ? "검증됨" : "확인 필요",
			List.of(), null, null, null, null
		);
	}

	private static RouteSearchResult itinerary(List<RouteStep> steps, List<RouteWarning> warnings) {
		return new RouteSearchResult(
			"route-1", "S1", "출발역", "S2", "도착역", MobilityType.WHEELCHAIR, RouteSearchStatus.FOUND,
			"L2", "2호선", 10, steps, warnings, List.of(), LocalDateTime.of(2026, 7, 26, 9, 0)
		);
	}
}
