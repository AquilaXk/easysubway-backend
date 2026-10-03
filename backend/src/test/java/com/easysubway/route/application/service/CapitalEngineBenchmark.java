package com.easysubway.route.application.service;

import com.easysubway.journey.application.JourneyProfileRaptorPort;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.ServiceDayResolver;
import com.easysubway.route.application.service.JourneyEngineDifferentialHarness.Mobility;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * #462: 수도권 실데이터 fixture({@link CapitalRealDerivedFixture}) 위의 엔진 벤치마크·프로파일 구동기.
 *
 * <p>테스트가 아니라 손으로 돌리는 측정 진입점이다. 성능 게이트({@link JourneyEnginePerformanceGateTest})는 격자 번들과
 * 4호선 슬라이스로 회귀를 막고, 이 구동기는 저수준 최적화의 전후를 실데이터 규모에서 잰다. 출발 시각 고정은 시드 고정
 * 무작위 OD 400건, 프로필 모드는 {@link ProfileSearchWithinResourcePolicyTest}의 모바일 대표 질의 14건을 운영 후보 정책
 * 한도로 돌린다. 모드마다 결과와 결정적 작업량의 지문(sha256)을 찍어 최적화 전후 결과가 같은지 확인한다.</p>
 *
 * <p>실행: 테스트 런타임 classpath로 {@code java -Xms1g -Xmx1g -cp <test runtime classpath>
 * com.easysubway.route.application.service.CapitalEngineBenchmark [rounds]}. JFR 프로파일은 같은 명령에
 * {@code -XX:StartFlightRecording=settings=profile,filename=capital.jfr}를 붙인다.</p>
 */
final class CapitalEngineBenchmark {

	private static final int DEPART_AT_QUERIES = 400;
	private static final long SEED = 4_620_462L;

	private CapitalEngineBenchmark() {
	}

	private record Query(String mode, JourneyRaptorQuery query) {
	}

	public static void main(String[] args) throws Exception {
		int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 5;
		int warmup = args.length > 1 ? Integer.parseInt(args[1]) : 3;
		var runtime = RaptorRouteBundleRuntimeView.compile("c".repeat(64), 1, CapitalRealDerivedFixture.load());
		var planner = new RouteTimetableRaptorPlanner();
		var adapter = new JourneyProfileRaptorAdapter();
		List<Query> queries = queries();
		Map<String, String> fingerprints = null;
		for (int round = 0; round < warmup; round += 1) fingerprints = runAll(queries, runtime, planner, adapter, null, null);
		Map<String, List<Long>> nanos = new LinkedHashMap<>();
		Map<String, List<Long>> bytes = new LinkedHashMap<>();
		for (int round = 0; round < rounds; round += 1) {
			ManagementFactory.getMemoryMXBean().gc();
			Map<String, String> measured = runAll(queries, runtime, planner, adapter, nanos, bytes);
			if (!measured.equals(fingerprints)) throw new IllegalStateException("results differ across rounds");
		}
		System.out.println("mode            queries  p50(us)   p99(us)   mean(us)  bytes/query   qps    fingerprint");
		for (String mode : nanos.keySet()) {
			long[] sorted = nanos.get(mode).stream().mapToLong(Long::longValue).sorted().toArray();
			long total = Arrays.stream(sorted).sum();
			long allocated = bytes.get(mode).stream().mapToLong(Long::longValue).sum();
			long perRound = sorted.length / rounds;
			System.out.printf(Locale.ROOT, "%-15s %7d %9.1f %9.1f %9.1f %12d %7.0f    %s%n", mode, perRound,
				percentile(sorted, 50) / 1e3, percentile(sorted, 99) / 1e3, total / 1e3 / sorted.length,
				allocated / sorted.length, sorted.length / (total / 1e9), fingerprints.get(mode).substring(0, 16));
		}
	}

	private static Map<String, String> runAll(
		List<Query> queries, RaptorRouteBundleRuntimeView runtime, RouteTimetableRaptorPlanner planner,
		JourneyProfileRaptorAdapter adapter, Map<String, List<Long>> nanos, Map<String, List<Long>> bytes
	) throws Exception {
		var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		Map<String, MessageDigest> digests = new LinkedHashMap<>();
		for (Query query : queries) {
			long allocated = threads.getCurrentThreadAllocatedBytes();
			long started = System.nanoTime();
			Object result = query.mode().equals("DEPART_AT")
				? planner.journeyItineraries(query.query(), runtime.compiledTimetable())
				: adapter.planRuntime(query.query(), runtime, null, JourneyEnginePerformanceGateTest.CANDIDATE_POLICY_LIMITS);
			long elapsed = System.nanoTime() - started;
			long used = threads.getCurrentThreadAllocatedBytes() - allocated;
			String fingerprint = result instanceof RouteTimetableRaptorPlanner.JourneyPlan plan
				? plan.itineraries() + " " + plan.scanMetrics() : profileFingerprint((JourneyProfileRaptorPort.PlanningResult) result);
			if (nanos != null) {
				nanos.computeIfAbsent(query.mode(), ignored -> new ArrayList<>()).add(elapsed);
				bytes.computeIfAbsent(query.mode(), ignored -> new ArrayList<>()).add(used);
			}
			MessageDigest digest = digests.computeIfAbsent(query.mode(), ignored -> sha256());
			digest.update(fingerprint.getBytes(StandardCharsets.UTF_8));
		}
		Map<String, String> fingerprints = new LinkedHashMap<>();
		digests.forEach((mode, digest) -> fingerprints.put(mode, HexFormat.of().formatHex(digest.digest())));
		return fingerprints;
	}

	/** 결과·작업량 지문. 규칙별 횟수 맵은 JVM마다 순회 순서가 달라 정렬해 찍는다. */
	private static String profileFingerprint(JourneyProfileRaptorPort.PlanningResult result) {
		String outcome = switch (result) {
			case JourneyProfileRaptorPort.PlanningResult.Planned planned -> String.valueOf(planned.temporalPlan());
			case JourneyProfileRaptorPort.PlanningResult.CapacityExceeded capacity -> "capacity:" + capacity.dimension();
			case JourneyProfileRaptorPort.PlanningResult.AdmissionRejected ignored -> "admission";
		};
		return outcome + " " + result.planningMetrics() + " " + new TreeMap<>(result.countSnapshot().countsByRuleId());
	}

	private static List<Query> queries() {
		List<String> stations = CapitalRealDerivedFixture.stations();
		Random random = new Random(SEED);
		Instant day = CapitalRealDerivedFixture.SERVICE_DATE.atStartOfDay(ServiceDayResolver.ZONE).toInstant();
		List<Query> queries = new ArrayList<>();
		for (int index = 0; index < DEPART_AT_QUERIES; index += 1) {
			String origin = stations.get(random.nextInt(stations.size()));
			String destination;
			do destination = stations.get(random.nextInt(stations.size())); while (destination.equals(origin));
			Instant readyAt = day.plusSeconds(23_400 + random.nextInt(55_800));
			Mobility mobility = Mobility.ALL.get(index % Mobility.ALL.size());
			var temporal = new JourneyRaptorQuery.DepartAt(readyAt);
			queries.add(new Query("DEPART_AT", new JourneyRaptorQuery(
				JourneyProfileFullCorpusRunner.requestId("capital-" + index, origin, destination, temporal), origin, destination,
				temporal, JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
				mobility.profile(), mobility.constraint(), 3, 3, () -> false)));
		}
		int index = 0;
		for (var testCase : ProfileSearchWithinResourcePolicyTest.cases()) {
			queries.add(new Query(testCase.mode(), testCase.query(index++)));
		}
		return List.copyOf(queries);
	}

	private static long percentile(long[] sorted, int percent) {
		int rank = (int) Math.ceil(percent / 100.0 * sorted.length);
		return sorted[Math.max(0, rank - 1)];
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
