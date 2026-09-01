package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class JourneyProfileSegmentPolicyV1Test {

	private static final Instant EARLIEST = Instant.parse("2026-09-02T08:00:00Z");
	private static final Instant LATEST = Instant.parse("2026-09-02T08:30:00Z");

	@Test
	void coversTheInclusiveRangeAcrossTwoSetChangesAndTrailingNoService() {
		var result = JourneyProfileSegmentPolicyV1.compress(range(), List.of(
			breakpoint("2026-09-02T08:20:00Z", "journey-b"),
			breakpoint("2026-09-02T08:10:00Z", "journey-a")));

		assertThat(result.segments()).containsExactly(
			segment("2026-09-02T08:00:00Z", "2026-09-02T08:10:01Z", "journey-a"),
			segment("2026-09-02T08:10:01Z", "2026-09-02T08:20:01Z", "journey-b"),
			segment("2026-09-02T08:20:01Z", "2026-09-02T08:30:01Z"));
		assertThat(result.allEmpty()).isFalse();
	}

	@Test
	void coversTheLeadingRangeWithTheEarliestBreakpointSet() {
		var result = JourneyProfileSegmentPolicyV1.compress(range(), List.of(
			breakpoint("2026-09-02T08:12:00Z", "journey-a")));

		assertThat(result.segments()).containsExactly(
			segment("2026-09-02T08:00:00Z", "2026-09-02T08:12:01Z", "journey-a"),
			segment("2026-09-02T08:12:01Z", "2026-09-02T08:30:01Z"));
	}

	@Test
	void mergesAdjacentEqualOrderedCandidateSets() {
		var result = JourneyProfileSegmentPolicyV1.compress(range(), List.of(
			breakpoint("2026-09-02T08:10:00Z", "journey-a", "journey-b"),
			breakpoint("2026-09-02T08:20:00Z", "journey-a", "journey-b")));

		assertThat(result.segments()).containsExactly(
			segment("2026-09-02T08:00:00Z", "2026-09-02T08:20:01Z", "journey-a", "journey-b"),
			segment("2026-09-02T08:20:01Z", "2026-09-02T08:30:01Z"));
	}

	@Test
	void reportsAllEmptyWhenNoBreakpointsExist() {
		var result = JourneyProfileSegmentPolicyV1.compress(range(), List.of());

		assertThat(result.segments()).containsExactly(
			segment("2026-09-02T08:00:00Z", "2026-09-02T08:30:01Z"));
		assertThat(result.allEmpty()).isTrue();
	}

	@Test
	void rejectsFractionalOutOfRangeDuplicateAndUnorderedFacts() {
		assertThatIllegalArgumentException().isThrownBy(() -> JourneyProfileSegmentPolicyV1.compress(
			new JourneyRaptorQuery.DepartBetween(EARLIEST.plusNanos(1), LATEST), List.of()));
		assertThatIllegalArgumentException().isThrownBy(() -> JourneyProfileSegmentPolicyV1.compress(range(), List.of(
			breakpoint("2026-09-02T07:59:59Z", "journey-a"))));
		assertThatIllegalArgumentException().isThrownBy(() -> JourneyProfileSegmentPolicyV1.compress(range(), List.of(
			breakpoint("2026-09-02T08:10:00.001Z", "journey-a"))));
		assertThatIllegalArgumentException().isThrownBy(() -> JourneyProfileSegmentPolicyV1.compress(range(), List.of(
			breakpoint("2026-09-02T08:10:00Z", "journey-a", "journey-a"))));
		assertThatIllegalArgumentException().isThrownBy(() -> JourneyProfileSegmentPolicyV1.compress(range(), List.of(
			breakpoint("2026-09-02T08:10:00Z", "journey-a"),
			breakpoint("2026-09-02T08:20:00Z", "journey-b"),
			breakpoint("2026-09-02T08:15:00Z", "journey-c"))));
	}

	private static JourneyRaptorQuery.DepartBetween range() {
		return new JourneyRaptorQuery.DepartBetween(EARLIEST, LATEST);
	}

	private static JourneyProfileSegmentPolicyV1.Breakpoint breakpoint(String readyAt, String... candidateIds) {
		return new JourneyProfileSegmentPolicyV1.Breakpoint(Instant.parse(readyAt), List.of(candidateIds));
	}

	private static JourneyProfileSegmentPolicyV1.Segment segment(
		String from,
		String until,
		String... candidateIds
	) {
		return new JourneyProfileSegmentPolicyV1.Segment(
			Instant.parse(from), Instant.parse(until), List.of(candidateIds));
	}
}
