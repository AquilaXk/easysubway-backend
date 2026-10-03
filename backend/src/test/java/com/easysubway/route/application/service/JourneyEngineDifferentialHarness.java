package com.easysubway.route.application.service;

import com.easysubway.journey.application.JourneyProfileRaptorPort;
import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.ServiceDayResolver;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * #460: 한 질의를 엔진(RAPTOR 계열)과 독립 기준 해({@link JourneyProfileExactOracle})로 풀어 정확히 비교한다.
 *
 * <h2>같음의 정의</h2>
 * <p>여정 하나를 라벨 벡터 {@code (readyAt, arrivalAtDestination, transfers, walkingSeconds,
 * walkingMeters, stairBurden, minimumConnectionSlack)}와 승차·환승 trace로 본다.</p>
 * <ul>
 *   <li>프로필 질의(ArriveBy, LastConnection, DepartBetween의 각 시점): 엔진이 돌려준 라벨 벡터 집합이 기준 해 파레토
 *   집합의 라벨 벡터 집합과 같아야 한다. 엔진의 각 여정 trace는 같은 벡터를 가진 기준 해 후보 trace 중 하나와 같아야
 *   하고(벡터가 같은 여러 경로 중 하나만 남기는 canonical trace 규칙을 허용), 엔진 결과 안에 같은 trace가 두 번
 *   나오면 안 된다.</li>
 *   <li>출발 시각 고정(DepartAt): point 탐색은 계약상 (도착 시각, 환승 수) 파레토만 보존하고, 무단차 선호 프로필에서는
 *   계단 경고 유무를 차원으로 더한다. 그래서 기준 해 파레토 집합을 그 키로 사영한 파레토 집합과 엔진 결과 키 집합이
 *   같아야 한다. 결과 상한({@code max(alternativeCount, maxTransfers + 1)})을 넘으면 문서화된 절단 순서
 *   ({@link #truncate})로 고른 키 집합과 정확히 같아야 한다. 엔진의 각 여정은 원시 승차·환승 사실로 실행 가능해야
 *   하고 지표가 다시 계산한 값과 같아야 한다.</li>
 * </ul>
 *
 * <h2>기준 해 입력은 엔진을 거치지 않는다</h2>
 * <p>승차 사실은 원시 시간표에서({@link JourneyProfileScheduledOracleInputs}), 환승 사실은 원시 동선 근거에서
 * ({@link JourneyProfileOracleAccessInputs}) 만든다. 엔진과 공유하는 것은 아래 제품 계약뿐이며 각각 이 클래스에
 * 명시적으로 적는다. 계약을 바꾸면 이 표도 바꿔야 차분 검증이 통과한다.</p>
 * <ul>
 *   <li>보행 프로필별 승차 여유: 표준·계단 회피 60초, 느린 걸음 90초, 무단차 180초</li>
 *   <li>환승 동선 선택: (역, 출발 노선, 도착 노선)마다 이용 가능한 동선 하나를 쓴다. 무단차 선호는 계단 없는 동선을
 *   먼저, 그다음 짧은 거리를 고르고, 거리가 같으면 실측 시간이 짧은 동선, 그다음 계단 없는 동선이다.
 *   point 탐색의 무단차 선호만 경고가 다른 대안 동선을 함께 본다.</li>
 *   <li>대안 창({@link JourneyProfileExactOracle#ALTERNATIVE_WINDOW_SECONDS}, 30분, #461): 출발 시간대의 각 시점은
 *   가장 이른 도착 + 창 안, 도착 희망·막차는 가장 늦은 준비 시각 - 창 안의 여정만 파레토 집합에 넣는다.
 *   DepartAt은 창이 없다.</li>
 *   <li>탐색 대상 서비스일: point는 03:00 경계로 정한 하루, 도착 희망은 준비 시각 30시간 전부터 마감일까지,
 *   출발 시간대는 시작 30시간 이후부터 끝 날짜까지, 막차는 그 서비스일 하루</li>
 * </ul>
 */
final class JourneyEngineDifferentialHarness {

	static final JourneyProfileResourcePolicy.ProfilePlanningLimits LIMITS =
		new JourneyProfileResourcePolicy.ProfilePlanningLimits(50_000_000L, 10_000, 10_000, 10_000);
	private static final long ORACLE_MAX_WORK = 2_000_000_000L;
	private static final int ORACLE_MAX_RIDES = 2_000_000;
	private static final int ORACLE_MAX_ACCESSES = 100_000;
	private static final long SERVICE_DAY_LIMIT = LoadRouteTimetablePort.SERVICE_DAY_SECONDS_LIMIT_EXCLUSIVE;

	private JourneyEngineDifferentialHarness() {
	}

	enum Mode { DEPART_AT, ARRIVE_BY, DEPART_BETWEEN, LAST_CONNECTION }

	/** 요청 가능한 보행 프로필·제약 조합. NO_STAIRS는 계약상 REQUIRE_STEP_FREE와만 쓴다. */
	record Mobility(JourneyRequest.MobilityProfile profile, JourneyRequest.ConstraintMode constraint) {
		static final List<Mobility> ALL = List.of(
			new Mobility(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE),
			new Mobility(JourneyRequest.MobilityProfile.SLOW, JourneyRequest.ConstraintMode.NONE),
			new Mobility(JourneyRequest.MobilityProfile.STEP_FREE, JourneyRequest.ConstraintMode.NONE),
			new Mobility(JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE),
			new Mobility(JourneyRequest.MobilityProfile.SLOW, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE),
			new Mobility(JourneyRequest.MobilityProfile.NO_STAIRS, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE),
			new Mobility(JourneyRequest.MobilityProfile.STEP_FREE, JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE));

		static Mobility of(JourneyRaptorQuery query) {
			return new Mobility(query.mobilityProfile(), query.constraintMode());
		}

		boolean prefersStepFree() {
			return profile == JourneyRequest.MobilityProfile.STEP_FREE && constraint == JourneyRequest.ConstraintMode.NONE;
		}

		int boardingSlackSeconds() {
			return switch (profile) {
				case STANDARD, NO_STAIRS -> 60;
				case SLOW -> 90;
				case STEP_FREE -> 180;
			};
		}
	}

	record Case(String fixture, long seed, int index, Mode mode, JourneyRaptorQuery query) {
		String describe() {
			String temporal = switch (query.temporalQuery()) {
				case JourneyRaptorQuery.DepartAt value -> "DepartAt(" + local(value.readyAt()) + ")";
				case JourneyRaptorQuery.ArriveBy value -> "ArriveBy(" + local(value.earliestReadyAt()) + " .. "
					+ local(value.arrivalDeadline()) + ")";
				case JourneyRaptorQuery.DepartBetween value -> "DepartBetween(" + local(value.earliestReadyAt()) + " .. "
					+ local(value.latestReadyAt()) + ")";
				case JourneyRaptorQuery.LastConnection value -> "LastConnection(" + value.serviceDate() + ")";
			};
			return fixture + " seed=" + seed + " case=" + index + " " + query.originStationId() + "->"
				+ query.destinationStationId() + " " + temporal + " profile=" + query.mobilityProfile()
				+ " constraint=" + query.constraintMode() + " pace=" + query.walkingPace()
				+ " maxTransfers=" + query.maxTransfers() + " alternatives=" + query.alternativeCount();
		}
	}

	/**
	 * 한 질의의 판정. {@code expectedLabels}는 기준 해가 요구한 비교 단위 수(DepartBetween은 모든 시점의 합),
	 * {@code maxTransfersUsed}는 기준 해 결과의 최대 환승 수, {@code serviceDayCrossing}은 기준 해 결과에
	 * 서비스일 날짜와 실제 달력 날짜가 다른(자정 이후) 승차가 있는지, {@code zeroSlack}은 기준 해 결과에 환승 여유가
	 * 정확히 0초인 경계 연결이 있는지, {@code truncatedPointFront}는 point 파레토 집합이 결과 상한을 넘어 절단 순서를
	 * 비교했는지다.
	 */
	record Verdict(
		Case testCase, String mismatch, int expectedLabels, int actualLabels, int maxTransfersUsed,
		boolean serviceDayCrossing, int extraEnginePoints, boolean zeroSlack, boolean truncatedPointFront
	) {
		boolean matched() { return mismatch == null; }

		Verdict withTruncatedPointFront() {
			return new Verdict(testCase, mismatch, expectedLabels, actualLabels, maxTransfersUsed, serviceDayCrossing,
				extraEnginePoints, zeroSlack, true);
		}
	}

	/**
	 * 엔진이 던진 예외도 불일치다. 예외로 끝나면 결과가 없으므로 다른 불일치와 똑같이 시드·질의를 출력하고 최소 반례
	 * 축소를 돌린다(예: 환승 여유가 음수인 경로를 만들어 지표 계산이 거절하는 결함). 기준 해 쪽 예외는 하네스나 입력의
	 * 결함이므로 그대로 던진다.
	 */
	static Verdict check(Case testCase, RouteTimetable source, RaptorRouteBundleRuntimeView runtime) {
		return check(testCase, source, runtime, Set.of());
	}

	/**
	 * #461 리뷰 F1: 요청 시점 시설 차단을 넣은 비교. 엔진은 차단 edge id를 {@code FacilityAvailabilityPort}로 받아 요청마다
	 * 한 번 차단 overlay를 만들고(운영 경로와 같음), 기준 해는 그 edge를 쓰는 환승을 이용 불가로 둔다. 프로필 세 모드만
	 * 받는다(출발 시각 고정은 이 하네스에서 시설 차단 경로를 거치지 않는다).
	 */
	static Verdict check(
		Case testCase, RouteTimetable source, RaptorRouteBundleRuntimeView runtime, Set<String> blockedEdgeIds
	) {
		Objects.requireNonNull(blockedEdgeIds, "blockedEdgeIds");
		if (testCase.mode() == Mode.DEPART_AT && !blockedEdgeIds.isEmpty()) {
			throw new IllegalArgumentException("facility blocks are compared only for profile modes");
		}
		try {
			return switch (testCase.mode()) {
				case DEPART_AT -> departAt(testCase, source, runtime);
				case ARRIVE_BY -> arriveBy(testCase, source, runtime, blockedEdgeIds);
				case DEPART_BETWEEN -> departBetween(testCase, source, runtime, blockedEdgeIds);
				case LAST_CONNECTION -> lastConnection(testCase, source, runtime, blockedEdgeIds);
			};
		} catch (EngineFailure failure) {
			Throwable cause = failure.getCause();
			return new Verdict(testCase, "engine threw " + cause.getClass().getSimpleName() + ": " + cause.getMessage(),
				0, 0, -1, false, 0, false, false);
		}
	}

	/** 엔진 호출 안에서 난 예외만 표시해 기준 해 예외와 구분한다. */
	private static <T> T engine(java.util.function.Supplier<T> call) {
		try {
			return call.get();
		} catch (RuntimeException exception) {
			throw new EngineFailure(exception);
		}
	}

	private static final class EngineFailure extends RuntimeException {
		private EngineFailure(RuntimeException cause) {
			super(cause);
		}
	}

	// ---------------------------------------------------------------- DepartAt (point RAPTOR)

	private static Verdict departAt(Case testCase, RouteTimetable source, RaptorRouteBundleRuntimeView runtime) {
		JourneyRaptorQuery query = testCase.query();
		Mobility mobility = Mobility.of(query);
		Instant readyAt = ((JourneyRaptorQuery.DepartAt) query.temporalQuery()).readyAt();
		LocalDate serviceDate = ServiceDayResolver.resolve(readyAt).serviceDate();
		List<JourneyProfileExactOracle.Ride> rides = rides(source, List.of(serviceDate));
		List<JourneyProfileExactOracle.Access> usable = usable(accesses(source, query));
		// point 탐색은 무단차 선호일 때만 경고가 다른 대안 동선을 함께 본다. 그 밖에는 선택된 동선 하나다.
		List<JourneyProfileExactOracle.Access> accesses = mobility.prefersStepFree() ? usable : canonical(usable, false);
		Instant deadline = terminal(query.destinationStationId(), rides);
		List<JourneyProfileExactOracle.Candidate> expected = deadline == null || !deadline.isAfter(readyAt) ? List.of()
			: new JourneyProfileExactOracle().solvePoint(oracleQuery(query, readyAt, deadline),
				window(rides, readyAt, deadline), accesses);

		List<JourneyProfileRaptorPort.Itinerary> actual = engine(() -> new RouteTimetableRaptorPlanner()
			.journeyItineraries(query, runtime.compiledTimetable()).itineraries().stream()
			.map(itinerary -> JourneyProfileRaptorAdapter.itinerary(itinerary, Map.of())).toList());

		List<String> problems = new ArrayList<>();
		for (JourneyProfileRaptorPort.Itinerary itinerary : actual) {
			String unsound = unsound(itinerary, rides, accesses, readyAt, deadline, mobility.boardingSlackSeconds());
			if (unsound != null) problems.add("infeasible engine itinerary: " + unsound + " " + vector(itinerary));
		}
		boolean warningDimension = mobility.prefersStepFree();
		List<long[]> front = pointFront(expected, warningDimension);
		List<String> actualKeys = actual.stream().map(itinerary -> pointKey(itinerary, warningDimension)).toList();
		int limit = Math.max(query.alternativeCount(), query.maxTransfers() + 1);
		if (new TreeSet<>(actualKeys).size() != actualKeys.size()) problems.add("duplicate engine key " + actualKeys);
		TreeSet<String> selected = keys(truncate(front, limit, warningDimension));
		if (!selected.equals(new TreeSet<>(actualKeys))) {
			problems.add((front.size() > limit ? "truncated point front differs: full front " + keys(front) + ", " : "point front differs: ")
				+ "expected " + selected + " actual " + new TreeSet<>(actualKeys));
		}
		Verdict verdict = verdict(testCase, problems, front.size(), actual.size(), expected);
		return front.size() > limit ? verdict.withTruncatedPointFront() : verdict;
	}

	/** point 결과의 (도착, 승차 수[, 계단 경고]) 파레토 키. 같은 키는 하나만 남긴다. */
	private static List<long[]> pointFront(List<JourneyProfileExactOracle.Candidate> expected, boolean warnings) {
		List<long[]> keys = new ArrayList<>();
		for (JourneyProfileExactOracle.Candidate candidate : expected) {
			keys.add(new long[] {candidate.arrivalAtDestination().getEpochSecond(), candidate.transfersUsed(),
				warnings && candidate.accessibilityBurden() > 0 ? 1 : 0});
		}
		List<long[]> front = new ArrayList<>();
		for (long[] key : keys) {
			boolean dominated = false;
			for (long[] other : keys) {
				if (other[0] <= key[0] && other[1] <= key[1] && other[2] <= key[2]
					&& (other[0] < key[0] || other[1] < key[1] || other[2] < key[2])) {
					dominated = true;
					break;
				}
			}
			if (!dominated && front.stream().noneMatch(existing -> Arrays.equals(existing, key))) front.add(key);
		}
		return front;
	}

	/**
	 * 제품 계약: point 결과 상한({@code max(alternativeCount, maxTransfers + 1)}) 절단 순서.
	 * (1) 도착 시각, 다음 승차 수 순으로 앞에서 상한만큼 남긴다. (2) 무단차 선호면 (계단 경고 없음, 도착, 승차 수)
	 * 순으로 가장 앞선 라벨이 남지 않았을 때, 맨 앞(가장 이른 도착)을 뺀 자리 중 승차 수가 겹치는 가장 뒤 자리를
	 * 그 라벨로 바꾼다. 바꿀 자리가 없으면 그대로 둔다. 파레토 집합 안에서는 (도착, 승차 수)가 같은 두 키가
	 * 없으므로(계단 경고만 다르면 경고 없는 쪽이 지배) 이 순서는 결정적이다.
	 */
	static List<long[]> truncate(List<long[]> front, int limit, boolean prefersStepFree) {
		List<long[]> ordered = new ArrayList<>(front);
		ordered.sort(Comparator.<long[]>comparingLong(key -> key[0]).thenComparingLong(key -> key[1]));
		if (ordered.size() <= limit) return ordered;
		List<long[]> kept = new ArrayList<>(ordered.subList(0, limit));
		if (!prefersStepFree) return kept;
		long[] preferred = ordered.stream().min(Comparator.<long[]>comparingLong(key -> key[2])
			.thenComparingLong(key -> key[0]).thenComparingLong(key -> key[1])).orElseThrow();
		if (kept.contains(preferred)) return kept;
		for (int index = kept.size() - 1; index >= 1; index -= 1) {
			long boardings = kept.get(index)[1];
			if (kept.stream().filter(key -> key[1] == boardings).count() > 1) {
				kept.set(index, preferred);
				break;
			}
		}
		return kept;
	}

	private static TreeSet<String> keys(List<long[]> keys) {
		TreeSet<String> result = new TreeSet<>();
		for (long[] key : keys) result.add(pointKey(key[0], (int) key[1], key[2] == 1));
		return result;
	}

	private static String pointKey(JourneyProfileRaptorPort.Itinerary itinerary, boolean warnings) {
		return pointKey(itinerary.plannedArrivalAtDestination().getEpochSecond(), itinerary.metrics().transfersUsed(),
			warnings && itinerary.metrics().accessibilityBurden() > 0);
	}

	private static String pointKey(long arrivalEpochSecond, int transfers, boolean stairs) {
		return "arr=" + local(Instant.ofEpochSecond(arrivalEpochSecond)) + ",k=" + transfers + (stairs ? ",stairs" : "");
	}

	// ---------------------------------------------------------------- ArriveBy (reverse range RAPTOR)

	private static Verdict arriveBy(
		Case testCase, RouteTimetable source, RaptorRouteBundleRuntimeView runtime, Set<String> blocked
	) {
		JourneyRaptorQuery query = testCase.query();
		var arriveBy = (JourneyRaptorQuery.ArriveBy) query.temporalQuery();
		LocalDate first = arriveBy.earliestReadyAt().minusSeconds(SERVICE_DAY_LIMIT - 1)
			.atZone(ServiceDayResolver.ZONE).toLocalDate();
		LocalDate last = arriveBy.arrivalDeadline().atZone(ServiceDayResolver.ZONE).toLocalDate();
		List<JourneyProfileExactOracle.Ride> rides = rides(source, first.datesUntil(last.plusDays(1)).toList());
		List<JourneyProfileExactOracle.Access> accesses = canonical(usable(accesses(source, query, blocked)),
			Mobility.of(query).prefersStepFree());
		List<JourneyProfileExactOracle.Candidate> expected = new JourneyProfileExactOracle().solveLatestReadyWindow(
			oracleQuery(query, arriveBy.earliestReadyAt(), arriveBy.arrivalDeadline()),
			window(rides, arriveBy.earliestReadyAt(), arriveBy.arrivalDeadline()), accesses);
		var planned = plan(query, runtime, blocked);
		if (planned == null) return failClosed(testCase, expected);
		var reverse = ((JourneyProfileRaptorPort.ArriveByPlan) planned.temporalPlan()).result();
		List<JourneyProfileRaptorPort.Itinerary> actual = reverse instanceof JourneyProfileRaptorPort.ReversePlan.Found found
			? found.itineraries() : List.of();
		List<String> problems = new ArrayList<>();
		compareFrontier("", expected, actual, problems);
		return verdict(testCase, problems, expected.size(), actual.size(), expected);
	}

	// ---------------------------------------------------------------- LastConnection (reverse, one service day)

	private static Verdict lastConnection(
		Case testCase, RouteTimetable source, RaptorRouteBundleRuntimeView runtime, Set<String> blocked
	) {
		JourneyRaptorQuery query = testCase.query();
		LocalDate serviceDate = ((JourneyRaptorQuery.LastConnection) query.temporalQuery()).serviceDate();
		List<JourneyProfileExactOracle.Ride> rides = rides(source, List.of(serviceDate));
		List<JourneyProfileExactOracle.Access> accesses = canonical(usable(accesses(source, query, blocked)),
			Mobility.of(query).prefersStepFree());
		Instant earliest = serviceDate.atStartOfDay(ServiceDayResolver.ZONE).toInstant();
		Instant terminal = terminal(query.destinationStationId(), rides);
		List<JourneyProfileExactOracle.Candidate> expected = terminal == null || !terminal.isAfter(earliest) ? List.of()
			: new JourneyProfileExactOracle().solveLatestReadyWindow(oracleQuery(query, earliest, terminal),
				window(rides, earliest, terminal), accesses);
		var planned = plan(query, runtime, blocked);
		if (planned == null) return failClosed(testCase, expected);
		var plan = (JourneyProfileRaptorPort.LastConnectionPlan) planned.temporalPlan();
		List<JourneyProfileRaptorPort.Itinerary> actual = plan.result() instanceof JourneyProfileRaptorPort.ReversePlan.Found found
			? found.itineraries() : List.of();
		List<String> problems = new ArrayList<>();
		if (!expected.isEmpty() && !Objects.equals(terminal, plan.terminalArrivalAtDestination())) {
			problems.add("terminal deadline expected " + local(terminal) + " actual "
				+ (plan.terminalArrivalAtDestination() == null ? "-" : local(plan.terminalArrivalAtDestination())));
		}
		compareFrontier("", expected, actual, problems);
		return verdict(testCase, problems, expected.size(), actual.size(), expected);
	}

	// ---------------------------------------------------------------- DepartBetween (forward range McRAPTOR)

	private static Verdict departBetween(
		Case testCase, RouteTimetable source, RaptorRouteBundleRuntimeView runtime, Set<String> blocked
	) {
		JourneyRaptorQuery query = testCase.query();
		Mobility mobility = Mobility.of(query);
		var range = (JourneyRaptorQuery.DepartBetween) query.temporalQuery();
		LocalDate firstNative = range.earliestReadyAt().minusSeconds(SERVICE_DAY_LIMIT)
			.atZone(ServiceDayResolver.ZONE).toLocalDate().plusDays(1);
		LocalDate lastNative = range.latestReadyAt().atZone(ServiceDayResolver.ZONE).toLocalDate();
		List<JourneyProfileExactOracle.Ride> rides = rides(source, firstNative.datesUntil(lastNative.plusDays(1)).toList());
		List<JourneyProfileExactOracle.Access> accesses = canonical(usable(accesses(source, query, blocked)),
			mobility.prefersStepFree());
		Instant deadline = terminal(query.destinationStationId(), rides);

		// 시간대 안의 출발 사건(출발역 승차 - 승차 여유)과 서비스일 조각마다의 마지막 준비 시각이 필수 시점이다.
		TreeSet<Instant> required = new TreeSet<>();
		LocalDate firstSlice = ServiceDayResolver.resolve(range.earliestReadyAt()).serviceDate();
		LocalDate lastSlice = ServiceDayResolver.resolve(range.latestReadyAt()).serviceDate();
		for (LocalDate slice = firstSlice; !slice.isAfter(lastSlice); slice = slice.plusDays(1)) {
			Instant sliceEarliest = slice.equals(firstSlice) ? range.earliestReadyAt() : cutoff(slice);
			Instant sliceLatest = slice.equals(lastSlice) ? range.latestReadyAt() : cutoff(slice.plusDays(1)).minusSeconds(1);
			if (sliceEarliest.isAfter(sliceLatest)) continue;
			required.add(sliceLatest);
			for (JourneyProfileExactOracle.Ride ride : rides) {
				if (!ride.pickupAllowed() || !ride.fromStationId().equals(query.originStationId())) continue;
				Instant breakpoint = ride.departureAt().minusSeconds(mobility.boardingSlackSeconds());
				if (!breakpoint.isBefore(sliceEarliest) && !breakpoint.isAfter(sliceLatest)) required.add(breakpoint);
			}
		}
		var oracle = new JourneyProfileExactOracle();
		Map<Instant, List<JourneyProfileExactOracle.Candidate>> expectedByPoint = new TreeMap<>();
		for (Instant breakpoint : required) {
			List<JourneyProfileExactOracle.Candidate> expected = solvePointOrEmpty(oracle, query, breakpoint, deadline,
				rides, accesses);
			if (!expected.isEmpty()) expectedByPoint.put(breakpoint, expected);
		}

		var planned = plan(query, runtime, blocked);
		if (planned == null) {
			return failClosed(testCase, expectedByPoint.values().stream().flatMap(List::stream).toList());
		}
		var points = ((JourneyProfileRaptorPort.DepartureWindowPlan) planned.temporalPlan()).points();
		List<String> problems = new ArrayList<>();
		Map<Instant, JourneyProfileRaptorPort.DeparturePoint> actualByPoint = new LinkedHashMap<>();
		for (JourneyProfileRaptorPort.DeparturePoint point : points) {
			if (actualByPoint.put(point.readyAt(), point) != null) problems.add("duplicate engine point " + local(point.readyAt()));
		}
		int extra = 0;
		int expectedLabels = 0;
		int actualLabels = 0;
		for (var entry : expectedByPoint.entrySet()) {
			expectedLabels += entry.getValue().size();
			var point = actualByPoint.get(entry.getKey());
			if (point == null) {
				problems.add("missing engine point " + local(entry.getKey()) + " expected " + vectors(entry.getValue()));
				continue;
			}
			actualLabels += point.itineraries().size();
			compareFrontier("@" + local(entry.getKey()) + " ", entry.getValue(), point.itineraries(), problems);
		}
		for (var entry : actualByPoint.entrySet()) {
			if (expectedByPoint.containsKey(entry.getKey())) continue;
			// 필수 시점 밖의 시점도 그 준비 시각의 정확한 파레토 집합이어야 한다. 시점 자체는 중복 표시일 뿐이다.
			extra += 1;
			actualLabels += entry.getValue().itineraries().size();
			List<JourneyProfileExactOracle.Candidate> expected = solvePointOrEmpty(oracle, query, entry.getKey(), deadline,
				rides, accesses);
			if (expected.isEmpty()) {
				problems.add("engine point without any feasible journey " + local(entry.getKey()));
			} else {
				compareFrontier("@" + local(entry.getKey()) + "(extra) ", expected, entry.getValue().itineraries(), problems);
			}
		}
		List<JourneyProfileExactOracle.Candidate> all = expectedByPoint.values().stream().flatMap(List::stream).toList();
		Verdict verdict = verdict(testCase, problems, expectedLabels, actualLabels, all);
		return new Verdict(testCase, verdict.mismatch(), verdict.expectedLabels(), verdict.actualLabels(),
			verdict.maxTransfersUsed(), verdict.serviceDayCrossing(), extra, verdict.zeroSlack(), false);
	}

	private static List<JourneyProfileExactOracle.Candidate> solvePointOrEmpty(
		JourneyProfileExactOracle oracle, JourneyRaptorQuery query, Instant readyAt, Instant deadline,
		List<JourneyProfileExactOracle.Ride> rides, List<JourneyProfileExactOracle.Access> accesses
	) {
		if (deadline == null || !deadline.isAfter(readyAt)) return List.of();
		return oracle.solvePointArrivalWindow(oracleQuery(query, readyAt, deadline), window(rides, readyAt, deadline), accesses);
	}

	// ---------------------------------------------------------------- 비교

	private static void compareFrontier(
		String label, List<JourneyProfileExactOracle.Candidate> expected,
		List<JourneyProfileRaptorPort.Itinerary> actual, List<String> problems
	) {
		Map<String, List<JourneyProfileExactOracle.Candidate>> expectedByVector = new TreeMap<>();
		for (JourneyProfileExactOracle.Candidate candidate : expected) {
			expectedByVector.computeIfAbsent(vector(candidate), ignored -> new ArrayList<>()).add(candidate);
		}
		TreeSet<String> actualVectors = new TreeSet<>();
		List<String> traces = new ArrayList<>();
		for (JourneyProfileRaptorPort.Itinerary itinerary : actual) {
			String vector = vector(itinerary);
			actualVectors.add(vector);
			String trace = trace(itinerary);
			if (traces.contains(trace)) problems.add(label + "duplicate engine trace " + trace);
			traces.add(trace);
			List<JourneyProfileExactOracle.Candidate> sameVector = expectedByVector.getOrDefault(vector, List.of());
			if (!sameVector.isEmpty() && sameVector.stream()
				.noneMatch(candidate -> JourneyProfileOracleComparison.matchesObservableTimetableTrace(candidate, itinerary))) {
				problems.add(label + "engine trace is not an oracle path for " + vector + ": " + trace);
			}
		}
		if (!expectedByVector.keySet().equals(actualVectors)) {
			TreeSet<String> missing = new TreeSet<>(expectedByVector.keySet());
			missing.removeAll(actualVectors);
			TreeSet<String> unexpected = new TreeSet<>(actualVectors);
			unexpected.removeAll(expectedByVector.keySet());
			problems.add(label + "frontier differs: missing " + missing + " unexpected " + unexpected);
		}
	}

	private static Verdict verdict(
		Case testCase, List<String> problems, int expectedLabels, int actualLabels,
		List<JourneyProfileExactOracle.Candidate> expected
	) {
		int maxTransfers = expected.stream().mapToInt(JourneyProfileExactOracle.Candidate::transfersUsed).max().orElse(-1);
		boolean crossing = expected.stream().flatMap(candidate -> candidate.rides().stream())
			.anyMatch(ride -> !ride.departureAt().atZone(ServiceDayResolver.ZONE).toLocalDate().equals(ride.serviceDate()));
		boolean zeroSlack = expected.stream().anyMatch(candidate -> candidate.minimumConnectionSlack()
			instanceof JourneyProfileExactOracle.ConnectionSlack.MinimumTransferSeconds seconds && seconds.seconds() == 0);
		return new Verdict(testCase, problems.isEmpty() ? null : String.join("\n    ", problems),
			expectedLabels, actualLabels, maxTransfers, crossing, 0, zeroSlack, false);
	}

	private static Verdict failClosed(Case testCase, List<JourneyProfileExactOracle.Candidate> expected) {
		return verdict(testCase, List.of("engine failed closed (admission/capacity) under generous test limits"),
			expected.size(), 0, expected);
	}

	private static JourneyProfileRaptorPort.PlanningResult.Planned plan(
		JourneyRaptorQuery query, RaptorRouteBundleRuntimeView runtime, Set<String> blocked
	) {
		// 차단이 있으면 신선한 시설 가동 뷰를 주는 포트로 어댑터를 만든다. 해석되지 않는 edge id가 있으면 운영처럼 뷰 전체가
		// 무시되므로, 호출자는 번들에 있는 edge만 넘긴다.
		JourneyProfileRaptorAdapter adapter = blocked.isEmpty() ? new JourneyProfileRaptorAdapter()
			: new JourneyProfileRaptorAdapter(RouteTimetableRaptorPlanner.ScanWorkspacePool.shared(),
				() -> com.easysubway.journey.application.FacilityAvailabilityView.blocked(Instant.now(), blocked),
				false, java.time.Clock.systemUTC());
		var result = engine(() -> adapter.planRuntime(query, runtime, null, LIMITS));
		return result instanceof JourneyProfileRaptorPort.PlanningResult.Planned planned ? planned : null;
	}

	/** point 결과 하나가 원시 승차·환승 사실로 실행 가능하고 지표가 다시 계산한 값과 같은지. 같으면 null. */
	private static String unsound(
		JourneyProfileRaptorPort.Itinerary itinerary, List<JourneyProfileExactOracle.Ride> rides,
		List<JourneyProfileExactOracle.Access> accesses, Instant readyAt, Instant deadline, int slack
	) {
		List<JourneyProfileRaptorPort.Leg> legs = itinerary.legs();
		if (legs.size() % 2 == 0) return "legs must alternate ride/transfer/ride";
		List<JourneyProfileExactOracle.Ride> matched = new ArrayList<>();
		for (int index = 0; index < legs.size(); index += 2) {
			if (!(legs.get(index) instanceof JourneyProfileRaptorPort.RideLeg ride)) return "leg " + index + " is not a ride";
			var found = rides.stream().filter(candidate -> candidate.tripId().equals(ride.tripId())
				&& candidate.fromStationId().equals(ride.fromStationId()) && candidate.toStationId().equals(ride.toStationId())
				&& candidate.departureAt().equals(ride.plannedDepartureTime())
				&& candidate.arrivalAt().equals(ride.plannedArrivalTime())
				&& candidate.pickupAllowed() && candidate.dropOffAllowed()).findFirst();
			if (found.isEmpty()) return "ride " + ride.tripId() + " " + ride.fromStationId() + "->" + ride.toStationId()
				+ " is not a scheduled pickup/drop-off ride";
			matched.add(found.get());
		}
		if (readyAt != null && !itinerary.plannedReadyAt().equals(readyAt)) return "point readyAt differs";
		if (matched.getFirst().departureAt().minusSeconds(slack).isBefore(itinerary.plannedReadyAt())) {
			return "first boarding violates boarding slack";
		}
		if (!itinerary.plannedArrivalAtDestination().equals(matched.getLast().arrivalAt())
			|| (deadline != null && matched.getLast().arrivalAt().isAfter(deadline))) return "arrival mismatch";
		long walking = 0;
		long meters = 0;
		long burden = 0;
		long minimumSlack = Long.MAX_VALUE;
		for (int index = 1; index < legs.size(); index += 2) {
			if (!(legs.get(index) instanceof JourneyProfileRaptorPort.AccessLeg access)) return "leg " + index + " is not a transfer";
			var previous = matched.get(index / 2);
			var next = matched.get(index / 2 + 1);
			boolean exists = accesses.stream().anyMatch(candidate -> candidate.fromStationId().equals(previous.toStationId())
				&& candidate.toStationId().equals(next.fromStationId()) && candidate.fromLineId().equals(previous.toLineId())
				&& candidate.toLineId().equals(next.fromLineId()) && candidate.durationSeconds() == access.durationSeconds()
				&& candidate.walkingDistanceMeters() == access.distanceMeters()
				&& (candidate.accessibilityBurden() > 0) == access.includesStairs());
			if (!exists) return "transfer at " + previous.toStationId() + " " + previous.toLineId() + "->" + next.fromLineId()
				+ " (" + access.durationSeconds() + "s," + access.distanceMeters() + "m) is not an eligible pathway";
			long transferSlack = next.departureAt().getEpochSecond() - previous.arrivalAt().getEpochSecond()
				- access.durationSeconds() - slack;
			if (transferSlack < 0) return "transfer is infeasible by " + (-transferSlack) + "s";
			minimumSlack = Math.min(minimumSlack, transferSlack);
			walking += access.durationSeconds();
			meters += access.distanceMeters();
			burden += access.includesStairs() ? 1 : 0;
		}
		var metrics = itinerary.metrics();
		JourneyProfileRaptorPort.ConnectionSlack expectedSlack = matched.size() == 1
			? new JourneyProfileRaptorPort.NoTransfer() : new JourneyProfileRaptorPort.MinimumTransferSeconds(minimumSlack);
		if (metrics.transfersUsed() != matched.size() - 1 || metrics.accessMovementSeconds() != walking
			|| metrics.accessDistanceMeters() != meters || metrics.accessibilityBurden() != burden
			|| !metrics.connectionSlack().equals(expectedSlack)) return "metrics differ from recomputed facts";
		return null;
	}

	// ---------------------------------------------------------------- 기준 해 입력

	private static List<JourneyProfileExactOracle.Ride> rides(RouteTimetable source, List<LocalDate> dates) {
		List<JourneyProfileExactOracle.Ride> rides = new ArrayList<>();
		for (LocalDate date : dates) rides.addAll(JourneyProfileScheduledOracleInputs.rides(source, date, ORACLE_MAX_RIDES));
		return List.copyOf(rides);
	}

	/**
	 * 질의 구간 밖 승차를 미리 뺀다. 모든 여정은 준비 시각 이후에 출발하고 마감 전에 도착하는 승차로만
	 * 이뤄지므로(승차는 시간이 줄지 않는다) 기준 해의 답을 바꾸지 않는 정확한 축소다.
	 */
	private static List<JourneyProfileExactOracle.Ride> window(
		List<JourneyProfileExactOracle.Ride> rides, Instant earliest, Instant deadline
	) {
		return rides.stream().filter(ride -> !ride.departureAt().isBefore(earliest) && !ride.arrivalAt().isAfter(deadline))
			.toList();
	}

	private static List<JourneyProfileExactOracle.Access> accesses(RouteTimetable source, JourneyRaptorQuery query) {
		return accesses(source, query, Set.of());
	}

	private static List<JourneyProfileExactOracle.Access> accesses(
		RouteTimetable source, JourneyRaptorQuery query, Set<String> blocked
	) {
		return JourneyProfileOracleAccessInputs.normalize(source.routeAccessData(), query.mobilityProfile(),
			query.constraintMode(), query.walkingPace().speedMetersPerHour(), ORACLE_MAX_ACCESSES, blocked);
	}

	private static List<JourneyProfileExactOracle.Access> usable(List<JourneyProfileExactOracle.Access> accesses) {
		return accesses.stream().filter(JourneyProfileExactOracle.Access::usable).toList();
	}

	/**
	 * 제품 계약: (역, 출발 노선, 도착 노선)마다 이용 가능한 동선 하나. 무단차 선호는 계단 없는 동선 우선, 다음은 짧은 거리,
	 * 거리가 같으면(거리 없이 실측 시간만 있는 동선끼리 등) 실측 시간이 짧은 동선, 그다음 계단 없는 동선이다.
	 * 보행 프로필 환승 시간은 같은 프로필 안에서 실측 시간에 대해 단조이므로 정규화된 시간으로 비교해도 순서가 같다.
	 */
	static List<JourneyProfileExactOracle.Access> canonical(
		List<JourneyProfileExactOracle.Access> usable, boolean prefersStepFree
	) {
		Map<String, JourneyProfileExactOracle.Access> selected = new LinkedHashMap<>();
		Comparator<JourneyProfileExactOracle.Access> order = Comparator.comparingInt(
			(JourneyProfileExactOracle.Access access) -> prefersStepFree ? access.accessibilityBurden() : 0)
			.thenComparingInt(JourneyProfileExactOracle.Access::walkingDistanceMeters)
			.thenComparingInt(JourneyProfileExactOracle.Access::durationSeconds)
			.thenComparingInt(JourneyProfileExactOracle.Access::accessibilityBurden);
		for (JourneyProfileExactOracle.Access access : usable) {
			String key = access.fromStationId() + '\u0000' + access.fromLineId() + '\u0000' + access.toLineId();
			selected.merge(key, access, (left, right) -> order.compare(right, left) < 0 ? right : left);
		}
		return List.copyOf(selected.values());
	}

	private static JourneyProfileExactOracle.Query oracleQuery(JourneyRaptorQuery query, Instant earliest, Instant deadline) {
		return new JourneyProfileExactOracle.Query(query.originStationId(), query.destinationStationId(), earliest, deadline,
			query.maxTransfers(), Mobility.of(query).boardingSlackSeconds(), ORACLE_MAX_WORK, () -> false);
	}

	/** 도착역 승강장에 내리는 가장 늦은 시각. 없으면 null. */
	private static Instant terminal(String destination, List<JourneyProfileExactOracle.Ride> rides) {
		Instant latest = null;
		for (JourneyProfileExactOracle.Ride ride : rides) {
			if (ride.dropOffAllowed() && ride.toStationId().equals(destination)
				&& (latest == null || ride.arrivalAt().isAfter(latest))) latest = ride.arrivalAt();
		}
		return latest;
	}

	private static Instant cutoff(LocalDate serviceDate) {
		return serviceDate.atTime(LocalTime.parse(ServiceDayResolver.CUTOFF_LOCAL_TIME)).atZone(ServiceDayResolver.ZONE)
			.toInstant();
	}

	// ---------------------------------------------------------------- 표기

	static String vector(JourneyProfileExactOracle.Candidate candidate) {
		String slack = candidate.minimumConnectionSlack() instanceof JourneyProfileExactOracle.ConnectionSlack.MinimumTransferSeconds seconds
			? Long.toString(seconds.seconds()) : "none";
		return vector(candidate.readyAt(), candidate.arrivalAtDestination(), candidate.transfersUsed(),
			candidate.walkingSeconds(), candidate.walkingDistanceMeters(), candidate.accessibilityBurden(), slack);
	}

	static String vector(JourneyProfileRaptorPort.Itinerary itinerary) {
		var metrics = itinerary.metrics();
		String slack = metrics.connectionSlack() instanceof JourneyProfileRaptorPort.MinimumTransferSeconds seconds
			? Long.toString(seconds.seconds()) : "none";
		return vector(itinerary.plannedReadyAt(), itinerary.plannedArrivalAtDestination(), metrics.transfersUsed(),
			metrics.accessMovementSeconds(), metrics.accessDistanceMeters(), metrics.accessibilityBurden(), slack);
	}

	private static String vector(Instant readyAt, Instant arrival, int transfers, long walking, long meters, long burden,
		String slack) {
		return "(ready=" + local(readyAt) + ",arr=" + local(arrival) + ",k=" + transfers + ",walk=" + walking + "s/" + meters
			+ "m,stairs=" + burden + ",slack=" + slack + ")";
	}

	private static List<String> vectors(List<JourneyProfileExactOracle.Candidate> candidates) {
		return candidates.stream().map(JourneyEngineDifferentialHarness::vector).sorted().toList();
	}

	private static String trace(JourneyProfileRaptorPort.Itinerary itinerary) {
		StringBuilder text = new StringBuilder();
		for (JourneyProfileRaptorPort.Leg leg : itinerary.legs()) {
			if (leg instanceof JourneyProfileRaptorPort.RideLeg ride) {
				text.append('[').append(ride.tripId()).append(' ').append(ride.fromStationId()).append('>')
					.append(ride.toStationId()).append(' ').append(local(ride.plannedDepartureTime())).append('-')
					.append(local(ride.plannedArrivalTime())).append(']');
			} else if (leg instanceof JourneyProfileRaptorPort.AccessLeg access) {
				text.append("~").append(access.durationSeconds()).append("s/").append(access.distanceMeters()).append('m')
					.append(access.includesStairs() ? "/stairs" : "").append('~');
			}
		}
		return text.toString();
	}

	static String local(Instant instant) {
		var local = instant.atZone(ServiceDayResolver.ZONE);
		return local.toLocalDate().getDayOfMonth() + "d" + local.toLocalTime();
	}
}
