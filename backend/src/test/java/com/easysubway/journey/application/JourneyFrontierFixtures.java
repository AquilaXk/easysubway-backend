package com.easysubway.journey.application;

import com.easysubway.journey.application.JourneyFrontierPolicyV1.FeasibleCandidate;
import com.easysubway.journey.application.JourneyProfileRaptorPort.MinimumTransferSeconds;
import java.time.Instant;

/** Frontier 정책 테스트가 함께 쓰는 후보 fixture. 모든 후보는 MinimumTransferSeconds 여유를 가진다. */
final class JourneyFrontierFixtures {

	static final Instant START = Instant.parse("2026-09-02T00:00:00Z");

	private JourneyFrontierFixtures() {
	}

	static FeasibleCandidate candidate(String id, long departure, long arrival, long transfers,
		long walkingSeconds, long walkingDistanceMeters, long accessibilityBurden, long slack) {
		return new FeasibleCandidate(id, START.plusSeconds(departure), START.plusSeconds(arrival), transfers,
			walkingSeconds, walkingDistanceMeters, accessibilityBurden, new MinimumTransferSeconds(slack));
	}
}
