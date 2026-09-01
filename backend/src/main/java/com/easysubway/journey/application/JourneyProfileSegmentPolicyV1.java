package com.easysubway.journey.application;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Whole-second, inclusive departure-window compression for JOURNEY_PROFILE_V1.
 *
 * <p>A breakpoint's ordered candidate set applies through its ready second. Segments use an
 * exclusive end so a requested inclusive terminal second becomes {@code latestReadyAt + 1s}.</p>
 */
public final class JourneyProfileSegmentPolicyV1 {

	private JourneyProfileSegmentPolicyV1() {
	}

	public static Result compress(JourneyRaptorQuery.DepartBetween range, List<Breakpoint> breakpoints) {
		JourneyRaptorQuery.DepartBetween requiredRange = Objects.requireNonNull(range, "range");
		Instant earliest = requireWholeSecond(requiredRange.earliestReadyAt(), "earliestReadyAt");
		Instant latest = requireWholeSecond(requiredRange.latestReadyAt(), "latestReadyAt");
		Instant terminal = nextSecond(latest, "latestReadyAt");
		List<Breakpoint> ascending = ascendingBreakpoints(breakpoints, earliest, latest);

		List<Segment> segments = new ArrayList<>();
		Instant start = earliest;
		for (Breakpoint breakpoint : ascending) {
			Instant end = nextSecond(breakpoint.readyAt(), "breakpoint.readyAt");
			append(segments, new Segment(start, end, breakpoint.candidateIds()));
			start = end;
		}
		if (start.isBefore(terminal)) {
			append(segments, new Segment(start, terminal, List.of()));
		}
		return new Result(segments);
	}

	private static List<Breakpoint> ascendingBreakpoints(
		List<Breakpoint> breakpoints,
		Instant earliest,
		Instant latest
	) {
		List<Breakpoint> requiredBreakpoints = List.copyOf(Objects.requireNonNull(breakpoints, "breakpoints"));
		for (Breakpoint breakpoint : requiredBreakpoints) {
			Instant readyAt = breakpoint.readyAt();
			if (readyAt.isBefore(earliest) || readyAt.isAfter(latest)) {
				throw new IllegalArgumentException("breakpoint.readyAt must be within the requested range");
			}
		}
		if (requiredBreakpoints.size() < 2) return requiredBreakpoints;

		int direction = requiredBreakpoints.get(0).readyAt().compareTo(requiredBreakpoints.get(1).readyAt());
		if (direction == 0) throw new IllegalArgumentException("breakpoint readyAt values must be unique");
		for (int index = 2; index < requiredBreakpoints.size(); index += 1) {
			int comparison = requiredBreakpoints.get(index - 1).readyAt()
				.compareTo(requiredBreakpoints.get(index).readyAt());
			if (comparison == 0 || Integer.signum(comparison) != Integer.signum(direction)) {
				throw new IllegalArgumentException("breakpoints must be strictly ascending or descending");
			}
		}
		if (direction < 0) return requiredBreakpoints;
		List<Breakpoint> ascending = new ArrayList<>(requiredBreakpoints);
		java.util.Collections.reverse(ascending);
		return List.copyOf(ascending);
	}

	private static void append(List<Segment> segments, Segment next) {
		if (!segments.isEmpty()) {
			Segment previous = segments.getLast();
			if (previous.journeyIds().equals(next.journeyIds())) {
				segments.set(segments.size() - 1, new Segment(previous.readyFromInclusive(),
					next.readyUntilExclusive(), previous.journeyIds()));
				return;
			}
		}
		segments.add(next);
	}

	private static Instant requireWholeSecond(Instant value, String name) {
		Instant requiredValue = Objects.requireNonNull(value, name);
		if (requiredValue.getNano() != 0) {
			throw new IllegalArgumentException(name + " must be a whole second");
		}
		return requiredValue;
	}

	private static Instant nextSecond(Instant value, String name) {
		try {
			return value.plusSeconds(1);
		} catch (DateTimeException exception) {
			throw new IllegalArgumentException(name + " cannot have an exclusive terminal", exception);
		}
	}

	public record Breakpoint(Instant readyAt, List<String> candidateIds) {
		public Breakpoint {
			readyAt = requireWholeSecond(readyAt, "readyAt");
			candidateIds = orderedUniqueIds(candidateIds, "candidateIds");
		}
	}

	public record Segment(Instant readyFromInclusive, Instant readyUntilExclusive, List<String> journeyIds) {
		public Segment {
			readyFromInclusive = requireWholeSecond(readyFromInclusive, "readyFromInclusive");
			readyUntilExclusive = requireWholeSecond(readyUntilExclusive, "readyUntilExclusive");
			if (!readyUntilExclusive.isAfter(readyFromInclusive)) {
				throw new IllegalArgumentException("readyUntilExclusive must be after readyFromInclusive");
			}
			journeyIds = orderedUniqueIds(journeyIds, "journeyIds");
		}
	}

	public record Result(List<Segment> segments) {
		public Result {
			segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
			if (segments.isEmpty()) throw new IllegalArgumentException("segments must not be empty");
			for (int index = 1; index < segments.size(); index += 1) {
				if (!segments.get(index - 1).readyUntilExclusive().equals(segments.get(index).readyFromInclusive())) {
					throw new IllegalArgumentException("segments must be contiguous");
				}
			}
		}

		public boolean allEmpty() {
			return segments.stream().allMatch(segment -> segment.journeyIds().isEmpty());
		}
	}

	private static List<String> orderedUniqueIds(List<String> ids, String name) {
		List<String> requiredIds = List.copyOf(Objects.requireNonNull(ids, name));
		for (String id : requiredIds) {
			if (id == null || id.isBlank()) throw new IllegalArgumentException(name + " must contain nonblank IDs");
		}
		if (requiredIds.stream().distinct().count() != requiredIds.size()) {
			throw new IllegalArgumentException(name + " must not contain duplicate IDs");
		}
		return requiredIds;
	}
}
