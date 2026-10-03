package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.JourneyProfileRaptorPort;
import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.ServiceDayResolver;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.service.JourneyEngineBenchmarkBundle.Grid;
import com.easysubway.route.application.service.JourneyEngineDifferentialHarness.Mobility;
import com.easysubway.route.application.service.JourneyEngineDifferentialHarness.Mode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #460: 경로 엔진 성능 회귀 게이트.
 *
 * <p>고정 번들({@link JourneyEngineBenchmarkBundle}) 위에서 고정 질의 집합을 돌려 컴파일 시간·보유 힙·질의별 할당량·
 * 모드별 p50/p99 탐색 지연과 탐색 작업량 카운터를 재고, 커밋된 기준선
 * {@code src/test/resources/journey-benchmark/engine-performance-baseline.json}과 비교한다. 허용폭을 넘으면 실패한다.
 * 도시철도 규모(metro) 번들은 컴파일과 출발 시각 고정 탐색을, 구역 규모(district) 번들과 실데이터 4호선 코리도
 * 슬라이스는 네 질의 모드를 모두 잰다. 현재 프로필 탐색(도착 희망·출발 시간대·막차)은 격자 번들에서 아래 벤치마크
 * 한도 안에 끝나지 않아 fail-closed로 끝나며, 그 건수와 거절까지의 비용도 회귀 대상으로 기록한다.</p>
 *
 * <ul>
 *   <li>작업량 카운터(확장한 노선·열차·환승, 소비 작업량, 상태·도착 라벨 최대치, 시점 수, 가지치기 규칙별 횟수,
 *   결과 수, 한도 초과 수)는 결정적이라 잡음이 없다. 기준선과 양방향 2% 안이어야 한다. 크게 좋아져도 실패해
 *   기준선을 새로 고치게 한다(ratchet).</li>
 *   <li>질의별 할당량과 컴파일 보유 힙은 JIT 판단에 따라 조금 흔들린다. 30% 넘게 늘면 실패한다.</li>
 *   <li>벽시계 지연과 컴파일 시간은 같은 JVM에서 잰 고정 보정 작업 시간으로 나눈 값을 쓴다. p50과 p99는 측정 5회
 *   각각의 최근순위 백분위의 중앙값이고, 성공 경로 계열은 회차당 질의가 100건 이상이다. 출발 시각 고정(DepartAt) p50은 잡음이 작아 2.0배,
 *   나머지(p99, 프로필 모드, 컴파일)는 2.5배를 넘으면 실패한다. 이 한도보다 작은 상수배 둔화(작업량 카운터가 그대로인
 *   내부 루프 비용 증가 등)는 설계상 통과시킨다. 보정 작업과 엔진의 속도 비율은 CPU 아키텍처마다 달라서 벽시계
 *   기준선은 Backend CI 러너 측정값을 쓴다.</li>
 * </ul>
 *
 * <p>측정은 새 JVM 하나에서 한다({@link #forkedMeasurement()}). 기준선 갱신 절차는 엔진 설계 문서
 * (같은 패키지 테스트 소스의 package-info.java)의 7절에 있다.</p>
 */
@DisplayName("#460 경로 엔진 성능 회귀 게이트")
class JourneyEnginePerformanceGateTest {

	static final Path BASELINE = Path.of("src/test/resources/journey-benchmark/engine-performance-baseline.json");
	private static final Path MEASURED = Path.of("build/journey-benchmark/engine-performance-measured.json");
	private static final int WARMUP_ROUNDS = 2;
	private static final int MEASURED_ROUNDS = 5;
	private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
	/**
	 * 벤치마크 전용 프로필 한도. 운영 정책 값은 배포 산출물(platform)에 있고 저장소에 없다. 한도가 없으면 출발 시간대
	 * McRAPTOR 라벨 풀이 작업 예산까지 커져 힙을 다 쓰므로 상한을 두고, 한도 초과(fail-closed) 건수도 작업량으로 잰다.
	 */
	static final JourneyProfileResourcePolicy.ProfilePlanningLimits BENCHMARK_LIMITS =
		new JourneyProfileResourcePolicy.ProfilePlanningLimits(5_000_000L, 64, 64, 64);
	/**
	 * 운영 배포 후보 정책의 프로필 한도. 같은 프로필 질의를 이 한도로 다시 돌려 운영에서 거절되는 비율을 결정적으로
	 * 기록한다. 다른 레포를 CI에서 읽지 않으므로 출처를 아래 {@link #CANDIDATE_POLICY_SOURCE}에 고정해 손으로 맞추는
	 * 동기화 지점이다. 출처 파일의 값(RAPTOR_RESOURCE_POLICY_V1 1.1.0, platform#218에서 #461 실측으로 역산):
	 * <pre>
	 *   44  maxTemporalWindowSeconds: 3600,
	 *   45  maxServiceDayCount: 2,
	 *   46  maxEstimatedWork: 10000000,
	 *   47  maxLabelsPerState: 2048,
	 *   48  maxDestinationProfileLabels: 128,
	 *   49  maxProfileBreakpoints: 128,
	 * </pre>
	 * 정책이 바뀌면 이 상수와 출처 sha를 함께 바꾸고 기준선의 재현 결과를 다시 만든다(설계 문서 7절, #461).
	 */
	static final JourneyProfileResourcePolicy.ProfilePlanningLimits CANDIDATE_POLICY_LIMITS =
		new JourneyProfileResourcePolicy.ProfilePlanningLimits(10_000_000L, 2_048, 128, 128);
	/**
	 * 위 한도를 옮겨 온 정확한 출처(레포@커밋:경로#행). 기준선에 함께 기록되어 바뀌면 게이트가 실패한다. 커밋은
	 * platform#218의 PR head이며 PR ref로 계속 열람할 수 있다. squash 병합된 main 커밋은 이 head와 같은 파일 내용을
	 * 담은 다른 sha이므로, 병합 후에도 두 sha가 같은 정책 값에 대응한다(정책 JSON sha256 6fdecdfb…로 확인).
	 */
	static final String CANDIDATE_POLICY_SOURCE = "AquilaXk/easysubway-platform@1cb1a5f9012dc0710fb2f4b3f6e968ccb187f6d9"
		+ ":tools/platform/render-journey-kubernetes-candidate.mjs#L44-L49";
	private static final Map<Mode, Integer> POLICY_REPLAY_COUNTS =
		Map.of(Mode.ARRIVE_BY, 12, Mode.DEPART_BETWEEN, 12, Mode.LAST_CONNECTION, 6);

	/**
	 * 고정 작업 부하. 번들·질의 수·시드를 바꾸면 digest가 바뀌어 기준선을 새로 만들어야 한다.
	 * {@code day}와 {@code firstReadySeconds}·{@code readySpanSeconds}는 준비 시각을 뽑는 범위다.
	 */
	record Workload(
		String id, java.util.function.Supplier<RouteTimetable> timetable,
		java.util.function.BiFunction<RouteTimetable, Random, String[]> odPair,
		String querySetId, long seed, Map<Mode, Integer> counts,
		java.time.LocalDate day, int firstReadySeconds, int readySpanSeconds
	) {
		static final List<Workload> ALL = List.of(
			grid(JourneyEngineBenchmarkBundle.METRO, "metro-grid-queries-v1", 4_600_460L, Map.of(Mode.DEPART_AT, 160)),
			grid(JourneyEngineBenchmarkBundle.DISTRICT, "district-grid-queries-v1", 4_600_461L,
				Map.of(Mode.DEPART_AT, 100, Mode.ARRIVE_BY, 12, Mode.DEPART_BETWEEN, 12, Mode.LAST_CONNECTION, 6)),
			// 실데이터(KRIC 4호선 실 시각표 코리도 슬라이스). 하행 3편이 06:42~07:08에 출발한다.
			new Workload("line4-corridor-slice", JourneyEngineRandomizedDifferentialTest::line4CorridorSlice,
				JourneyEnginePerformanceGateTest::downstreamPair, "line4-corridor-queries-v1", 4_600_462L,
				Map.of(Mode.DEPART_AT, 100, Mode.ARRIVE_BY, 100, Mode.DEPART_BETWEEN, 100, Mode.LAST_CONNECTION, 100),
				java.time.LocalDate.of(2026, 7, 6), 22_800, 2_400));

		static Workload grid(Grid grid, String querySetId, long seed, Map<Mode, Integer> counts) {
			List<String> stations = JourneyEngineBenchmarkBundle.stations(grid);
			return new Workload(grid.id(), () -> JourneyEngineBenchmarkBundle.build(grid), (ignored, random) -> {
				String origin = stations.get(random.nextInt(stations.size()));
				String destination;
				do destination = stations.get(random.nextInt(stations.size())); while (destination.equals(origin));
				return new String[] {origin, destination};
			}, querySetId, seed, counts, JourneyEngineSyntheticBundles.WEDNESDAY, 23_400, 50_400);
		}
	}

	/** 운행 방향 OD. 첫 열차의 정차 순서에서 앞 역을 출발, 뒤 역을 도착으로 고른다. */
	private static String[] downstreamPair(RouteTimetable timetable, Random random) {
		List<String> corridor = timetable.transitStopTimes().stream()
			.filter(stop -> stop.tripId().equals(timetable.transitTrips().getFirst().id()))
			.sorted(java.util.Comparator.comparingInt(stop -> stop.stopSequence()))
			.map(stop -> stop.stationId()).toList();
		int from = random.nextInt(corridor.size() - 1);
		return new String[] {corridor.get(from), corridor.get(from + 1 + random.nextInt(corridor.size() - from - 1))};
	}

	@Test
	@DisplayName("고정 번들·고정 질의의 지연·힙·작업량이 기준선 허용폭 안에 있다")
	void staysWithinTheCommittedPerformanceBaseline() throws IOException, InterruptedException {
		Map<String, Object> measured = forkedMeasurement();
		Files.createDirectories(MEASURED.getParent());
		String json = JSON.writeValueAsString(measured);
		Files.writeString(MEASURED, json + "\n", StandardCharsets.UTF_8);
		System.out.println("#460 engine performance measured:\n" + json);
		if (Boolean.parseBoolean(System.getenv("EASYSUBWAY_PERF_BASELINE_WRITE"))) {
			Map<String, Object> baseline = new LinkedHashMap<>(measured);
			baseline.put("tolerances", Tolerances.DEFAULT.asMap());
			Files.writeString(BASELINE, JSON.writeValueAsString(baseline) + "\n", StandardCharsets.UTF_8);
		}
		JsonNode baseline = JSON.readTree(Files.readString(BASELINE, StandardCharsets.UTF_8));
		List<String> regressions = regressions(baseline, JSON.valueToTree(measured));
		assertThat(regressions).as("성능 기준선 위반. 측정값:\n%s", json).isEmpty();
	}

	@Test
	@DisplayName("게이트 판정은 허용폭을 넘는 회귀와 오래된 기준선을 잡는다")
	void gateRejectsRegressionsBeyondToleranceAndStaleBaselines() {
		JsonNode baseline = tree(sample(1_000, 2_000_000, 1_000_000, 100_000_000, "bundle"));
		assertThat(regressions(baseline, tree(sample(1_000, 2_000_000, 1_000_000, 100_000_000, "bundle")))).isEmpty();
		assertThat(regressions(baseline, tree(sample(1_019, 2_000_000, 1_000_000, 100_000_000, "bundle")))).isEmpty();
		assertThat(regressions(baseline, tree(sample(1_021, 2_000_000, 1_000_000, 100_000_000, "bundle"))))
			.singleElement().asString().contains("work", "DEPART_AT.expandedTrips", "regressed");
		assertThat(regressions(baseline, tree(sample(979, 2_000_000, 1_000_000, 100_000_000, "bundle"))))
			.singleElement().asString().contains("stale");
		assertThat(regressions(baseline, tree(sample(1_000, 2_000_000, 1_400_000, 100_000_000, "bundle"))))
			.singleElement().asString().contains("allocatedBytesPerQuery");
		assertThat(regressions(baseline, tree(sample(1_000, 5_100_000, 1_000_000, 100_000_000, "bundle"))))
			.anySatisfy(message -> assertThat(message).contains("DEPART_AT.p50Nanos"));
		// 출발 시각 고정 p50은 2.0배를 넘으면 실패한다. 같은 배율의 p99(2.5배 한도)는 통과한다.
		assertThat(regressions(baseline, tree(sample(1_000, 4_100_000, 1_000_000, 100_000_000, "bundle"))))
			.singleElement().asString().contains("DEPART_AT.p50Nanos", "x2.0");
		assertThat(regressions(baseline, tree(sample(1_000, 3_900_000, 1_000_000, 100_000_000, "bundle")))).isEmpty();
		// 러너가 두 배 느리면 보정 작업도 두 배 느리므로 정규화 지연은 그대로다.
		assertThat(regressions(baseline, tree(sample(1_000, 4_000_000, 1_000_000, 200_000_000, "bundle")))).isEmpty();
		assertThat(regressions(baseline, tree(sample(1_000, 2_000_000, 1_000_000, 100_000_000, "other"))))
			.singleElement().asString().contains("bundle identity changed");
		// 운영 후보 정책 재현 결과(거절 수)가 바뀌면 기준선을 새로 고쳐야 한다.
		Map<String, Object> fewerRejections = sample(1_000, 2_000_000, 1_000_000, 100_000_000, "bundle");
		@SuppressWarnings("unchecked")
		Map<String, Object> workload = new LinkedHashMap<>((Map<String, Object>) ((Map<String, Object>) fewerRejections
			.get("workloads")).get("sample-grid"));
		workload.put("candidatePolicyReplay", Map.of("source", CANDIDATE_POLICY_SOURCE,
			"limits", Map.of("maxEstimatedWork", 1_000L, "maxLabelsPerState", 8,
			"maxDestinationProfileLabels", 16, "maxProfileBreakpoints", 32),
			"modes", Map.of("ARRIVE_BY", Map.of("queries", 12, "failClosed", 6L))));
		fewerRejections.put("workloads", Map.of("sample-grid", workload));
		assertThat(regressions(baseline, tree(fewerRejections))).singleElement().asString()
			.contains("policyReplay ARRIVE_BY.failClosed", "stale");
		// 정책 출처(커밋)가 바뀌면 값이 같아도 재현 결과를 다시 확인하고 기준선을 새로 만들어야 한다.
		workload.put("candidatePolicyReplay", Map.of("source", "AquilaXk/easysubway-platform@other:path#L1",
			"limits", Map.of("maxEstimatedWork", 1_000L, "maxLabelsPerState", 8,
			"maxDestinationProfileLabels", 16, "maxProfileBreakpoints", 32),
			"modes", Map.of("ARRIVE_BY", Map.of("queries", 12, "failClosed", 12L))));
		fewerRejections.put("workloads", Map.of("sample-grid", workload));
		assertThat(regressions(baseline, tree(fewerRejections))).singleElement().asString()
			.contains("candidate policy source or limits changed");
		Map<String, Object> missingWorkload = sample(1_000, 2_000_000, 1_000_000, 100_000_000, "bundle");
		missingWorkload.put("workloads", Map.of());
		assertThat(regressions(baseline, tree(missingWorkload))).singleElement().asString().contains("workload");
	}

	// ---------------------------------------------------------------- 측정

	/**
	 * 새 JVM 하나에서 측정한다. 같은 테스트 JVM에서 앞서 돈 다른 테스트가 JIT 타입 프로파일을 오염시키면 인라이닝과
	 * 탈출 분석이 달라져 할당량이 수 배, 지연이 2배 넘게 흔들린다(shard 구성에 따라 달라짐). 그래서 JMH의 fork처럼
	 * 깨끗한 JVM에서 재고 결과 JSON만 읽는다. 측정 JVM에는 커버리지 에이전트를 붙이지 않는다.
	 */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> forkedMeasurement() throws IOException, InterruptedException {
		Path output = Files.createTempFile("engine-performance", ".json");
		Path log = Files.createTempFile("engine-performance", ".log");
		String launcher = ProcessHandle.current().info().command()
			.orElseThrow(() -> new IllegalStateException("current java executable is not observable"));
		Process process = new ProcessBuilder(launcher, "-Xms1g", "-Xmx1g", "-Dfile.encoding=UTF-8",
			"-cp", System.getProperty("java.class.path"), JourneyEnginePerformanceGateTest.class.getName(), output.toString())
			.directory(Path.of(System.getProperty("user.dir")).toFile())
			.redirectErrorStream(true).redirectOutput(log.toFile()).start();
		if (!process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)) {
			process.destroyForcibly();
			throw new IllegalStateException("forked engine measurement timed out");
		}
		if (process.exitValue() != 0) {
			throw new IllegalStateException("forked engine measurement failed with exit " + process.exitValue() + ":\n"
				+ Files.readString(log, StandardCharsets.UTF_8));
		}
		return JSON.readValue(Files.readString(output, StandardCharsets.UTF_8), LinkedHashMap.class);
	}

	/** 측정 전용 JVM 진입점. 인자는 결과 JSON을 쓸 경로다. */
	public static void main(String[] args) throws IOException {
		if (args.length != 1) throw new IllegalArgumentException("usage: <output json path>");
		Files.writeString(Path.of(args[0]), JSON.writeValueAsString(measure()), StandardCharsets.UTF_8);
	}

	static Map<String, Object> measure() {
		var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		long calibration = median(repeat(5, JourneyEnginePerformanceGateTest::calibrationWork));
		Map<String, Object> workloads = new LinkedHashMap<>();
		for (Workload workload : Workload.ALL) workloads.put(workload.id(), measure(workload, threads));
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("schemaVersion", 1);
		Map<String, Object> method = new LinkedHashMap<>();
		method.put("warmupRounds", WARMUP_ROUNDS);
		method.put("measuredRounds", MEASURED_ROUNDS);
		method.put("aggregation", "median over measured rounds of per-round nearest-rank p50 and p99");
		method.put("profileLimits", Map.of("maxEstimatedWork", BENCHMARK_LIMITS.maxEstimatedWork(),
			"maxLabelsPerState", BENCHMARK_LIMITS.maxLabelsPerState(),
			"maxDestinationProfileLabels", BENCHMARK_LIMITS.maxDestinationProfileLabels(),
			"maxProfileBreakpoints", BENCHMARK_LIMITS.maxProfileBreakpoints()));
		method.put("java", System.getProperty("java.vendor") + " " + System.getProperty("java.version"));
		method.put("arch", System.getProperty("os.arch"));
		method.put("processors", Runtime.getRuntime().availableProcessors());
		result.put("method", method);
		result.put("calibrationNanos", calibration);
		result.put("workloads", workloads);
		return result;
	}

	static Map<String, Object> measure(Workload workload, com.sun.management.ThreadMXBean threads) {
		RouteTimetable timetable = workload.timetable().get();
		List<Query> queries = querySet(workload, timetable);
		long[] compileNanos = new long[3];
		long[] compileBytes = new long[3];
		RaptorRouteBundleRuntimeView runtime = null;
		for (int index = 0; index < compileNanos.length; index += 1) {
			long bytes = threads.getCurrentThreadAllocatedBytes();
			long started = System.nanoTime();
			runtime = RaptorRouteBundleRuntimeView.compile("d".repeat(64), 1, timetable);
			compileNanos[index] = System.nanoTime() - started;
			compileBytes[index] = threads.getCurrentThreadAllocatedBytes() - bytes;
		}
		long retained = retainedBytes(timetable);

		var planner = new RouteTimetableRaptorPlanner();
		var adapter = new JourneyProfileRaptorAdapter();
		for (int round = 0; round < WARMUP_ROUNDS; round += 1) {
			for (Query query : queries) run(query, runtime, planner, adapter, BENCHMARK_LIMITS);
		}
		Map<Mode, List<long[]>> nanosByRound = new EnumMap<>(Mode.class);
		Map<Mode, List<Long>> bytesByRound = new EnumMap<>(Mode.class);
		Map<Mode, Map<String, Long>> work = null;
		MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
		for (int round = 0; round < MEASURED_ROUNDS; round += 1) {
			// 회차마다 GC를 먼저 돌린다. 짧은 회차(수 MB 할당)에 GC 정지가 끼면 모은 표본의 p99가 정지 시간이 된다.
			memory.gc();
			Map<Mode, List<Long>> nanos = new EnumMap<>(Mode.class);
			Map<Mode, List<Long>> bytes = new EnumMap<>(Mode.class);
			Map<Mode, Map<String, Long>> roundWork = new EnumMap<>(Mode.class);
			for (Query query : queries) {
				long allocated = threads.getCurrentThreadAllocatedBytes();
				long started = System.nanoTime();
				Map<String, Long> counters = run(query, runtime, planner, adapter, BENCHMARK_LIMITS);
				long elapsed = System.nanoTime() - started;
				long used = threads.getCurrentThreadAllocatedBytes() - allocated;
				nanos.computeIfAbsent(query.mode(), ignored -> new ArrayList<>()).add(elapsed);
				bytes.computeIfAbsent(query.mode(), ignored -> new ArrayList<>()).add(used);
				Map<String, Long> total = roundWork.computeIfAbsent(query.mode(), ignored -> new java.util.TreeMap<>());
				counters.forEach((key, value) -> total.merge(key, value, key.startsWith("peak") ? Math::max : Long::sum));
			}
			if (work != null && !work.equals(roundWork)) {
				throw new IllegalStateException("engine work counters must be deterministic across rounds");
			}
			work = roundWork;
			for (Mode mode : nanos.keySet()) {
				long[] sorted = nanos.get(mode).stream().mapToLong(Long::longValue).sorted().toArray();
				nanosByRound.computeIfAbsent(mode, ignored -> new ArrayList<>())
					.add(new long[] {percentile(sorted, 50), percentile(sorted, 99)});
				bytesByRound.computeIfAbsent(mode, ignored -> new ArrayList<>())
					.add(bytes.get(mode).stream().mapToLong(Long::longValue).sum() / bytes.get(mode).size());
			}
		}

		Map<String, Object> modes = new LinkedHashMap<>();
		for (Mode mode : Mode.values()) {
			if (!work.containsKey(mode)) continue;
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("queries", queries.stream().filter(query -> query.mode() == mode).count());
			value.put("p50Nanos", median(nanosByRound.get(mode).stream().mapToLong(pair -> pair[0]).toArray()));
			// 회차 p99는 질의가 100건 이상이면 두 번째로 큰 값이다. 회차 5개의 중앙값이라 일시적 정지가 한두 회차에만
			// 끼면 결과를 바꾸지 못한다. 모든 회차 표본을 모으면 CI 러너의 1~3% 일시 정지가 p99를 차지했다.
			value.put("p99Nanos", median(nanosByRound.get(mode).stream().mapToLong(pair -> pair[1]).toArray()));
			value.put("samplesPerRound", queries.stream().filter(query -> query.mode() == mode).count());
			value.put("allocatedBytesPerQuery", median(bytesByRound.get(mode).stream().mapToLong(Long::longValue).toArray()));
			value.put("work", work.get(mode));
			modes.put(mode.name(), value);
		}
		Map<String, Object> bundle = new LinkedHashMap<>();
		bundle.put("sha256", RouteTimetableRaptorPlanner.computeTimetableDigest(timetable));
		bundle.put("stations", timetable.transitStopTimes().stream().map(stop -> stop.stationId()).distinct().count());
		bundle.put("trips", timetable.transitTrips().size());
		bundle.put("stopTimes", timetable.transitStopTimes().size());
		bundle.put("transferRules", timetable.routeAccessData().transferRules().size());
		Map<String, Object> querySet = new LinkedHashMap<>();
		querySet.put("id", workload.querySetId());
		querySet.put("count", queries.size());
		querySet.put("sha256", sha256(String.join("\n", queries.stream().map(Query::describe).toList())));
		Map<String, Object> compile = new LinkedHashMap<>();
		compile.put("nanos", median(compileNanos));
		compile.put("allocatedBytes", median(compileBytes));
		compile.put("retainedBytes", retained);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("bundle", bundle);
		result.put("querySet", querySet);
		result.put("compile", compile);
		result.put("modes", modes);
		result.put("candidatePolicyReplay", policyReplay(workload, timetable, runtime, planner, adapter));
		return result;
	}

	/** 운영 후보 정책 한도로 프로필 질의를 한 번씩 돌린 결정적 결과(모드별 합계). */
	private static Map<String, Object> policyReplay(
		Workload workload, RouteTimetable timetable, RaptorRouteBundleRuntimeView runtime,
		RouteTimetableRaptorPlanner planner, JourneyProfileRaptorAdapter adapter
	) {
		Workload replay = new Workload(workload.id(), workload.timetable(), workload.odPair(),
			workload.querySetId() + "-policy-replay", workload.seed() + 7, POLICY_REPLAY_COUNTS, workload.day(),
			workload.firstReadySeconds(), workload.readySpanSeconds());
		Map<String, Map<String, Long>> modes = new java.util.TreeMap<>();
		for (Query query : querySet(replay, timetable)) {
			Map<String, Long> total = modes.computeIfAbsent(query.mode().name(), ignored -> new java.util.TreeMap<>());
			total.merge("queries", 1L, Long::sum);
			run(query, runtime, planner, adapter, CANDIDATE_POLICY_LIMITS)
				.forEach((key, value) -> total.merge(key, value, key.startsWith("peak") ? Math::max : Long::sum));
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("source", CANDIDATE_POLICY_SOURCE);
		result.put("limits", Map.of("maxEstimatedWork", CANDIDATE_POLICY_LIMITS.maxEstimatedWork(),
			"maxLabelsPerState", CANDIDATE_POLICY_LIMITS.maxLabelsPerState(),
			"maxDestinationProfileLabels", CANDIDATE_POLICY_LIMITS.maxDestinationProfileLabels(),
			"maxProfileBreakpoints", CANDIDATE_POLICY_LIMITS.maxProfileBreakpoints()));
		result.put("modes", modes);
		return result;
	}

	private record Query(Mode mode, JourneyRaptorQuery query, String querySetId) {
		String describe() {
			return new JourneyEngineDifferentialHarness.Case(querySetId, 0, 0, mode, query).describe();
		}
	}

	/** 결정적 작업량 카운터. 같은 질의는 항상 같은 값을 낸다. */
	private static Map<String, Long> run(
		Query query, RaptorRouteBundleRuntimeView runtime, RouteTimetableRaptorPlanner planner,
		JourneyProfileRaptorAdapter adapter, JourneyProfileResourcePolicy.ProfilePlanningLimits limits
	) {
		Map<String, Long> counters = new LinkedHashMap<>();
		if (query.mode() == Mode.DEPART_AT) {
			var plan = planner.journeyItineraries(query.query(), runtime.compiledTimetable());
			counters.put("expandedRoutes", (long) plan.scanMetrics().expandedRoutes());
			counters.put("expandedTrips", (long) plan.scanMetrics().expandedTrips());
			counters.put("expandedTransfers", (long) plan.scanMetrics().expandedTransfers());
			counters.put("itineraries", (long) plan.itineraries().size());
			return counters;
		}
		var result = adapter.planRuntime(query.query(), runtime, null, limits);
		var metrics = result.planningMetrics();
		counters.put("workConsumed", metrics.workConsumed());
		counters.put("peakStateLabels", metrics.peakStateLabels());
		counters.put("peakDestinationLabels", metrics.peakDestinationLabels());
		counters.put("profileBreakpoints", metrics.reservedProfileBreakpoints());
		result.countSnapshot().countsByRuleId().forEach((rule, count) -> counters.put("pruning." + rule, count));
		long itineraries = 0;
		long failClosed = 0;
		if (result instanceof JourneyProfileRaptorPort.PlanningResult.Planned planned) {
			itineraries = switch (planned.temporalPlan()) {
				case JourneyProfileRaptorPort.DepartureWindowPlan plan ->
					plan.points().stream().mapToLong(point -> point.itineraries().size()).sum();
				case JourneyProfileRaptorPort.ArriveByPlan plan -> found(plan.result());
				case JourneyProfileRaptorPort.LastConnectionPlan plan -> found(plan.result());
				default -> throw new IllegalStateException("unexpected profile plan");
			};
			counters.put("found", itineraries > 0 ? 1L : 0L);
		} else {
			failClosed = 1;
			counters.put(switch (result) {
				case JourneyProfileRaptorPort.PlanningResult.AdmissionRejected ignored -> "failClosed.MAX_ESTIMATED_WORK";
				case JourneyProfileRaptorPort.PlanningResult.CapacityExceeded capacity -> "failClosed." + capacity.dimension();
				default -> throw new IllegalStateException("unexpected planning result");
			}, 1L);
		}
		counters.put("itineraries", itineraries);
		counters.put("failClosed", failClosed);
		return counters;
	}

	private static long found(JourneyProfileRaptorPort.ReversePlan plan) {
		return plan instanceof JourneyProfileRaptorPort.ReversePlan.Found found ? found.itineraries().size() : 0;
	}

	/** 고정 질의 집합. 보행 프로필·제약 조합 7가지를 돌아가며 쓴다. */
	private static List<Query> querySet(Workload workload, RouteTimetable timetable) {
		Random random = new Random(workload.seed());
		Instant day = workload.day().atStartOfDay(ServiceDayResolver.ZONE).toInstant();
		List<Query> queries = new ArrayList<>();
		int index = 0;
		for (Mode mode : Mode.values()) {
			for (int count = 0; count < workload.counts().getOrDefault(mode, 0); count += 1, index += 1) {
				String[] od = workload.odPair().apply(timetable, random);
				Instant readyAt = day.plusSeconds(workload.firstReadySeconds() + random.nextInt(workload.readySpanSeconds()));
				JourneyRaptorQuery.TemporalQuery temporal = switch (mode) {
					case DEPART_AT -> new JourneyRaptorQuery.DepartAt(readyAt);
					case ARRIVE_BY -> new JourneyRaptorQuery.ArriveBy(readyAt, readyAt.plusSeconds(7_200));
					case DEPART_BETWEEN -> new JourneyRaptorQuery.DepartBetween(readyAt, readyAt.plusSeconds(1_200));
					case LAST_CONNECTION -> new JourneyRaptorQuery.LastConnection(workload.day());
				};
				Mobility mobility = Mobility.ALL.get(index % Mobility.ALL.size());
				int maxTransfers = mode == Mode.DEPART_AT ? 3 : 2;
				queries.add(new Query(mode, new JourneyRaptorQuery(
					JourneyProfileFullCorpusRunner.requestId(workload.querySetId() + "-" + index, od[0], od[1], temporal),
					od[0], od[1], temporal, JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
					JourneyRequest.WalkingPace.STANDARD, mobility.profile(), mobility.constraint(), maxTransfers, 3,
					() -> false), workload.querySetId()));
			}
		}
		return List.copyOf(queries);
	}

	/** 엔진과 무관한 고정 CPU·메모리 작업. 러너 속도 차이를 지우는 정규화 분모다. */
	private static long calibrationWork() {
		long started = System.nanoTime();
		Random random = new Random(46L);
		int[] values = new int[1 << 20];
		for (int index = 0; index < values.length; index += 1) values[index] = random.nextInt();
		Arrays.sort(values);
		Map<Integer, Integer> map = new java.util.HashMap<>();
		for (int index = 0; index < 200_000; index += 1) map.merge(values[index * 5] & 0xffff, 1, Integer::sum);
		if (map.isEmpty()) throw new IllegalStateException("calibration work was optimized away");
		return System.nanoTime() - started;
	}

	/** 컴파일된 런타임 하나가 붙잡는 힙. 명시적 GC 전후 사용량 차이다. */
	private static long retainedBytes(RouteTimetable timetable) {
		MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
		settle(memory);
		long before = memory.getHeapMemoryUsage().getUsed();
		var runtime = RaptorRouteBundleRuntimeView.compile("e".repeat(64), 1, timetable);
		settle(memory);
		long after = memory.getHeapMemoryUsage().getUsed();
		if (runtime.generation() != 1) throw new IllegalStateException("runtime must stay reachable while measured");
		return Math.max(0, after - before);
	}

	private static void settle(MemoryMXBean memory) {
		for (int index = 0; index < 3; index += 1) memory.gc();
	}

	// ---------------------------------------------------------------- 판정

	/**
	 * 허용폭. {@code stablePointP50Factor}는 출발 시각 고정 탐색 p50에만 쓴다. 이 계열은 회차당 질의가 100건 이상이고
	 * 결과가 성공 경로다. #460의 CI 4회에서는 정규화 값이 중앙값 대비 0.7~1.16배였지만, #461의 CI 3회(같은 커밋,
	 * 출발 시각 고정 코드 변경 없음)에서는 0.67~1.52배(원값 district 99~177 µs, 최대/최소 약 1.8배)까지 흔들려
	 * 1.5배로는 무관한 PR이 간헐 실패할 수 있어 2.0배로 둔다. 나머지 지연은 p99 꼬리(CI 러너의 일시 정지), 질의 수가
	 * 적은 거절 비용 계열처럼 편차가 커서 2.5배로 둔다.
	 */
	record Tolerances(double workCounterRelative, double allocatedBytesRegression, double retainedBytesRegression,
		double normalizedLatencyFactor, double normalizedCompileFactor, double stablePointP50Factor) {
		static final Tolerances DEFAULT = new Tolerances(0.02, 0.30, 0.30, 2.5, 2.5, 2.0);

		Map<String, Object> asMap() {
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("workCounterRelative", workCounterRelative);
			value.put("allocatedBytesRegression", allocatedBytesRegression);
			value.put("retainedBytesRegression", retainedBytesRegression);
			value.put("normalizedLatencyFactor", normalizedLatencyFactor);
			value.put("normalizedCompileFactor", normalizedCompileFactor);
			value.put("stablePointP50Factor", stablePointP50Factor);
			return value;
		}

		static Tolerances from(JsonNode node) {
			if (node == null || !node.isObject()) throw new IllegalArgumentException("baseline tolerances are required");
			return new Tolerances(required(node, "workCounterRelative"), required(node, "allocatedBytesRegression"),
				required(node, "retainedBytesRegression"), required(node, "normalizedLatencyFactor"),
				required(node, "normalizedCompileFactor"), required(node, "stablePointP50Factor"));
		}

		private static double required(JsonNode node, String name) {
			if (!node.path(name).isNumber()) throw new IllegalArgumentException("baseline tolerance " + name + " is required");
			return node.path(name).asDouble();
		}
	}

	static List<String> regressions(JsonNode baseline, JsonNode measured) {
		List<String> problems = new ArrayList<>();
		Tolerances tolerances = Tolerances.from(baseline.get("tolerances"));
		double baselineCalibration = baseline.path("calibrationNanos").asDouble();
		double measuredCalibration = measured.path("calibrationNanos").asDouble();
		for (String id : names(baseline.path("workloads"), measured.path("workloads"))) {
			JsonNode before = baseline.path("workloads").path(id);
			JsonNode after = measured.path("workloads").path(id);
			if (before.isMissingNode() || after.isMissingNode()) {
				problems.add("workload " + id + " is missing on one side; regenerate the baseline with the documented procedure");
				continue;
			}
			boolean identityChanged = false;
			for (String identity : List.of("bundle", "querySet")) {
				if (!before.path(identity).path("sha256").equals(after.path(identity).path("sha256"))) {
					problems.add(id + " " + identity + " identity changed; regenerate the baseline with the documented procedure");
					identityChanged = true;
				}
			}
			if (identityChanged) continue;
			compareFactor(problems, id + " compile.nanos", before.path("compile").path("nanos").asDouble() / baselineCalibration,
				after.path("compile").path("nanos").asDouble() / measuredCalibration, tolerances.normalizedCompileFactor());
			compareRegression(problems, id + " compile.retainedBytes", before.path("compile").path("retainedBytes").asDouble(),
				after.path("compile").path("retainedBytes").asDouble(), tolerances.retainedBytesRegression());
			for (String mode : names(before.path("modes"), after.path("modes"))) {
				JsonNode expected = before.path("modes").path(mode);
				JsonNode actual = after.path("modes").path(mode);
				if (expected.isMissingNode() || actual.isMissingNode()) {
					problems.add(id + " mode " + mode + " is missing on one side; regenerate the baseline");
					continue;
				}
				for (String latency : List.of("p50Nanos", "p99Nanos")) {
					double factor = mode.equals(Mode.DEPART_AT.name()) && latency.equals("p50Nanos")
						? tolerances.stablePointP50Factor() : tolerances.normalizedLatencyFactor();
					compareFactor(problems, id + " " + mode + "." + latency, expected.path(latency).asDouble() / baselineCalibration,
						actual.path(latency).asDouble() / measuredCalibration, factor);
				}
				compareRegression(problems, id + " " + mode + ".allocatedBytesPerQuery",
					expected.path("allocatedBytesPerQuery").asDouble(), actual.path("allocatedBytesPerQuery").asDouble(),
					tolerances.allocatedBytesRegression());
				for (String name : names(expected.path("work"), actual.path("work"))) {
					compareWork(problems, id + " " + mode + "." + name, expected.path("work").path(name),
						actual.path("work").path(name), tolerances.workCounterRelative());
				}
			}
			JsonNode replayBefore = before.path("candidatePolicyReplay").path("modes");
			JsonNode replayAfter = after.path("candidatePolicyReplay").path("modes");
			if (!sameNumbers(before.path("candidatePolicyReplay").path("limits"), after.path("candidatePolicyReplay").path("limits"))
				|| !before.path("candidatePolicyReplay").path("source").isTextual()
				|| !before.path("candidatePolicyReplay").path("source").equals(after.path("candidatePolicyReplay").path("source"))) {
				problems.add(id + " candidate policy source or limits changed; regenerate the baseline with the documented procedure");
				continue;
			}
			for (String mode : names(replayBefore, replayAfter)) {
				for (String name : names(replayBefore.path(mode), replayAfter.path(mode))) {
					compareWork(problems, id + " policyReplay " + mode + "." + name, replayBefore.path(mode).path(name),
						replayAfter.path(mode).path(name), tolerances.workCounterRelative());
				}
			}
		}
		return problems;
	}

	private static void compareWork(List<String> problems, String name, JsonNode expected, JsonNode actual, double tolerance) {
		if (!expected.isNumber() || !actual.isNumber()) {
			problems.add("work " + name + " is missing on one side; regenerate the baseline");
			return;
		}
		double before = expected.asDouble();
		double after = actual.asDouble();
		if (after > before * (1 + tolerance)) {
			problems.add("work " + name + " regressed " + (long) before + " -> " + (long) after);
		} else if (after < before * (1 - tolerance)) {
			problems.add("work " + name + " improved " + (long) before + " -> " + (long) after
				+ "; the baseline is stale, regenerate it with the documented procedure");
		}
	}

	private static void compareFactor(List<String> problems, String name, double before, double after, double factor) {
		if (after > before * factor) {
			problems.add(name + " normalized " + String.format(java.util.Locale.ROOT, "%.4f -> %.4f (limit x%.1f)",
				before, after, factor));
		}
	}

	private static void compareRegression(List<String> problems, String name, double before, double after, double ratio) {
		if (after > before * (1 + ratio)) {
			problems.add(name + " regressed " + (long) before + " -> " + (long) after + " (limit +" + Math.round(ratio * 100) + "%)");
		}
	}

	/** 파일에서 읽은 정수와 메모리의 long을 같은 값으로 본다(Jackson 노드 타입 차이 무시). */
	private static boolean sameNumbers(JsonNode left, JsonNode right) {
		if (!left.isObject() || !right.isObject()) return false;
		for (String name : names(left, right)) {
			if (!left.path(name).isNumber() || !right.path(name).isNumber()
				|| left.path(name).asLong() != right.path(name).asLong()) return false;
		}
		return true;
	}

	private static TreeSet<String> names(JsonNode left, JsonNode right) {
		TreeSet<String> names = new TreeSet<>();
		left.fieldNames().forEachRemaining(names::add);
		right.fieldNames().forEachRemaining(names::add);
		return names;
	}

	private static JsonNode tree(Map<String, Object> value) {
		return JSON.valueToTree(value);
	}

	private static Map<String, Object> sample(long trips, long p50, long bytes, long calibration, String bundleSha) {
		Map<String, Object> modes = new LinkedHashMap<>();
		for (Mode mode : Mode.values()) {
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("p50Nanos", mode == Mode.DEPART_AT ? p50 : 2_000_000);
			value.put("p99Nanos", mode == Mode.DEPART_AT ? p50 * 2 : 4_000_000);
			value.put("allocatedBytesPerQuery", mode == Mode.DEPART_AT ? bytes : 1_000_000);
			value.put("work", Map.of("expandedTrips", mode == Mode.DEPART_AT ? trips : 1_000));
			modes.put(mode.name(), value);
		}
		Map<String, Object> workload = new LinkedHashMap<>();
		workload.put("bundle", Map.of("sha256", bundleSha));
		workload.put("querySet", Map.of("sha256", "queries"));
		workload.put("compile", Map.of("nanos", 50_000_000L * calibration / 100_000_000, "retainedBytes", 1_000_000));
		workload.put("modes", modes);
		workload.put("candidatePolicyReplay", Map.of("source", CANDIDATE_POLICY_SOURCE,
			"limits", Map.of("maxEstimatedWork", 1_000, "maxLabelsPerState", 8, "maxDestinationProfileLabels", 16,
				"maxProfileBreakpoints", 32),
			"modes", Map.of("ARRIVE_BY", Map.of("queries", 12, "failClosed", 12L))));
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("calibrationNanos", calibration);
		value.put("workloads", Map.of("sample-grid", workload));
		value.put("tolerances", Tolerances.DEFAULT.asMap());
		return value;
	}

	// ---------------------------------------------------------------- 통계

	private static long[] repeat(int count, java.util.function.LongSupplier supplier) {
		long[] values = new long[count];
		for (int index = 0; index < count; index += 1) values[index] = supplier.getAsLong();
		return values;
	}

	static long median(long[] values) {
		long[] sorted = values.clone();
		Arrays.sort(sorted);
		return sorted[(sorted.length - 1) / 2];
	}

	/** 최근순위(nearest-rank) 백분위. 표본이 100개보다 적으면 p99는 최댓값이다. */
	static long percentile(long[] sorted, int percent) {
		int rank = (int) Math.ceil(percent / 100.0 * sorted.length);
		return sorted[Math.max(0, rank - 1)];
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
