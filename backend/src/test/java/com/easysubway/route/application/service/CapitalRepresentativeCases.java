package com.easysubway.route.application.service;

import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.ServiceDayResolver;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/**
 * 모바일이 실제로 보내는 프로필 질의의 수도권 대표 출발·도착 쌍({@link CapitalRealDerivedFixture} 위). 프로필 탐색 정책
 * 테스트, 엔진 벤치마크, 대표 질의 golden 코퍼스가 같은 목록을 읽는다.
 */
final class CapitalRepresentativeCases {

	private CapitalRepresentativeCases() {
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
