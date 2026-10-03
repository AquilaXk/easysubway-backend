package com.easysubway.route.application.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Test-only exhaustive oracle over normalized, date-qualified ride and access facts.
 *
 * <p>This deliberately has no dependency on planner, frontier, pruning, or public-result code.
 * It enumerates finite ride chains only up to the query's explicit transfer budget.</p>
 */
final class JourneyProfileExactOracle {

	/**
	 * #461 제품 계약: 한 시점의 대안 창 길이. 출발 시간대의 각 시점은 가장 이른 도착 + 창 안, 도착 희망·막차는
	 * 가장 늦은 준비 시각 - 창 안의 여정만 파레토 집합에 넣는다. 엔진 상수와 같아야 한다(테스트로 고정).
	 */
	static final long ALTERNATIVE_WINDOW_SECONDS = 30 * 60;

	/** 창 없는 프로필 파레토 집합(준비 시각도 기준). */
	List<Candidate> solve(Query query, List<Ride> rides, List<Access> accesses) {
		return solve(query, rides, accesses, ReadyAt.PROFILE, Window.NONE);
	}

	/** 창 없는 고정 준비 시각 파레토 집합. */
	List<Candidate> solvePoint(Query query, List<Ride> rides, List<Access> accesses) {
		return solve(query, rides, accesses, ReadyAt.FIXED, Window.NONE);
	}

	/** 도착 희망·막차: 가장 늦은 준비 시각 - 창보다 늦게 준비하는 여정만 두고 파레토 집합을 만든다. */
	List<Candidate> solveLatestReadyWindow(Query query, List<Ride> rides, List<Access> accesses) {
		return solve(query, rides, accesses, ReadyAt.PROFILE, Window.LATEST_READY);
	}

	/** 출발 시간대의 한 시점: 가장 이른 도착 + 창보다 늦게 도착하지 않는 여정만 두고 파레토 집합을 만든다. */
	List<Candidate> solvePointArrivalWindow(Query query, List<Ride> rides, List<Access> accesses) {
		return solve(query, rides, accesses, ReadyAt.FIXED, Window.EARLIEST_ARRIVAL);
	}

	private List<Candidate> solve(Query query, List<Ride> rides, List<Access> accesses, ReadyAt readyAtMode, Window window) {
		Objects.requireNonNull(query, "query");
		rides = List.copyOf(Objects.requireNonNull(rides, "rides"));
		accesses = List.copyOf(Objects.requireNonNull(accesses, "accesses"));
		Work work = new Work(query);
		work.consume();
		Map<String, Ride> rideIdentities = new LinkedHashMap<>();
		for (Ride ride : rides) {
			work.consume();
			if (rideIdentities.putIfAbsent(ride.identity(), ride) != null) {
				throw new IllegalArgumentException("duplicate ride identity");
			}
		}
		Map<String, Access> accessIdentities = new LinkedHashMap<>();
		for (Access access : accesses) {
			work.consume();
			if (accessIdentities.putIfAbsent(access.id(), access) != null) {
				throw new IllegalArgumentException("duplicate access identity");
			}
		}
		List<Candidate> candidates = new ArrayList<>();
		// #454: 여정은 출발역 승강장에서 바로 탄다(진입 이동 없음).
		for (Ride first : rides) {
			work.consume();
			if (!first.pickupAllowed() || !first.fromStationId().equals(query.originStationId())) continue;
			Instant readyAt = lastDeparture(first, query.boardingSlackSeconds());
			if (readyAt.isBefore(query.earliestReadyAt())) continue;
			enumerate(query, rides, accesses, List.of(first), List.of(), candidates, work, readyAtMode);
		}
		return pareto(withinWindow(candidates, window), work);
	}

	/** 창은 실행 가능한 모든 여정에서 정한다. 파레토 계산 전에 거른다. */
	private static List<Candidate> withinWindow(List<Candidate> candidates, Window window) {
		if (window == Window.NONE || candidates.isEmpty()) return candidates;
		if (window == Window.EARLIEST_ARRIVAL) {
			Instant last = candidates.stream().map(Candidate::arrivalAtDestination).min(Comparator.naturalOrder())
				.orElseThrow().plusSeconds(ALTERNATIVE_WINDOW_SECONDS);
			return candidates.stream().filter(candidate -> !candidate.arrivalAtDestination().isAfter(last)).toList();
		}
		Instant first = candidates.stream().map(Candidate::readyAt).max(Comparator.naturalOrder())
			.orElseThrow().minusSeconds(ALTERNATIVE_WINDOW_SECONDS);
		return candidates.stream().filter(candidate -> !candidate.readyAt().isBefore(first)).toList();
	}

	private static void enumerate(
		Query query,
		List<Ride> rides,
		List<Access> accesses,
		List<Ride> chain,
		List<Access> chainAccesses,
		List<Candidate> candidates,
		Work work,
		ReadyAt readyAtMode
	) {
		Ride last = chain.getLast();
		// #454: 도착역 승강장에 내리면 도착이다(하차 이동 없음).
		if (last.toStationId().equals(query.destinationStationId()) && last.dropOffAllowed()) {
			work.consume();
			Instant readyAt = lastDeparture(chain.getFirst(), query.boardingSlackSeconds());
			Instant arrivalAt = last.arrivalAt();
			if (!readyAt.isBefore(query.earliestReadyAt()) && !arrivalAt.isAfter(query.arrivalDeadline())) {
				work.consume();
				candidates.add(candidate(chain, chainAccesses,
					readyAtMode == ReadyAt.FIXED ? query.earliestReadyAt() : readyAt, arrivalAt, query.boardingSlackSeconds()));
			}
		}
		if (chain.size() - 1 >= query.maxTransfers()) return;
		for (Ride next : rides) {
			work.consume();
			if (!next.pickupAllowed()) continue;
			for (Access transfer : accesses) {
				work.consume();
				if (!matchesTransfer(last, next, transfer)) continue;
				Instant latestBoarding = last.arrivalAt().plusSeconds(transfer.durationSeconds())
					.plusSeconds(query.boardingSlackSeconds());
				if (next.departureAt().isBefore(latestBoarding)) continue;
				List<Ride> nextChain = new ArrayList<>(chain);
				nextChain.add(next);
				List<Access> nextAccesses = new ArrayList<>(chainAccesses);
				nextAccesses.add(transfer);
			enumerate(query, rides, accesses, List.copyOf(nextChain), List.copyOf(nextAccesses), candidates, work, readyAtMode);
			}
		}
	}

	private static List<Candidate> pareto(List<Candidate> candidates, Work work) {
		Map<String, Candidate> canonicalByPath = new LinkedHashMap<>();
		for (Candidate candidate : candidates) {
			work.consume();
			if (canonicalByPath.putIfAbsent(candidate.pathIdentity(), candidate) != null) {
				throw new IllegalArgumentException("duplicate route identity: " + candidate.pathIdentity());
			}
		}
		List<Candidate> frontier = new ArrayList<>();
		for (Candidate candidate : canonicalByPath.values()) {
			boolean dominated = false;
			for (Candidate other : canonicalByPath.values()) {
				work.consume();
				if (other != candidate && dominates(other, candidate)) {
					dominated = true;
					break;
				}
			}
			if (dominated) continue;
			frontier.add(candidate);
		}
		frontier.sort(Comparator.comparing(Candidate::pathIdentity));
		return List.copyOf(frontier);
	}

	private static boolean dominates(Candidate left, Candidate right) {
		return !left.readyAt().isBefore(right.readyAt())
			&& !left.arrivalAtDestination().isAfter(right.arrivalAtDestination())
			&& left.transfersUsed() <= right.transfersUsed()
			&& left.walkingSeconds() <= right.walkingSeconds()
			&& left.walkingDistanceMeters() <= right.walkingDistanceMeters()
			&& left.accessibilityBurden() <= right.accessibilityBurden()
			&& ConnectionSlack.compare(left.minimumConnectionSlack(), right.minimumConnectionSlack()) >= 0
			&& (!left.readyAt().equals(right.readyAt()) || !left.arrivalAtDestination().equals(right.arrivalAtDestination())
				|| left.transfersUsed() != right.transfersUsed() || left.walkingSeconds() != right.walkingSeconds()
				|| left.walkingDistanceMeters() != right.walkingDistanceMeters()
				|| left.accessibilityBurden() != right.accessibilityBurden()
				|| ConnectionSlack.compare(left.minimumConnectionSlack(), right.minimumConnectionSlack()) != 0);
	}

	/** {@code transfers.get(i)}는 {@code rides.get(i)}와 {@code rides.get(i + 1)} 사이의 환승이다. */
	private static Candidate candidate(
		List<Ride> rides, List<Access> transfers, Instant readyAt, Instant arrivalAt, int boardingSlackSeconds
	) {
		long walkingSeconds = 0;
		long walkingDistance = 0;
		long burden = 0;
		ConnectionSlack slack = new ConnectionSlack.NoTransfer();
		for (int index = 0; index < transfers.size(); index += 1) {
			Access access = transfers.get(index);
			walkingSeconds += access.durationSeconds();
			walkingDistance += access.walkingDistanceMeters();
			burden += access.accessibilityBurden();
			Ride previous = rides.get(index);
			Ride next = rides.get(index + 1);
			long seconds = next.departureAt().getEpochSecond() - previous.arrivalAt().getEpochSecond()
				- access.durationSeconds() - boardingSlackSeconds;
			ConnectionSlack current = new ConnectionSlack.MinimumTransferSeconds(seconds);
			slack = slack instanceof ConnectionSlack.NoTransfer ? current
				: new ConnectionSlack.MinimumTransferSeconds(Math.min(
					((ConnectionSlack.MinimumTransferSeconds) slack).seconds(), seconds));
		}
		// 같은 열차를 타더라도 다른 환승 동선은 별도 후보다. 길이 접두사로 ID 구분자 충돌을 막는다.
		StringBuilder identity = new StringBuilder();
		for (int index = 0; index < rides.size(); index++) {
			if (index > 0) identity.append(encode("transfer", transfers.get(index - 1).id()));
			identity.append(encode("ride", rides.get(index).identity()));
		}
		String pathIdentity = identity.toString();
		return new Candidate(readyAt, arrivalAt, rides.size() - 1, walkingSeconds, walkingDistance, burden,
			slack, pathIdentity, rides, transfers);
	}

	private static Instant lastDeparture(Ride first, int boardingSlackSeconds) {
		return first.departureAt().minusSeconds(boardingSlackSeconds);
	}

	private enum ReadyAt { PROFILE, FIXED }

	private enum Window { NONE, EARLIEST_ARRIVAL, LATEST_READY }

	private static boolean matchesTransfer(Ride previous, Ride next, Access access) {
		return access.kind() == AccessKind.TRANSFER && access.usable() && previous.dropOffAllowed()
			&& access.fromStationId().equals(previous.toStationId()) && access.toStationId().equals(next.fromStationId())
			&& access.fromLineId().equals(previous.toLineId()) && access.toLineId().equals(next.fromLineId());
	}

	record Query(
		String originStationId, String destinationStationId, Instant earliestReadyAt, Instant arrivalDeadline,
		int maxTransfers, int boardingSlackSeconds, long maxWork, BooleanSupplier cancelled
	) {
		Query {
			originStationId = text(originStationId, "originStationId");
			destinationStationId = text(destinationStationId, "destinationStationId");
			earliestReadyAt = Objects.requireNonNull(earliestReadyAt, "earliestReadyAt");
			arrivalDeadline = Objects.requireNonNull(arrivalDeadline, "arrivalDeadline");
			if (!arrivalDeadline.isAfter(earliestReadyAt) || maxTransfers < 0 || boardingSlackSeconds < 0 || maxWork <= 0) {
				throw new IllegalArgumentException("query bounds and budgets must be explicit and valid");
			}
			cancelled = Objects.requireNonNull(cancelled, "cancelled");
		}
	}

	record Ride(
		String tripId, LocalDate serviceDate, int scheduledTripIndex,
		String fromStationId, String fromLineId, String toStationId, String toLineId,
		Instant departureAt, Instant arrivalAt, int boardingPosition, int alightingPosition, boolean pickupAllowed, boolean dropOffAllowed
	) {
		Ride {
			tripId = text(tripId, "tripId");
			serviceDate = Objects.requireNonNull(serviceDate, "serviceDate");
			fromStationId = text(fromStationId, "fromStationId"); fromLineId = text(fromLineId, "fromLineId");
			toStationId = text(toStationId, "toStationId"); toLineId = text(toLineId, "toLineId");
			departureAt = Objects.requireNonNull(departureAt, "departureAt"); arrivalAt = Objects.requireNonNull(arrivalAt, "arrivalAt");
			if (arrivalAt.isBefore(departureAt) || scheduledTripIndex < 0 || boardingPosition < 0 || alightingPosition <= boardingPosition) {
				throw new IllegalArgumentException("ride timing and stop positions must be valid");
			}
		}

		String identity() {
			return encode(serviceDate.toString(), tripId, Integer.toString(scheduledTripIndex),
				Integer.toString(boardingPosition), Integer.toString(alightingPosition));
		}
	}

	/** #454: 승강장 기준 여정의 이동은 승차 사이 환승뿐이다. */
	enum AccessKind { TRANSFER }

	record Access(
		String id, AccessKind kind, String fromStationId, String fromLineId, String toStationId, String toLineId,
		int durationSeconds, int walkingDistanceMeters, int accessibilityBurden, boolean verified, boolean allowed
	) {
		Access {
			id = text(id, "access id");
			kind = Objects.requireNonNull(kind, "kind");
			fromStationId = text(fromStationId, "fromStationId");
			toStationId = text(toStationId, "toStationId");
			fromLineId = text(fromLineId, "fromLineId");
			toLineId = text(toLineId, "toLineId");
			if (durationSeconds < 0 || walkingDistanceMeters < 0 || accessibilityBurden < 0) {
				throw new IllegalArgumentException("access facts must not be negative");
			}
		}

		boolean usable() { return verified && allowed; }
	}

	record Candidate(
		Instant readyAt, Instant arrivalAtDestination, int transfersUsed, long walkingSeconds, long walkingDistanceMeters,
		long accessibilityBurden, ConnectionSlack minimumConnectionSlack, String pathIdentity,
		List<Ride> rides, List<Access> accesses
	) {
		Candidate {
			readyAt = Objects.requireNonNull(readyAt, "readyAt"); arrivalAtDestination = Objects.requireNonNull(arrivalAtDestination, "arrivalAtDestination");
			minimumConnectionSlack = Objects.requireNonNull(minimumConnectionSlack, "minimumConnectionSlack");
			pathIdentity = text(pathIdentity, "pathIdentity");
			// 집계 값이 같아도 다른 승차·환승 동선일 수 있으므로 원본 trace를 보존한다.
			rides = List.copyOf(rides);
			accesses = List.copyOf(accesses);
		}
	}

	sealed interface ConnectionSlack permits ConnectionSlack.NoTransfer, ConnectionSlack.MinimumTransferSeconds {
		record NoTransfer() implements ConnectionSlack { }
		record MinimumTransferSeconds(long seconds) implements ConnectionSlack { }

		static int compare(ConnectionSlack left, ConnectionSlack right) {
			if (left instanceof NoTransfer) return right instanceof NoTransfer ? 0 : 1;
			if (right instanceof NoTransfer) return -1;
			return Long.compare(((MinimumTransferSeconds) left).seconds(), ((MinimumTransferSeconds) right).seconds());
		}
	}

	static final class WorkLimitExceeded extends RuntimeException {
		private final long observed;
		private final long max;
		WorkLimitExceeded(long observed, long max) { super("MAX_WORK"); this.observed = observed; this.max = max; }
		long observed() { return observed; }
		long max() { return max; }
	}

	static final class Cancelled extends RuntimeException {
		Cancelled() { super("CANCELLED"); }
	}

	private static final class Work {
		private final Query query;
		private long consumed;
		private Work(Query query) { this.query = query; }
		private void consume() {
			if (query.cancelled().getAsBoolean()) throw new Cancelled();
			consumed = Math.addExact(consumed, 1L);
			if (consumed > query.maxWork()) throw new WorkLimitExceeded(consumed, query.maxWork());
		}
	}

	private static String text(String value, String name) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must be nonblank");
		return value;
	}

	private static String encode(String... parts) {
		StringBuilder encoded = new StringBuilder();
		for (String part : parts) encoded.append(part.length()).append(':').append(part);
		return encoded.toString();
	}
}
