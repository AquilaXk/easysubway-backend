package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class JourneyProfileResourcePolicyTest {

	private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

	@Test
	void selectsDeadlineAndCostFromOneVersionedPolicySnapshot() {
		var policy = policy(Duration.ofSeconds(5));

		assertThat(policy.deadlineFor(new JourneyRaptorQuery.DepartAt(NOW)))
			.isEqualTo(Duration.ofSeconds(2));
		assertThat(policy.deadlineFor(new JourneyRaptorQuery.DepartBetween(NOW, NOW.plusSeconds(60))))
			.isEqualTo(Duration.ofSeconds(5));
		assertThat(policy.deadlineFor(new JourneyRaptorQuery.ArriveBy(NOW, NOW.plusSeconds(60))))
			.isEqualTo(Duration.ofSeconds(5));
		assertThat(policy.deadlineFor(new JourneyRaptorQuery.LastConnection(LocalDate.of(2026, 9, 1))))
			.isEqualTo(Duration.ofSeconds(8));
		assertThat(policy.costUnitsFor(new JourneyRaptorQuery.DepartAt(NOW))).isOne();
		assertThat(policy.costUnitsFor(new JourneyRaptorQuery.DepartBetween(NOW, NOW.plusSeconds(60))))
			.isEqualTo(2);
		assertThat(policy.costUnitsFor(new JourneyRaptorQuery.ArriveBy(NOW, NOW.plusSeconds(60))))
			.isEqualTo(3);
		assertThat(policy.costUnitsFor(new JourneyRaptorQuery.LastConnection(LocalDate.of(2026, 9, 1))))
			.isEqualTo(4);
		assertThat(policy.profilePlanningLimits()).isEqualTo(
			new JourneyProfileResourcePolicy.ProfilePlanningLimits(1_000, 8, 16, 32));
	}

	@Test
	void rejectsARequestCostThatCannotFitItsSessionCeiling() {
		assertThatThrownBy(() -> new JourneyProfileResourcePolicy(
			new JourneyProfileResourcePolicy.Identity("RAPTOR_RESOURCE_POLICY_V1", "1.0.0", "d".repeat(64)),
			Duration.ofHours(1), 2, 1_000, 8, 16, 32, Duration.ofHours(1),
			Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(8), 1, 2, 3, 5, 4))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("request costs must fit maxCostUnitsPerSession");
	}

	static JourneyProfileResourcePolicy policy(Duration profileDeadline) {
		return new JourneyProfileResourcePolicy(
			new JourneyProfileResourcePolicy.Identity("RAPTOR_RESOURCE_POLICY_V1", "1.0.0", "d".repeat(64)),
			Duration.ofHours(1), 2, 1_000, 8, 16, 32, Duration.ofHours(1),
			Duration.ofSeconds(2), profileDeadline, Duration.ofSeconds(8), 1, 2, 3, 4, 10);
	}
}
