package com.easysubway.journey.adapter.in.web;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.easysubway.journey.analytics.InMemoryJourneySearchRecordStore;
import com.easysubway.journey.analytics.JourneySearchLatencyMetrics;
import com.easysubway.journey.analytics.JourneySearchRecorder;
import com.easysubway.journey.application.ActiveJourneySnapshotPort;
import com.easysubway.journey.application.FacilityAvailabilityPort;
import com.easysubway.journey.application.JourneyApplicationDeadlineExecutor;
import com.easysubway.journey.application.JourneyApplicationService;
import com.easysubway.journey.application.JourneyProfileApplicationService;
import com.easysubway.journey.application.JourneyProfileDeadlineExecutor;
import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyRealtimePort;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.JourneySessionService;
import com.easysubway.journey.application.ServiceDayResolver;
import com.easysubway.route.application.service.CapitalEndpointFixture;
import com.easysubway.route.application.service.JourneyProfileRaptorAdapter;
import com.easysubway.route.application.service.JourneyRaptorAdapter;
import com.easysubway.route.application.service.RaptorRouteBundleRuntimeView;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.ScanWorkspacePool;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * #494: 실제 HTTP 엔드포인트의 모드별 지연 측정 구동기. 테스트가 아니라 손으로 돌린다.
 *
 * <p>내장 Tomcat 위에 운영 컨트롤러({@code JourneySearchController}·{@code JourneyProfileController})와 예외 처리기,
 * 운영 엔진(점·프로필 RAPTOR 어댑터, 작업 공간 풀 16개, 가상 스레드 실행기)을 수도권 실데이터 fixture로 묶고 루프백 HTTP로
 * 부른다. 정책 값은 platform 후보 정책 RAPTOR_RESOURCE_POLICY_V1 1.1.0(마감 2/5/8초)이다. 세션 검증만 고정 응답 목이다.
 * 클라이언트가 잰 왕복 지연(직렬화 포함)과 {@link JourneySearchLatencyMetrics}와 같은 구간의 서버 처리 지연(정확한 나노초 값)을
 * 모드·결과별로 함께 찍고, 마지막에 Prometheus 출력의 히스토그램 일부를 보인다.</p>
 *
 * <p>실행: 테스트 런타임 classpath로 {@code java -Xms1g -Xmx1g -cp <test runtime classpath>
 * com.easysubway.journey.adapter.in.web.JourneyEndpointLatencyMeasurement [rounds] [concurrency]}.</p>
 */
public final class JourneyEndpointLatencyMeasurement {

	private static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
	private static final String SHA = "d".repeat(64);
	private static final Instant NOW = CapitalEndpointFixture.serviceDate().atStartOfDay(ServiceDayResolver.ZONE).toInstant();
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final int POINT_QUERIES = 300;
	private static final int RANDOM_PROFILE_QUERIES_PER_MODE = 40;

	private JourneyEndpointLatencyMeasurement() {
	}

	/** 운영 지표를 그대로 쓰되, 측정 구간에서는 같은 구간의 정확한 나노초 값을 모드·결과별로 따로 모은다. */
	private static final class ExactLatencyMetrics extends JourneySearchLatencyMetrics {
		ExactLatencyMetrics() {
			super(Wiring.METERS);
		}

		@Override
		public void recordSuccess(com.easysubway.journey.analytics.JourneySearchKind kind, long startedNanos) {
			capture(kind, "ok", startedNanos);
			super.recordSuccess(kind, startedNanos);
		}

		@Override
		public void recordFailure(com.easysubway.journey.analytics.JourneySearchKind kind, long startedNanos,
			int httpStatus, String machineCode) {
			capture(kind, httpStatus + "/" + machineCode, startedNanos);
			super.recordFailure(kind, startedNanos, httpStatus, machineCode);
		}

		private static void capture(com.easysubway.journey.analytics.JourneySearchKind kind, String outcome, long startedNanos) {
			if (!measuring) return;
			exact.computeIfAbsent(kind.name().toLowerCase(Locale.ROOT) + " " + outcome,
				ignored -> java.util.Collections.synchronizedList(new ArrayList<>())).add(System.nanoTime() - startedNanos);
		}
	}

	private static volatile boolean measuring;
	private static final Map<String, List<Long>> exact = new java.util.concurrent.ConcurrentHashMap<>();

	private record Request(String mode, String body, String path) {
	}

	private record Sample(String mode, int status, long nanos) {
	}

	// @Configuration을 붙이지 않는다. 붙이면 애플리케이션 컨텍스트 테스트의 컴포넌트 스캔이 이 클래스를 읽어 빈이 섞인다.
	@Import({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
		WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class})
	static class Wiring {
		static final PrometheusMeterRegistry METERS = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

		@Bean
		JourneySessionService sessions() {
			var sessions = mock(JourneySessionService.class);
			when(sessions.authorize(anyString(), anyInt())).thenReturn(
				new JourneySessionService.AuthorizedSession("journey:v3", NOW.plusSeconds(3_600)));
			return sessions;
		}

		@Bean
		JourneyProfileResourcePolicy policy() {
			return new JourneyProfileResourcePolicy(
				new JourneyProfileResourcePolicy.Identity("RAPTOR_RESOURCE_POLICY_V1", "1.1.0", "a".repeat(64)),
				Duration.ofSeconds(3_600), 2, 10_000_000L, 2_048, 128, 128, Duration.ofSeconds(3_600),
				Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(8), 1, 2, 3, 4, 10);
		}

		@Bean
		JourneySearchRecorder recorder() {
			return new JourneySearchRecorder(new InMemoryJourneySearchRecordStore(), Executors.newSingleThreadExecutor(),
				CLOCK, METERS);
		}

		@Bean
		JourneySearchLatencyMetrics latency() {
			return new ExactLatencyMetrics();
		}

		@Bean
		JourneySearchController searchController(
			JourneySessionService sessions, JourneyProfileResourcePolicy policy, JourneySearchRecorder recorder,
			JourneySearchLatencyMetrics latency
		) {
			var snapshot = snapshot();
			ActiveJourneySnapshotPort snapshotPort = (request, instant, measurement) -> snapshot;
			JourneyRealtimePort realtime = (request, ignored, instant) -> {
				throw new IllegalStateException("timetable-only measurement");
			};
			var service = new JourneyApplicationService(snapshotPort, realtime,
				new JourneyRaptorAdapter(new ScanWorkspacePool(16), FacilityAvailabilityPort.unavailable(), false, CLOCK), CLOCK);
			var workers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("journey-search-", 0).factory());
			var executor = new JourneyApplicationDeadlineExecutor(service, workers, workers, policy.pointSearchDeadline());
			return new JourneySearchController(sessions, executor, policy, recorder, latency);
		}

		@Bean
		JourneyProfileController profileController(
			JourneySessionService sessions, JourneyProfileResourcePolicy policy, JourneySearchRecorder recorder,
			JourneySearchLatencyMetrics latency
		) {
			var snapshot = snapshot();
			var service = new JourneyProfileApplicationService((query, reference, measurement) -> snapshot,
				new JourneyProfileRaptorAdapter(new ScanWorkspacePool(16), FacilityAvailabilityPort.unavailable(), false, CLOCK),
				CLOCK);
			var workers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("journey-profile-", 0).factory());
			return new JourneyProfileController(sessions, new JourneyProfileDeadlineExecutor(service, workers), policy,
				JourneyProfileController.DEFAULT_MAX_REQUEST_BYTES, recorder, latency);
		}

		@Bean
		JourneySearchExceptionHandler exceptionHandler() {
			return new JourneySearchExceptionHandler();
		}

		private static ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot() {
			var runtime = RaptorRouteBundleRuntimeView.compile(SHA, 1, CapitalEndpointFixture.timetable());
			return new ActiveJourneySnapshotPort.ActiveJourneySnapshot(
				"snapshot", "bundle", SHA, "timetable", "accessibility", 1, runtime, NOW.plus(Duration.ofDays(30)), true,
				ActiveJourneySnapshotPort.ActiveServingEvidence.unobservable(),
				ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.observed(0, 0));
		}
	}

	public static void main(String[] args) throws Exception {
		int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 5;
		int concurrency = args.length > 1 ? Integer.parseInt(args[1]) : 1;
		System.setProperty("server.port", "0");
		var context = new AnnotationConfigServletWebServerApplicationContext(Wiring.class);
		try {
			int port = ((org.springframework.boot.web.server.WebServer) context.getWebServer()).getPort();
			var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
			List<Request> requests = requests();
			for (int warmup = 0; warmup < 3; warmup += 1) for (Request request : requests) send(client, port, request);
			measuring = true;
			List<Sample> samples = new ArrayList<>();
			for (int round = 0; round < rounds; round += 1) {
				if (concurrency == 1) {
					for (Request request : requests) samples.add(send(client, port, request));
				} else {
					samples.addAll(runConcurrent(client, port, requests, concurrency));
				}
			}
			report(samples, concurrency);
		} finally {
			context.close();
		}
		// 기록기 실행기 스레드가 비데몬이라 JVM이 스스로 끝나지 않는다.
		System.exit(0);
	}

	private static List<Sample> runConcurrent(HttpClient client, int port, List<Request> requests, int concurrency)
		throws Exception {
		var pool = Executors.newFixedThreadPool(concurrency);
		try {
			List<Future<Sample>> futures = new ArrayList<>();
			for (Request request : requests) futures.add(pool.submit(() -> send(client, port, request)));
			List<Sample> samples = new ArrayList<>();
			for (Future<Sample> future : futures) samples.add(future.get());
			return samples;
		} finally {
			pool.shutdown();
			pool.awaitTermination(1, TimeUnit.MINUTES);
		}
	}

	private static Sample send(HttpClient client, int port, Request request) throws Exception {
		var http = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + request.path()))
			.header("Authorization", "Bearer measurement-session").header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8)).build();
		long started = System.nanoTime();
		var response = client.send(http, HttpResponse.BodyHandlers.ofByteArray());
		long elapsed = System.nanoTime() - started;
		return new Sample(request.mode(), response.statusCode(), elapsed);
	}

	private static List<Request> requests() {
		var random = new Random(4_940_494L);
		List<String> stations = CapitalEndpointFixture.stations();
		var day = CapitalEndpointFixture.serviceDate();
		List<Request> requests = new ArrayList<>();
		// 점 탐색(DEPART_AT): 시드 고정 무작위 OD, 서비스일 06:30~22:00 사이 출발.
		var mobilities = List.of(
			new String[] {"STANDARD", "NONE"}, new String[] {"STEP_FREE", "NONE"}, new String[] {"STEP_FREE", "REQUIRE_STEP_FREE"});
		for (int index = 0; index < POINT_QUERIES; index += 1) {
			String[] od = pair(stations, random);
			Instant at = day.atStartOfDay(ServiceDayResolver.ZONE).toInstant().plusSeconds(23_400 + random.nextInt(55_800));
			String[] mobility = mobilities.get(index % mobilities.size());
			requests.add(new Request("depart_at", """
				{"requestId":"%s","originStationId":"%s","destinationStationId":"%s",
				"departure":{"mode":"SCHEDULED","requestedAt":"%s"},"timePolicy":"TIMETABLE_REQUIRED","walkingPace":"STANDARD",
				"mobilityProfile":"%s","constraintMode":"%s","maxTransfers":3,"alternativeCount":3}
				""".formatted(ulid(random), od[0], od[1], at, mobility[0], mobility[1]), "/api/v3/journeys/search"));
		}
		// 프로필 3종: 모바일 대표 질의 14건 + 모드별 무작위 OD.
		for (var testCase : CapitalEndpointFixture.representativeProfileCases()) {
			requests.add(profile(random, testCase.mode(), testCase.origin(), testCase.destination(),
				testCase.localTime() == null ? null : LocalTime.parse(testCase.localTime()), testCase.profile().name(),
				testCase.constraint().name()));
		}
		for (String mode : List.of("DEPART_BETWEEN", "ARRIVE_BY", "LAST_CONNECTION")) {
			for (int index = 0; index < RANDOM_PROFILE_QUERIES_PER_MODE; index += 1) {
				String[] od = pair(stations, random);
				String[] mobility = mobilities.get(index % mobilities.size());
				requests.add(profile(random, mode, od[0], od[1], LocalTime.of(6, 30).plusMinutes(random.nextInt(900)),
					mobility[0], mobility[1]));
			}
		}
		return requests;
	}

	private static Request profile(Random random, String mode, String origin, String destination, LocalTime localTime,
		String mobility, String constraint) {
		var day = CapitalEndpointFixture.serviceDate();
		String temporal = switch (mode) {
			case "DEPART_BETWEEN" -> {
				Instant start = day.atTime(localTime).atZone(ServiceDayResolver.ZONE).toInstant();
				yield "{\"kind\":\"DEPART_BETWEEN\",\"earliestReadyAt\":\"%s\",\"latestReadyAt\":\"%s\"}"
					.formatted(start, start.plusSeconds(1_800));
			}
			case "ARRIVE_BY" -> {
				Instant start = day.atTime(localTime).atZone(ServiceDayResolver.ZONE).toInstant();
				yield "{\"kind\":\"ARRIVE_BY\",\"earliestReadyAt\":\"%s\",\"arrivalDeadline\":\"%s\"}"
					.formatted(start, start.plusSeconds(3_600));
			}
			default -> "{\"kind\":\"LAST_CONNECTION\",\"serviceDate\":\"%s\"}".formatted(day);
		};
		return new Request(mode.toLowerCase(Locale.ROOT), """
			{"requestId":"%s","originStationId":"%s","destinationStationId":"%s","temporalQuery":%s,
			"timePolicy":"TIMETABLE_REQUIRED","walkingPace":"STANDARD","mobilityProfile":"%s","constraintMode":"%s",
			"maxTransfers":3,"alternativeCount":3}
			""".formatted(ulid(random), origin, destination, temporal, mobility, constraint), "/api/v3/journeys/profile");
	}

	private static String[] pair(List<String> stations, Random random) {
		String origin = stations.get(random.nextInt(stations.size()));
		String destination;
		do destination = stations.get(random.nextInt(stations.size())); while (destination.equals(origin));
		return new String[] {origin, destination};
	}

	private static String ulid(Random random) {
		char[] id = new char[26];
		id[0] = '0';
		for (int index = 1; index < id.length; index += 1) id[index] = CROCKFORD.charAt(random.nextInt(32));
		return new String(id);
	}

	private static void report(List<Sample> samples, int concurrency) {
		System.out.printf(Locale.ROOT, "%nconcurrency=%d  samples=%d  jvm=%s%n", concurrency, samples.size(),
			System.getProperty("java.version"));
		System.out.println("== 클라이언트 왕복(직렬화·루프백 포함), 모드 x HTTP 상태");
		System.out.println("mode              status      n    p50(ms)   p95(ms)   p99(ms)   max(ms)");
		Map<String, List<Long>> byKey = new TreeMap<>();
		for (Sample sample : samples) byKey.computeIfAbsent(sample.mode() + "|" + sample.status(), ignored -> new ArrayList<>()).add(sample.nanos());
		Map<String, List<Long>> byMode = new TreeMap<>();
		for (Sample sample : samples) {
			if (sample.status() == 200 || sample.status() == 422) {
				byMode.computeIfAbsent(sample.mode(), ignored -> new ArrayList<>()).add(sample.nanos());
			}
		}
		byKey.forEach((key, values) -> printRow(key.replace('|', ' '), values));
		System.out.println("-- SLI 대상(200+422, 모드별 합산)");
		byMode.forEach(JourneyEndpointLatencyMeasurement::printRow);
		System.out.println("== 서버 처리 지연(JourneySearchLatencyMetrics 측정 구간, 정확한 나노초 값), 모드 x 결과");
		System.out.println("mode outcome                    n    p50(ms)   p95(ms)   p99(ms)   max(ms)");
		new TreeMap<>(exact).forEach((key, values) -> printRow(key, values));
		Map<String, List<Long>> serverByMode = new TreeMap<>();
		exact.forEach((key, values) -> {
			if (key.endsWith(" ok") || key.endsWith("/ROUTE_NOT_FOUND") || key.contains("NO_") || key.contains("ACCESSIBILITY_CONSTRAINT")) {
				serverByMode.computeIfAbsent(key.substring(0, key.indexOf(' ')), ignored -> new ArrayList<>()).addAll(values);
			}
		});
		System.out.println("-- SLI 대상(ok + no_route, 모드별 합산)");
		serverByMode.forEach(JourneyEndpointLatencyMeasurement::printRow);
		System.out.println("== Prometheus 출력(일부)");
		Arrays.stream(Wiring.METERS.scrape().split("\n"))
			.filter(line -> line.startsWith("easysubway_journey_search_duration_seconds_bucket{mode=\"depart_at\",outcome=\"ok\""))
			.forEach(System.out::println);
	}

	private static void printRow(String label, List<Long> nanos) {
		long[] sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
		System.out.printf(Locale.ROOT, "%-24s %6d %9.2f %9.2f %9.2f %9.2f%n", label, sorted.length,
			percentile(sorted, 50) / 1e6, percentile(sorted, 95) / 1e6, percentile(sorted, 99) / 1e6, sorted[sorted.length - 1] / 1e6);
	}

	private static long percentile(long[] sorted, int percent) {
		int rank = (int) Math.ceil(percent / 100.0 * sorted.length);
		return sorted[Math.max(0, rank - 1)];
	}
}
