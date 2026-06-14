package com.easysubway.route.domain;

import com.easysubway.profile.domain.MobilityType;

public record RouteProfileWeight(
	int baseAccessCost,
	int lowDataConfidencePenalty,
	int stairOnlyAccessPenalty,
	boolean blocksStairOnlyAccess,
	String entryGuidance,
	String exitGuidance
) {

	public static RouteProfileWeight from(MobilityType mobilityType) {
		// 점수는 시간 예측이 아니라 같은 경로 후보를 비교하기 위한 접근 부담 비용이다.
		return switch (mobilityType) {
			case SENIOR -> new RouteProfileWeight(
				18,
				24,
				38,
				false,
				"계단을 피하고 이동 거리가 짧은 출구를 먼저 확인합니다.",
				"도착역에서 계단을 피할 수 있는 출구와 짧은 동선을 확인합니다."
			);
			case STROLLER -> new RouteProfileWeight(
				20,
				28,
				48,
				false,
				"엘리베이터와 넓은 통로가 있는 출구를 먼저 확인합니다.",
				"도착역에서 엘리베이터와 넓은 통로가 있는 출구를 확인합니다."
			);
			case WHEELCHAIR -> new RouteProfileWeight(
				24,
				36,
				100,
				true,
				"엘리베이터, 리프트, 경사로 연결을 먼저 확인합니다.",
				"도착역에서 엘리베이터, 리프트, 경사로 연결 출구를 확인합니다."
			);
			case PREGNANT -> new RouteProfileWeight(
				17,
				26,
				42,
				false,
				"엘리베이터와 짧은 이동 동선을 먼저 확인합니다.",
				"도착역에서 짧게 걸을 수 있는 엘리베이터 출구를 확인합니다."
			);
			case TEMPORARY_INJURY -> new RouteProfileWeight(
				22,
				30,
				52,
				false,
				"계단을 피하고 쉬어 갈 수 있는 동선을 먼저 확인합니다.",
				"도착역에서 계단을 피하고 천천히 이동할 수 있는 출구를 확인합니다."
			);
			case LUGGAGE -> new RouteProfileWeight(
				16,
				22,
				34,
				false,
				"엘리베이터와 넓은 출구 동선을 먼저 확인합니다.",
				"도착역에서 큰 짐을 들고 지나가기 쉬운 출구를 확인합니다."
			);
		};
	}
}
