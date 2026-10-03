package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.ServiceDayResolver;
import com.easysubway.route.adapter.out.persistence.JdbcRouteTimetableRepository;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.service.JourneyEngineDifferentialHarness.Case;
import com.easysubway.route.application.service.JourneyEngineDifferentialHarness.Mobility;
import com.easysubway.route.application.service.JourneyEngineDifferentialHarness.Mode;
import com.easysubway.route.application.service.JourneyEngineDifferentialHarness.Verdict;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * #460: 경로 엔진과 독립 기준 해의 시드 고정 무작위 차분 검증.
 *
 * <p>합성 번들 {@value #SYNTHETIC_BUNDLES}개에 질의 {@value #QUERIES_PER_BUNDLE}건씩, 실데이터 4호선 코리도
 * 슬라이스(KRIC 실 시각표)에 {@value #LINE4_QUERIES}건을 돌린다. 비교 규칙은
 * {@link JourneyEngineDifferentialHarness}에 있다. 불일치가 나오면 같은 질의가 계속 틀리는 동안 열차와 환승 규칙을
 * 하나씩 지워 최소 반례를 만들고, 시드·질의·남은 번들을 출력한다.</p>
 *
 * <p>재현: {@code EASYSUBWAY_DIFFERENTIAL_BASE_SEED}(기본 {@value #DEFAULT_BASE_SEED})와
 * {@code EASYSUBWAY_DIFFERENTIAL_BUNDLES}(기본 {@value #SYNTHETIC_BUNDLES})로 범위를 바꾼다. 야간·수동 확장 실행은
 * 번들 수만 늘리면 된다(예: 1000). 출력된 반례의 seed가 곧 번들 시드다.</p>
 */
@DisplayName("#460 경로 엔진 기준 해 무작위 차분 검증")
class JourneyEngineRandomizedDifferentialTest {

	static final long DEFAULT_BASE_SEED = 4_600_000L;
	static final int SYNTHETIC_BUNDLES = 80;
	static final int QUERIES_PER_BUNDLE = 20;
	static final int LINE4_QUERIES = 240;
	private static final int REPORTED_COUNTEREXAMPLES = 3;

	@Test
	@DisplayName("합성 번들 질의 전부에서 엔진 결과가 기준 해 파레토 집합과 정확히 같다")
	void syntheticBundlesMatchTheExactOracle() {
		long baseSeed = environmentLong("EASYSUBWAY_DIFFERENTIAL_BASE_SEED", DEFAULT_BASE_SEED);
		int bundles = (int) environmentLong("EASYSUBWAY_DIFFERENTIAL_BUNDLES", SYNTHETIC_BUNDLES);
		Tally tally = new Tally();
		List<String> counterexamples = new ArrayList<>();
		for (int offset = 0; offset < bundles; offset += 1) {
			long seed = baseSeed + offset;
			var bundle = JourneyEngineSyntheticBundles.generate(seed);
			var runtime = compile(bundle.timetable());
			for (Case testCase : syntheticCases(bundle, QUERIES_PER_BUNDLE)) {
				long started = System.nanoTime();
				Verdict verdict = JourneyEngineDifferentialHarness.check(testCase, bundle.timetable(), runtime);
				tally.time(verdict, System.nanoTime() - started);
				tally.add(verdict);
				if (!verdict.matched() && counterexamples.size() < REPORTED_COUNTEREXAMPLES) {
					counterexamples.add("original mismatch:\n    " + verdict.mismatch() + "\n"
						+ minimalCounterexample(testCase, bundle.timetable()));
				}
			}
		}
		System.out.println("#460 synthetic differential: " + tally.summary());
		assertThat(tally.mismatches).as("엔진-기준 해 불일치(최소 반례):\n%s", String.join("\n", counterexamples)).isZero();
		assertThat(tally.queries).isGreaterThanOrEqualTo(bundles * QUERIES_PER_BUNDLE);
		if (bundles >= SYNTHETIC_BUNDLES) tally.requireCoverage();
	}

	@Test
	@DisplayName("실데이터 4호선 코리도 슬라이스 질의에서 엔진 결과가 기준 해와 정확히 같다")
	void realDerivedLine4SliceMatchesTheExactOracle() {
		RouteTimetable timetable = line4CorridorSlice();
		var runtime = compile(timetable);
		Tally tally = new Tally();
		List<String> counterexamples = new ArrayList<>();
		for (Case testCase : line4Cases(timetable, LINE4_QUERIES)) {
			Verdict verdict = JourneyEngineDifferentialHarness.check(testCase, timetable, runtime);
			tally.add(verdict);
			if (!verdict.matched() && counterexamples.size() < REPORTED_COUNTEREXAMPLES) {
				counterexamples.add(testCase.describe() + "\n    " + verdict.mismatch());
			}
		}
		System.out.println("#460 line4 real-derived differential: " + tally.summary());
		assertThat(tally.mismatches).as("엔진-기준 해 불일치:\n%s", String.join("\n", counterexamples)).isZero();
		assertThat(tally.queries).isEqualTo(LINE4_QUERIES);
		for (Mode mode : Mode.values()) {
			assertThat(tally.nonEmptyByMode.getOrDefault(mode, 0)).as("실데이터 비어 있지 않은 %s 질의", mode)
				.isGreaterThanOrEqualTo(20);
		}
	}

	@Test
	@DisplayName("환승 여유가 정확히 -1·0·+1초인 경계 연결에서 모든 프로필·속도·모드가 기준 해와 같다")
	void boundaryConnectionsMatchTheExactOracle() {
		Tally tally = new Tally();
		List<String> counterexamples = new ArrayList<>();
		int index = 0;
		for (Mobility mobility : Mobility.ALL) {
			for (JourneyRequest.WalkingPace pace : JourneyRequest.WalkingPace.values()) {
				for (int delta = -1; delta <= 1; delta += 1) {
					RouteTimetable timetable = boundaryTimetable(mobility, pace, delta);
					var runtime = compile(timetable);
					for (Mode mode : Mode.values()) {
						Case testCase = boundaryCase(mobility, pace, delta, mode, index++);
						Verdict verdict = JourneyEngineDifferentialHarness.check(testCase, timetable, runtime);
						tally.add(verdict);
						if (!verdict.matched() && counterexamples.size() < REPORTED_COUNTEREXAMPLES) {
							counterexamples.add("delta=" + delta + " original mismatch:\n    " + verdict.mismatch() + "\n"
								+ minimalCounterexample(testCase, timetable));
						}
					}
				}
			}
		}
		System.out.println("#460 boundary differential: " + tally.summary());
		assertThat(tally.mismatches).as("경계 연결 엔진-기준 해 불일치:\n%s", String.join("\n", counterexamples)).isZero();
		assertThat(tally.queries).isEqualTo(Mobility.ALL.size() * 3 * 3 * Mode.values().length);
		assertThat(tally.zeroSlack).as("여유 0초 연결이 기준 해 결과에 실제로 나타난 질의").isGreaterThanOrEqualTo(50);
	}

	@Test
	@DisplayName("무단차 선호 point 결과가 상한을 넘으면 문서화된 절단 순서로 고른 집합과 같다")
	void truncatedPointFrontFollowsTheDocumentedOrder() {
		RouteTimetable timetable = truncationTimetable();
		var runtime = compile(timetable);
		Instant readyAt = BOUNDARY_DATE.atStartOfDay(ServiceDayResolver.ZONE).toInstant().plusSeconds(7 * 3_600 + 50 * 60);
		int truncated = 0;
		for (int alternatives = 1; alternatives <= 3; alternatives += 1) {
			var temporal = new JourneyRaptorQuery.DepartAt(readyAt);
			var query = new JourneyRaptorQuery(
				JourneyProfileFullCorpusRunner.requestId("truncation-" + alternatives, "o", "d", temporal), "o", "d", temporal,
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.FAST,
				JourneyRequest.MobilityProfile.STEP_FREE, JourneyRequest.ConstraintMode.NONE, 1, alternatives, () -> false);
			Case testCase = new Case("truncation", alternatives, alternatives, Mode.DEPART_AT, query);
			Verdict verdict = JourneyEngineDifferentialHarness.check(testCase, timetable, runtime);
			assertThat(verdict.mismatch()).as(testCase.describe()).isNull();
			// 파레토 키는 (08:30 환승1 계단), (08:36 환승1 무단차), (09:00 직행) 3개이고 상한은 max(대안 수, 2)다.
			assertThat(verdict.expectedLabels()).isEqualTo(3);
			assertThat(verdict.truncatedPointFront()).isEqualTo(alternatives < 3);
			if (verdict.truncatedPointFront()) truncated += 1;
		}
		assertThat(truncated).isEqualTo(2);
	}

	/**
	 * o에서 L1로 x에 08:10 도착. x의 L1→L2 환승은 계단 동선(실측 60초)과 무단차 동선(실측 300초)이 함께 있다.
	 * 무단차 프로필·빠른 걸음이면 시설 대기 60초와 승차 여유 180초를 더해 계단은 08:15, 무단차는 08:19부터 탈 수 있다.
	 * L2 08:16 열차는 계단으로만, 08:22 열차는 무단차로도 탄다. L3는 o에서 d로 가는 09:00 도착 직행이다.
	 */
	private static RouteTimetable truncationTimetable() {
		var stairs = new LoadRouteTimetablePort.PathwayEdge("e-x-stairs", "p-x-L1", "p-x-L2", 60, 0, false, true, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED");
		var stepFree = new LoadRouteTimetablePort.PathwayEdge("e-x-step-free", "p-x-L1", "p-x-L2", 300, 0, false, false, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED");
		var access = new LoadRouteTimetablePort.RouteAccessData(
			List.of(new LoadRouteTimetablePort.PathwayNode("p-x-L1", "x", "L1", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("p-x-L2", "x", "L2", "PLATFORM")),
			List.of(stairs, stepFree),
			List.of(new LoadRouteTimetablePort.TransferRule("rule-x", "x", "L1", "x", "L2", "IN_STATION", 60,
				stairs.id(), stepFree.id(), "VERIFIED")),
			List.of(new LoadRouteTimetablePort.RouteEdgeEvidence("ev-stairs", "x", "L2", stairs.id(), "TRANSFER",
					"OFFICIAL_SOURCE", "VERIFIED", true, null),
				new LoadRouteTimetablePort.RouteEdgeEvidence("ev-step-free", "x", "L2", stepFree.id(), "TRANSFER",
					"OFFICIAL_SOURCE", "VERIFIED", true, null)));
		var calendar = new LoadRouteTimetablePort.ServiceCalendar("daily", true, true, true, true, true, true, true,
			BOUNDARY_DATE.minusDays(2), BOUNDARY_DATE.plusDays(2), "Asia/Seoul");
		return new RouteTimetable(List.of(calendar), List.of(),
			List.of(new LoadRouteTimetablePort.TransitRoute("r1", "L1", "1", "1", "up", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute("r2", "L2", "2", "2", "up", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute("r3", "L3", "3", "3", "up", "Asia/Seoul")),
			List.of(new LoadRouteTimetablePort.TransitTrip("feeder", "r1", "daily", "x", "up", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip("fast", "r2", "daily", "d", "up", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip("slow", "r2", "daily", "d", "up", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip("direct", "r3", "daily", "d", "up", "LOCAL", 0)),
			List.of(stopAt("feeder", 1, "o", "L1", 28_800), stopAt("feeder", 2, "x", "L1", 29_400),
				stopAt("fast", 1, "x", "L2", 29_760), stopAt("fast", 2, "d", "L2", 30_600),
				stopAt("slow", 1, "x", "L2", 30_120), stopAt("slow", 2, "d", "L2", 30_960),
				stopAt("direct", 1, "o", "L3", 29_100), stopAt("direct", 2, "d", "L3", 32_400)),
			List.of(), List.of(), null, access);
	}

	private static LoadRouteTimetablePort.TransitStopTime stopAt(
		String trip, int sequence, String station, String line, int seconds
	) {
		return new LoadRouteTimetablePort.TransitStopTime(trip, sequence, station, line, seconds, seconds, 0, 0);
	}

	private static final java.time.LocalDate BOUNDARY_DATE = JourneyEngineSyntheticBundles.WEDNESDAY;
	private static final int BOUNDARY_ARRIVAL = 8 * 3_600 + 600;

	/**
	 * 출발역 o에서 L1으로 환승역 x에 08:10에 내리고, L2가 x에서 (도착 + 환승 시간 + 승차 여유 + delta)에 떠나는 번들.
	 * 환승 시간은 엔진이 아니라 기준 해 입력 정규화가 그 프로필·속도로 계산한 값이다. 10분 뒤 L2 후속 열차도 둔다.
	 */
	private static RouteTimetable boundaryTimetable(Mobility mobility, JourneyRequest.WalkingPace pace, int delta) {
		var edge = new LoadRouteTimetablePort.PathwayEdge("e-x-L1-L2", "p-x-L1", "p-x-L2", 150, 0, false, false, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED");
		var access = new LoadRouteTimetablePort.RouteAccessData(
			List.of(new LoadRouteTimetablePort.PathwayNode("p-x-L1", "x", "L1", "PLATFORM"),
				new LoadRouteTimetablePort.PathwayNode("p-x-L2", "x", "L2", "PLATFORM")),
			List.of(edge),
			List.of(new LoadRouteTimetablePort.TransferRule("rule-x", "x", "L1", "x", "L2", "IN_STATION", 150,
				edge.id(), edge.id(), "VERIFIED")),
			List.of(new LoadRouteTimetablePort.RouteEdgeEvidence("ev-x", "x", "L2", edge.id(), "TRANSFER",
				"OFFICIAL_SOURCE", "VERIFIED", true, null)));
		int walk = JourneyProfileOracleAccessInputs.normalize(access, mobility.profile(), mobility.constraint(),
			pace.speedMetersPerHour(), 1).getFirst().durationSeconds();
		int departure = BOUNDARY_ARRIVAL + walk + mobility.boardingSlackSeconds() + delta;
		var calendar = new LoadRouteTimetablePort.ServiceCalendar("daily", true, true, true, true, true, true, true,
			BOUNDARY_DATE.minusDays(2), BOUNDARY_DATE.plusDays(2), "Asia/Seoul");
		return new RouteTimetable(List.of(calendar), List.of(),
			List.of(new LoadRouteTimetablePort.TransitRoute("r1", "L1", "1", "1", "up", "Asia/Seoul"),
				new LoadRouteTimetablePort.TransitRoute("r2", "L2", "2", "2", "up", "Asia/Seoul")),
			List.of(new LoadRouteTimetablePort.TransitTrip("feeder", "r1", "daily", "x", "up", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip("boundary", "r2", "daily", "d", "up", "LOCAL", 0),
				new LoadRouteTimetablePort.TransitTrip("next", "r2", "daily", "d", "up", "LOCAL", 0)),
			List.of(new LoadRouteTimetablePort.TransitStopTime("feeder", 1, "o", "L1", BOUNDARY_ARRIVAL - 600,
					BOUNDARY_ARRIVAL - 600, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("feeder", 2, "x", "L1", BOUNDARY_ARRIVAL, BOUNDARY_ARRIVAL, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("boundary", 1, "x", "L2", departure, departure, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("boundary", 2, "d", "L2", departure + 600, departure + 600, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("next", 1, "x", "L2", departure + 600, departure + 600, 0, 0),
				new LoadRouteTimetablePort.TransitStopTime("next", 2, "d", "L2", departure + 1_200, departure + 1_200, 0, 0)),
			List.of(), List.of(), null, access);
	}

	private static Case boundaryCase(
		Mobility mobility, JourneyRequest.WalkingPace pace, int delta, Mode mode, int index
	) {
		Instant midnight = BOUNDARY_DATE.atStartOfDay(ServiceDayResolver.ZONE).toInstant();
		Instant feederReady = midnight.plusSeconds(BOUNDARY_ARRIVAL - 600 - mobility.boardingSlackSeconds());
		JourneyRaptorQuery.TemporalQuery temporal = switch (mode) {
			case DEPART_AT -> new JourneyRaptorQuery.DepartAt(feederReady.minusSeconds(120));
			case ARRIVE_BY -> new JourneyRaptorQuery.ArriveBy(feederReady.minusSeconds(1_800), midnight.plusSeconds(43_200));
			case DEPART_BETWEEN -> new JourneyRaptorQuery.DepartBetween(feederReady.minusSeconds(1_800), feederReady.plusSeconds(60));
			case LAST_CONNECTION -> new JourneyRaptorQuery.LastConnection(BOUNDARY_DATE);
		};
		var query = new JourneyRaptorQuery(
			JourneyProfileFullCorpusRunner.requestId("boundary-" + index + "-" + delta, "o", "d", temporal), "o", "d",
			temporal, JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, pace, mobility.profile(), mobility.constraint(), 1, 3,
			() -> false);
		return new Case("boundary", delta, index, mode, query);
	}

	@Test
	@DisplayName("생성기와 질의는 시드가 같으면 같은 입력을 만든다")
	void generatorIsDeterministicPerSeed() {
		var first = JourneyEngineSyntheticBundles.generate(DEFAULT_BASE_SEED);
		var second = JourneyEngineSyntheticBundles.generate(DEFAULT_BASE_SEED);
		assertThat(second.timetable()).isEqualTo(first.timetable());
		assertThat(syntheticCases(second, QUERIES_PER_BUNDLE).stream().map(Case::describe).toList())
			.isEqualTo(syntheticCases(first, QUERIES_PER_BUNDLE).stream().map(Case::describe).toList());
		assertThat(JourneyEngineSyntheticBundles.generate(DEFAULT_BASE_SEED + 1).timetable())
			.isNotEqualTo(first.timetable());
	}

	// ---------------------------------------------------------------- 질의 생성

	static List<Case> syntheticCases(JourneyEngineSyntheticBundles.Bundle bundle, int count) {
		Random random = new Random(bundle.seed() * 1_000_003L + 17);
		List<String> served = servedStations(bundle.timetable());
		List<Case> cases = new ArrayList<>();
		Instant day = bundle.queryDate().atStartOfDay(ServiceDayResolver.ZONE).toInstant();
		int start = bundle.band().startSeconds - 1_800;
		int span = bundle.band().endSeconds - start;
		for (int index = 0; index < count; index += 1) {
			String origin = served.get(random.nextInt(served.size()));
			String destination;
			do destination = served.get(random.nextInt(served.size())); while (destination.equals(origin));
			Mode mode = mode(random);
			Instant readyAt = day.plusSeconds(start + random.nextInt(span));
			// 심야 대역 일부는 03:00 서비스일 경계 직후로 옮겨 경계 처리도 비교한다.
			if (bundle.band() == JourneyEngineSyntheticBundles.Band.LATE_NIGHT && random.nextInt(8) == 0) {
				readyAt = day.plusSeconds(97_200 - 900 + random.nextInt(1_800));
			}
			JourneyRaptorQuery.TemporalQuery temporal = switch (mode) {
				case DEPART_AT -> new JourneyRaptorQuery.DepartAt(readyAt);
				case ARRIVE_BY -> new JourneyRaptorQuery.ArriveBy(readyAt, readyAt.plusSeconds(1_800 + random.nextInt(7_200)));
				case DEPART_BETWEEN -> new JourneyRaptorQuery.DepartBetween(readyAt, readyAt.plusSeconds(300 + random.nextInt(2_700)));
				case LAST_CONNECTION -> new JourneyRaptorQuery.LastConnection(
					random.nextInt(4) == 0 ? bundle.queryDate().minusDays(1) : bundle.queryDate());
			};
			cases.add(testCase("synthetic", bundle.seed(), index, mode, origin, destination, temporal, random));
		}
		return cases;
	}

	private static List<Case> line4Cases(RouteTimetable timetable, int count) {
		Random random = new Random(DEFAULT_BASE_SEED + 4);
		List<String> served = servedStations(timetable);
		List<Case> cases = new ArrayList<>();
		// 2026-07-06(월)은 운행일, 2026-07-04(토)는 평일 달력이라 운행이 없는 날이다.
		List<LocalDate> dates = List.of(LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 6),
			LocalDate.of(2026, 7, 4));
		// 하행 운행 방향의 역 순서. 대부분은 운행 방향 OD를, 일부는 역방향(경로 없음) OD를 묻는다.
		List<String> corridor = timetable.transitStopTimes().stream()
			.filter(stop -> stop.tripId().equals(timetable.transitTrips().getFirst().id()))
			.sorted(java.util.Comparator.comparingInt(LoadRouteTimetablePort.TransitStopTime::stopSequence))
			.map(LoadRouteTimetablePort.TransitStopTime::stationId).toList();
		for (int index = 0; index < count; index += 1) {
			String origin;
			String destination;
			if (random.nextInt(5) > 0) {
				int from = random.nextInt(corridor.size() - 1);
				origin = corridor.get(from);
				destination = corridor.get(from + 1 + random.nextInt(corridor.size() - from - 1));
			} else {
				origin = served.get(random.nextInt(served.size()));
				do destination = served.get(random.nextInt(served.size())); while (destination.equals(origin));
			}
			Mode mode = Mode.values()[index % Mode.values().length];
			LocalDate date = dates.get(random.nextInt(dates.size()));
			// 실 시각표 3편은 06:42~07:08에 출발해 08:30~08:43에 도착한다.
			Instant readyAt = date.atStartOfDay(ServiceDayResolver.ZONE).toInstant().plusSeconds(22_800 + random.nextInt(4_800));
			JourneyRaptorQuery.TemporalQuery temporal = switch (mode) {
				case DEPART_AT -> new JourneyRaptorQuery.DepartAt(readyAt);
				case ARRIVE_BY -> new JourneyRaptorQuery.ArriveBy(readyAt.minusSeconds(1_800),
					readyAt.plusSeconds(3_600 + random.nextInt(7_200)));
				case DEPART_BETWEEN -> new JourneyRaptorQuery.DepartBetween(readyAt, readyAt.plusSeconds(300 + random.nextInt(2_400)));
				case LAST_CONNECTION -> new JourneyRaptorQuery.LastConnection(date);
			};
			cases.add(testCase("line4-corridor-slice", DEFAULT_BASE_SEED + 4, index, mode, origin, destination, temporal, random));
		}
		return cases;
	}

	private static Case testCase(
		String fixture, long seed, int index, Mode mode, String origin, String destination,
		JourneyRaptorQuery.TemporalQuery temporal, Random random
	) {
		Mobility mobility = Mobility.ALL.get(random.nextInt(Mobility.ALL.size()));
		JourneyRequest.WalkingPace pace = JourneyRequest.WalkingPace.values()[random.nextInt(3)];
		int maxTransfers = switch (random.nextInt(20)) {
			case 0, 1, 2 -> 0;
			case 3, 4, 5, 6, 7, 8, 9 -> 1;
			case 10, 11, 12, 13, 14, 15 -> 2;
			default -> 3;
		};
		// 출발 시간대는 시점마다 기준 해를 따로 풀고, 연결 여유가 최대화 기준이라 다음 날까지 기다리는 여정도 파레토에
		// 남는다. 전수 열거 기준 해의 비용이 환승 3회에서 폭증하므로 이 모드만 2회로 묶는다(3회는 다른 세 모드가 덮는다).
		if (mode == Mode.DEPART_BETWEEN) maxTransfers = Math.min(maxTransfers, 2);
		int alternatives = 1 + random.nextInt(3);
		var query = new JourneyRaptorQuery(
			JourneyProfileFullCorpusRunner.requestId(fixture + "-" + seed + "-" + index, origin, destination, temporal),
			origin, destination, temporal, JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, pace,
			mobility.profile(), mobility.constraint(), maxTransfers, alternatives, () -> false);
		return new Case(fixture, seed, index, mode, query);
	}

	private static Mode mode(Random random) {
		int value = random.nextInt(20);
		if (value < 6) return Mode.DEPART_AT;
		if (value < 11) return Mode.ARRIVE_BY;
		if (value < 16) return Mode.DEPART_BETWEEN;
		return Mode.LAST_CONNECTION;
	}

	private static List<String> servedStations(RouteTimetable timetable) {
		return List.copyOf(timetable.transitStopTimes().stream().map(LoadRouteTimetablePort.TransitStopTime::stationId)
			.collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new)));
	}

	// ---------------------------------------------------------------- 최소 반례

	/** 같은 질의가 계속 틀리는 동안 열차와 환승 규칙을 하나씩 지운다(1-minimal). */
	static String minimalCounterexample(Case testCase, RouteTimetable source) {
		Set<String> removedTrips = new LinkedHashSet<>();
		Set<String> removedRules = new LinkedHashSet<>();
		for (var trip : source.transitTrips()) {
			removedTrips.add(trip.id());
			if (!stillFails(testCase, JourneyEngineSyntheticBundles.without(source, removedTrips, removedRules))) {
				removedTrips.remove(trip.id());
			}
		}
		for (var rule : source.routeAccessData().transferRules()) {
			removedRules.add(rule.id());
			if (!stillFails(testCase, JourneyEngineSyntheticBundles.without(source, removedTrips, removedRules))) {
				removedRules.remove(rule.id());
			}
		}
		RouteTimetable minimal = JourneyEngineSyntheticBundles.without(source, removedTrips, removedRules);
		Verdict verdict = JourneyEngineDifferentialHarness.check(testCase, minimal, compile(minimal));
		return "COUNTEREXAMPLE " + testCase.describe() + "\n  mismatch:\n    " + verdict.mismatch()
			+ "\n  minimal bundle (" + minimal.transitTrips().size() + " trips, "
			+ minimal.routeAccessData().transferRules().size() + " transfer rules):\n"
			+ JourneyEngineSyntheticBundles.describe(minimal);
	}

	private static boolean stillFails(Case testCase, RouteTimetable candidate) {
		try {
			return !JourneyEngineDifferentialHarness.check(testCase, candidate, compile(candidate)).matched();
		} catch (RuntimeException exception) {
			// 열차를 지워 입력 자체가 성립하지 않으면 그 축소는 반례가 아니다.
			return false;
		}
	}

	private static RaptorRouteBundleRuntimeView compile(RouteTimetable timetable) {
		return RaptorRouteBundleRuntimeView.compile("c".repeat(64), 1, timetable);
	}

	// ---------------------------------------------------------------- 실데이터 고정 입력

	/** KRIC 4호선 실 시각표 코리도 슬라이스(평일 아침 하행 3편). 환승이 없는 단일 노선이다. */
	static RouteTimetable line4CorridorSlice() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:engine-differential-line4;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
		var jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("DROP ALL OBJECTS");
		for (String migration : List.of("V16__datapack_source_snapshots.sql", "V17__datapack_alias_quarantine_ledgers.sql",
			"V19__datapack_route_edge_evidence.sql", "V29__canonical_transit_schedule.sql",
			"V30__canonical_station_pathways.sql", "V37__transit_feed_info.sql", "V50__route_service_identity.sql",
			"V62__route_v2_planner_identity.sql")) {
			jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/" + migration + "'");
		}
		jdbc.execute("RUNSCRIPT FROM 'src/test/resources/timetable/line4-corridor-slice-seed.sql'");
		var loaded = new JdbcRouteTimetableRepository(dataSource).loadRouteTimetable();
		return new RouteTimetable(loaded.serviceCalendars(), loaded.serviceCalendarDates(), loaded.transitRoutes(),
			loaded.transitTrips(), loaded.transitStopTimes(), loaded.transitFrequencies(), loaded.officialFares(),
			loaded.feedEndDate(), LoadRouteTimetablePort.RouteAccessData.empty());
	}

	private static long environmentLong(String name, long fallback) {
		String value = System.getenv(name);
		return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
	}

	// ---------------------------------------------------------------- 집계

	static final class Tally {
		int queries;
		int mismatches;
		long expectedLabels;
		int extraEnginePoints;
		int withTransfer;
		int withTwoTransfers;
		int serviceDayCrossing;
		int zeroSlack;
		int truncatedPointFront;
		final Map<Mode, Integer> queriesByMode = new EnumMap<>(Mode.class);
		final Map<Mode, Integer> nonEmptyByMode = new EnumMap<>(Mode.class);
		final Map<Mobility, Integer> nonEmptyByMobility = new java.util.LinkedHashMap<>();
		final Map<Mode, Long> nanosByMode = new EnumMap<>(Mode.class);
		long slowestNanos;
		String slowest = "-";

		void time(Verdict verdict, long nanos) {
			nanosByMode.merge(verdict.testCase().mode(), nanos, Long::sum);
			if (nanos > slowestNanos) {
				slowestNanos = nanos;
				slowest = verdict.testCase().describe();
			}
		}

		void add(Verdict verdict) {
			queries += 1;
			if (!verdict.matched()) mismatches += 1;
			Mode mode = verdict.testCase().mode();
			queriesByMode.merge(mode, 1, Integer::sum);
			expectedLabels += verdict.expectedLabels();
			extraEnginePoints += verdict.extraEnginePoints();
			if (verdict.expectedLabels() > 0) {
				nonEmptyByMode.merge(mode, 1, Integer::sum);
				nonEmptyByMobility.merge(Mobility.of(verdict.testCase().query()), 1, Integer::sum);
			}
			if (verdict.maxTransfersUsed() >= 1) withTransfer += 1;
			if (verdict.maxTransfersUsed() >= 2) withTwoTransfers += 1;
			if (verdict.serviceDayCrossing()) serviceDayCrossing += 1;
			if (verdict.zeroSlack()) zeroSlack += 1;
			if (verdict.truncatedPointFront()) truncatedPointFront += 1;
		}

		/** 검증이 공허하지 않도록 비교가 실제로 일어난 범위를 강제한다. */
		void requireCoverage() {
			for (Mode mode : Mode.values()) {
				assertThat(nonEmptyByMode.getOrDefault(mode, 0)).as("비어 있지 않은 %s 질의", mode).isGreaterThanOrEqualTo(80);
			}
			for (Mobility mobility : Mobility.ALL) {
				assertThat(nonEmptyByMobility.getOrDefault(mobility, 0)).as("비어 있지 않은 %s 질의", mobility)
					.isGreaterThanOrEqualTo(40);
			}
			assertThat(withTransfer).as("환승 1회 이상 기준 해 질의").isGreaterThanOrEqualTo(150);
			assertThat(withTwoTransfers).as("환승 2회 이상 기준 해 질의").isGreaterThanOrEqualTo(20);
			assertThat(serviceDayCrossing).as("자정을 넘는 서비스일 승차 질의").isGreaterThanOrEqualTo(40);
			assertThat(zeroSlack).as("환승 여유가 정확히 0초인 경계 연결을 포함한 질의").isGreaterThanOrEqualTo(10);
		}

		String summary() {
			return "queries=" + queries + " mismatches=" + mismatches + " matchRate="
				+ (queries == 0 ? "n/a" : String.format(java.util.Locale.ROOT, "%.2f%%", 100.0 * (queries - mismatches) / queries))
				+ " comparedLabels=" + expectedLabels + " byMode=" + queriesByMode + " nonEmptyByMode=" + nonEmptyByMode
				+ " withTransfer=" + withTransfer + " withTwoTransfers=" + withTwoTransfers
				+ " serviceDayCrossing=" + serviceDayCrossing + " zeroSlack=" + zeroSlack
				+ " truncatedPointFront=" + truncatedPointFront
				+ " extraEnginePoints=" + extraEnginePoints
				+ " nonEmptyByMobility=" + nonEmptyByMobility + " millisByMode=" + nanosByMode.entrySet().stream()
					.map(entry -> entry.getKey() + "=" + entry.getValue() / 1_000_000).toList()
				+ " slowest=" + slowestNanos / 1_000_000 + "ms " + slowest;
		}
	}
}
