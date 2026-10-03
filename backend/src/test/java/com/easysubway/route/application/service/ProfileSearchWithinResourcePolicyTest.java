package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.JourneyProfileRaptorPort;
import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyRaptorPruningInventoryV1;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.ServiceDayResolver;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #461: 모바일이 실제로 보내는 프로필 질의(출발 시간대 30분, 막차, 환승 3회, 대안 3개)와 API의 도착 희망 질의(정책 최대
 * 시간 범위 1시간)가 운영 자원 정책 안에서 결과를 낸다. 입력은 실제 서버 경로 번들에서 자른 수도권 평일 fixture
 * ({@link CapitalRealDerivedFixture})다. 정책 값은 성능 게이트가 platform 출처와 함께 고정한
 * {@link JourneyEnginePerformanceGateTest#CANDIDATE_POLICY_LIMITS} 하나만 쓴다.
 */
@DisplayName("#461 프로필 탐색이 운영 자원 정책 안에서 결과를 낸다")
class ProfileSearchWithinResourcePolicyTest {

	static final JourneyProfileResourcePolicy.ProfilePlanningLimits POLICY =
		JourneyEnginePerformanceGateTest.CANDIDATE_POLICY_LIMITS;

	@Test
	@DisplayName("대표 수도권 질의가 거절 없이 경로를 찾는다")
	void representativeCapitalQueriesFindJourneysWithinThePolicy() {
		var runtime = RaptorRouteBundleRuntimeView.compile("a".repeat(64), 1, CapitalRealDerivedFixture.load());
		var adapter = new JourneyProfileRaptorAdapter();
		List<String> failures = new ArrayList<>();
		List<String> windowViolations = new ArrayList<>();
		int index = 0;
		for (Case testCase : cases()) {
			var query = testCase.query(index++);
			var result = adapter.planRuntime(query, runtime, null, POLICY);
			String outcome = describe(result);
			System.out.println("#461 policy " + testCase.mode() + " " + testCase.origin() + "->" + testCase.destination()
				+ " " + testCase.profile() + " " + outcome);
			if (!outcome.startsWith("found")) failures.add(testCase + " -> " + outcome);
			windowViolations.addAll(windowViolations(testCase, result));
		}
		assertThat(failures).as("운영 정책 안에서 경로를 찾지 못한 질의").isEmpty();
		assertThat(windowViolations).as("30분 대안 창을 벗어난 결과").isEmpty();
	}

	@Test
	@DisplayName("실데이터 fixture는 고정된 원본 번들에서 자른 것이다")
	void capitalFixtureComesFromThePinnedSourceBundle() {
		// 로더가 fixture sha256과 provenance(번들 ID·release·payload digest·data workflow run)를 대조한다.
		var timetable = CapitalRealDerivedFixture.load();
		assertThat(timetable.transitTrips()).hasSize(9_691);
		assertThat(CapitalRealDerivedFixture.stations()).hasSize(656);
		assertThat(timetable.routeAccessData().transferRules()).hasSize(297);
	}

	/**
	 * 결과 의미(#461): 출발 시간대의 각 시점은 가장 이른 도착 + 30분 안, 도착 희망·막차는 가장 늦은 준비 시각 - 30분
	 * 이후의 여정만 담는다.
	 */
	static List<String> windowViolations(Case testCase, JourneyProfileRaptorPort.PlanningResult result) {
		long window = JourneyRaptorPruningInventoryV1.PROFILE_ALTERNATIVE_WINDOW_SECONDS;
		List<String> violations = new ArrayList<>();
		if (!(result instanceof JourneyProfileRaptorPort.PlanningResult.Planned planned)) return violations;
		switch (planned.temporalPlan()) {
			case JourneyProfileRaptorPort.DepartureWindowPlan plan -> {
				for (var point : plan.points()) {
					long first = point.itineraries().stream().mapToLong(it -> it.plannedArrivalAtDestination().getEpochSecond())
						.min().orElseThrow();
					long last = point.itineraries().stream().mapToLong(it -> it.plannedArrivalAtDestination().getEpochSecond())
						.max().orElseThrow();
					if (last - first > window) violations.add(testCase + " point " + point.readyAt() + " arrival span " + (last - first));
				}
			}
			case JourneyProfileRaptorPort.ArriveByPlan plan -> violations.addAll(readinessSpan(testCase, plan.result(), window));
			case JourneyProfileRaptorPort.LastConnectionPlan plan -> violations.addAll(readinessSpan(testCase, plan.result(), window));
		}
		return violations;
	}

	private static List<String> readinessSpan(Case testCase, JourneyProfileRaptorPort.ReversePlan result, long window) {
		if (!(result instanceof JourneyProfileRaptorPort.ReversePlan.Found found)) return List.of();
		long first = found.itineraries().stream().mapToLong(it -> it.plannedReadyAt().getEpochSecond()).min().orElseThrow();
		long last = found.itineraries().stream().mapToLong(it -> it.plannedReadyAt().getEpochSecond()).max().orElseThrow();
		return last - first > window ? List.of(testCase + " readiness span " + (last - first)) : List.of();
	}

	static List<Case> cases() {
		var standard = JourneyRequest.MobilityProfile.STANDARD;
		var stepFree = JourneyRequest.MobilityProfile.STEP_FREE;
		var none = JourneyRequest.ConstraintMode.NONE;
		var strict = JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE;
		return List.of(
			new Case("DEPART_BETWEEN", CapitalRealDerivedFixture.GANGNAM, CapitalRealDerivedFixture.SEOUL, "08:00", standard, none),
			new Case("DEPART_BETWEEN", CapitalRealDerivedFixture.JAMSIL, CapitalRealDerivedFixture.HONGIK_UNIV, "08:00", standard, none),
			new Case("DEPART_BETWEEN", CapitalRealDerivedFixture.SUWON, CapitalRealDerivedFixture.GANGNAM, "07:30", standard, none),
			new Case("DEPART_BETWEEN", CapitalRealDerivedFixture.BUPYEONG, CapitalRealDerivedFixture.YEOUIDO, "18:00", stepFree, none),
			new Case("DEPART_BETWEEN", CapitalRealDerivedFixture.NOWON, CapitalRealDerivedFixture.PANGYO, "12:00", stepFree, strict),
			new Case("DEPART_BETWEEN", CapitalRealDerivedFixture.GIMPO_AIRPORT, CapitalRealDerivedFixture.KONKUK_UNIV, "21:30", standard, none),
			// 김포공항은 이 fixture에서 30분 안 출발이 가장 많은 역(59편)이라 시점 수 한도를 확인한다.
			new Case("DEPART_BETWEEN", CapitalRealDerivedFixture.GIMPO_AIRPORT, CapitalRealDerivedFixture.GANGNAM, "08:00", standard, none),
			new Case("LAST_CONNECTION", CapitalRealDerivedFixture.GIMPO_AIRPORT, CapitalRealDerivedFixture.JAMSIL, null, standard, none),
			new Case("LAST_CONNECTION", CapitalRealDerivedFixture.GANGNAM, CapitalRealDerivedFixture.NOWON, null, standard, none),
			new Case("LAST_CONNECTION", CapitalRealDerivedFixture.HONGIK_UNIV, CapitalRealDerivedFixture.JAMSIL, null, standard, none),
			new Case("LAST_CONNECTION", CapitalRealDerivedFixture.SEOUL, CapitalRealDerivedFixture.SUWON, null, stepFree, none),
			new Case("LAST_CONNECTION", CapitalRealDerivedFixture.SADANG, CapitalRealDerivedFixture.BUPYEONG, null, stepFree, strict),
			new Case("ARRIVE_BY", CapitalRealDerivedFixture.SINDORIM, CapitalRealDerivedFixture.WANGSIMNI, "07:30", standard, none),
			new Case("ARRIVE_BY", CapitalRealDerivedFixture.EXPRESS_BUS_TERMINAL, CapitalRealDerivedFixture.JONGNO_3GA, "17:00", stepFree, none));
	}

	static String describe(JourneyProfileRaptorPort.PlanningResult result) {
		var metrics = result.planningMetrics();
		String measured = " work=" + metrics.workConsumed() + " stateLabels=" + metrics.peakStateLabels()
			+ " destinationLabels=" + metrics.peakDestinationLabels() + " breakpoints=" + metrics.reservedProfileBreakpoints();
		return switch (result) {
			case JourneyProfileRaptorPort.PlanningResult.AdmissionRejected ignored -> "rejected:MAX_ESTIMATED_WORK" + measured;
			case JourneyProfileRaptorPort.PlanningResult.CapacityExceeded capacity -> "rejected:" + capacity.dimension() + measured;
			case JourneyProfileRaptorPort.PlanningResult.Planned planned -> switch (planned.temporalPlan()) {
				case JourneyProfileRaptorPort.DepartureWindowPlan plan -> plan.points().isEmpty() ? "notFound" + measured
					: "found points=" + plan.points().size() + measured;
				case JourneyProfileRaptorPort.ArriveByPlan plan -> plan.result() instanceof JourneyProfileRaptorPort.ReversePlan.Found found
					? "found itineraries=" + found.itineraries().size() + measured : "notFound" + measured;
				case JourneyProfileRaptorPort.LastConnectionPlan plan -> plan.result() instanceof JourneyProfileRaptorPort.ReversePlan.Found found
					? "found itineraries=" + found.itineraries().size() + measured : "notFound" + measured;
			};
		};
	}

	record Case(
		String mode, String origin, String destination, String localTime,
		JourneyRequest.MobilityProfile profile, JourneyRequest.ConstraintMode constraint
	) {
		JourneyRaptorQuery query(int index) {
			JourneyRaptorQuery.TemporalQuery temporal = switch (mode) {
				case "DEPART_BETWEEN" -> {
					Instant start = instant(localTime);
					yield new JourneyRaptorQuery.DepartBetween(start, start.plusSeconds(1_800));
				}
				case "ARRIVE_BY" -> {
					Instant start = instant(localTime);
					yield new JourneyRaptorQuery.ArriveBy(start, start.plusSeconds(3_600));
				}
				default -> new JourneyRaptorQuery.LastConnection(CapitalRealDerivedFixture.SERVICE_DATE);
			};
			return new JourneyRaptorQuery(JourneyProfileFullCorpusRunner.requestId("policy-" + index, origin, destination,
				temporal), origin, destination, temporal, JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
				JourneyRequest.WalkingPace.STANDARD, profile, constraint, 3, 3, () -> false);
		}

		private static Instant instant(String localTime) {
			return CapitalRealDerivedFixture.SERVICE_DATE.atTime(LocalTime.parse(localTime))
				.atZone(ServiceDayResolver.ZONE).toInstant();
		}
	}
}
