package com.easysubway.route.application.service;

import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import java.time.LocalDate;
import java.util.List;

/**
 * #494: 패키지 밖의 수동 측정 도구({@code JourneyEndpointLatencyMeasurement})가 수도권 실데이터 fixture와 모바일 대표
 * 프로필 질의를 읽는 공개 통로. fixture 자체({@link CapitalRealDerivedFixture})와 대표 질의
 * ({@link ProfileSearchWithinResourcePolicyTest#cases()})는 그대로 두고 값만 노출한다.
 */
public final class CapitalEndpointFixture {

	private CapitalEndpointFixture() {
	}

	public record RepresentativeCase(
		String mode, String origin, String destination, String localTime,
		JourneyRequest.MobilityProfile profile, JourneyRequest.ConstraintMode constraint
	) {
	}

	public static RouteTimetable timetable() {
		return CapitalRealDerivedFixture.load();
	}

	public static List<String> stations() {
		return CapitalRealDerivedFixture.stations();
	}

	public static LocalDate serviceDate() {
		return CapitalRealDerivedFixture.SERVICE_DATE;
	}

	public static List<RepresentativeCase> representativeProfileCases() {
		return ProfileSearchWithinResourcePolicyTest.cases().stream()
			.map(testCase -> new RepresentativeCase(testCase.mode(), testCase.origin(), testCase.destination(),
				testCase.localTime(), testCase.profile(), testCase.constraint()))
			.toList();
	}
}
