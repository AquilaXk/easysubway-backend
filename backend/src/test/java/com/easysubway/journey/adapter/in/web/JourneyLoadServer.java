package com.easysubway.journey.adapter.in.web;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.easysubway.journey.analytics.InMemoryJourneySearchRecordStore;
import com.easysubway.journey.analytics.JourneySearchLatencyMetrics;
import com.easysubway.journey.analytics.JourneySearchRecorder;
import com.easysubway.journey.application.FacilityAvailabilityPort;
import com.easysubway.journey.application.FacilityAvailabilityView;
import com.easysubway.journey.application.JourneyApplicationDeadlineExecutor;
import com.easysubway.journey.application.JourneyApplicationService;
import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyRealtimePort;
import com.easysubway.journey.application.JourneySessionService;
import com.easysubway.journey.application.ServiceDayResolver;
import com.easysubway.journey.bundle.LoadScenarioBundles;
import com.easysubway.journey.bundle.RouteBundleActivationRegistry;
import com.easysubway.journey.bundle.RouteBundleActiveJourneySnapshotAdapter;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitFrequency;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.service.CapitalEndpointFixture;
import com.easysubway.route.application.service.JourneyRaptorAdapter;
import com.easysubway.route.application.service.RaptorRouteBundleRuntimeView;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.ScanWorkspacePool;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * 경로 API 부하·장애 시나리오(k6)가 때리는 로컬 서버. 운영 서버가 아니라 CI에서 띄운 인스턴스가 대상이다.
 *
 * <p>내장 Tomcat 위에 운영 컨트롤러({@code JourneySearchController}), 예외 처리기, 운영 엔진(점 탐색 RAPTOR 어댑터, 작업 공간 풀
 * 16개, 가상 스레드 실행기)을 수도권 실데이터 fixture로 묶는다({@code JourneyEndpointLatencyMeasurement}와 같은 구성). 활성 팩은
 * 운영과 같은 {@link RouteBundleActivationRegistry}가 쥐고, 세션 검증(Play Integrity)만 고정 응답 목이다.</p>
 *
 * <p>시나리오가 장애를 주입하는 제어 면은 두 가지다. 둘 다 운영 코드에는 없고 이 서버에만 있다.</p>
 * <ul>
 *   <li>{@code POST /__load/bundle/{a|b}}: 해당 팩을 컴파일해 stage하고 activate한다. 팩 {@code a}는 fixture 그대로,
 *       {@code b}는 모든 정차 시각을 {@value #REPLACEMENT_SHIFT_SECONDS}초 늦춘 같은 노선망이다. 컴파일은 요청 경로 밖에서 하고,
 *       요청은 activate 순간까지 이전 팩으로 응답한다.</li>
 *   <li>{@code POST /__load/facility/{up|down}}: 시설 가동 원천을 정상(차단 0건, 방금 관측)이나 사용 불가로 바꾼다. 시설 상태 필수
 *       모드({@code easysubway.journey.facility-status.required=true})를 켠 구성이므로, 사용 불가이면 계단 없는 필수 요청은
 *       {@code FACILITY_STATUS_UNAVAILABLE} 503이 되고 나머지 요청은 {@code facilityStatus=UNOBSERVED}를 밝힌다. 운영 기본값은
 *       필수 모드 꺼짐이라 그 구성은 이 서버가 검증하지 않는다.</li>
 * </ul>
 * <p>{@code GET /__load/fixture}는 시나리오가 질의를 만드는 입력(역 목록, 서비스일, 팩 식별자)을 알려 준다.</p>
 *
 * <p>실행: {@code main}은 {@code --port-file <절대 경로>}에 포트를 적고(임시 파일에 쓴 뒤 옮겨, 읽는 쪽은 완성된 값만 본다)
 * 종료 신호까지 서비스한다.</p>
 */
public final class JourneyLoadServer implements AutoCloseable {

	static final int REPLACEMENT_SHIFT_SECONDS = 60;
	private static final Instant NOW = CapitalEndpointFixture.serviceDate().atStartOfDay(ServiceDayResolver.ZONE).toInstant();
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final String FIRST_BUNDLE = "a";

	private final AnnotationConfigServletWebServerApplicationContext context;
	private final int port;

	private JourneyLoadServer(AnnotationConfigServletWebServerApplicationContext context) {
		this.context = context;
		this.port = ((WebServer) context.getWebServer()).getPort();
	}

	/** 빈 포트에 서버를 띄운다. 포트는 이 컨텍스트의 설정으로만 정해 같은 JVM의 다른 테스트에 영향을 주지 않는다. */
	public static JourneyLoadServer start() {
		var context = new AnnotationConfigServletWebServerApplicationContext();
		context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("journey-load-server", Map.of("server.port", "0")));
		context.register(Wiring.class);
		context.refresh();
		return new JourneyLoadServer(context);
	}

	public int port() {
		return port;
	}

	@Override
	public void close() {
		context.close();
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 2 || !"--port-file".equals(args[0]) || !Path.of(args[1]).isAbsolute()) {
			throw new IllegalArgumentException("usage: --port-file <absolute path>");
		}
		Path portFile = Path.of(args[1]);
		var server = start();
		Runtime.getRuntime().addShutdownHook(new Thread(server::close));
		writeAtomically(portFile, Integer.toString(server.port()));
		Thread.currentThread().join();
	}

	private static void writeAtomically(Path target, String content) throws IOException {
		Path pending = target.resolveSibling(target.getFileName() + ".pending");
		Files.writeString(pending, content, StandardCharsets.UTF_8);
		Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	/** 다음 팩 시간표: 같은 노선망의 모든 정차·배차 시각을 늦춘다. 서비스일 한도를 넘으면 레코드 검증이 거부한다. */
	private static RouteTimetable replacementTimetable(RouteTimetable base) {
		List<TransitStopTime> stopTimes = base.transitStopTimes().stream()
			.map(stop -> new TransitStopTime(stop.tripId(), stop.stopSequence(), stop.stationId(), stop.lineId(),
				stop.arrivalSeconds() + REPLACEMENT_SHIFT_SECONDS, stop.departureSeconds() + REPLACEMENT_SHIFT_SECONDS,
				stop.pickupType(), stop.dropOffType()))
			.toList();
		List<TransitFrequency> frequencies = base.transitFrequencies().stream()
			.map(frequency -> new TransitFrequency(frequency.tripId(), frequency.startTimeSeconds() + REPLACEMENT_SHIFT_SECONDS,
				frequency.endTimeSeconds() + REPLACEMENT_SHIFT_SECONDS, frequency.headwaySeconds(), frequency.exactTimes()))
			.toList();
		return new RouteTimetable(base.serviceCalendars(), base.serviceCalendarDates(), base.transitRoutes(),
			base.transitTrips(), stopTimes, frequencies, base.officialFares(), base.feedEndDate(), base.routeAccessData());
	}

	/** 시설 가동 원천: 정상이면 차단 없이 방금 관측한 뷰를, 멈추면 운영 기본 원천과 같은 사용 불가 뷰를 돌려준다. */
	private static final class SwitchableFacilityAvailability implements FacilityAvailabilityPort {
		private final AtomicBoolean down = new AtomicBoolean();

		@Override
		public FacilityAvailabilityView currentView() {
			return down.get() ? FacilityAvailabilityView.unavailable() : FacilityAvailabilityView.empty(CLOCK.instant());
		}
	}

	/** 한 번에 하나씩 팩을 바꾼다. 컴파일은 요청 경로 밖(이 호출 스레드)에서 하고 activate만 원자적으로 바뀐다. */
	private static final class BundleReplacement {
		private final RouteBundleActivationRegistry registry = new RouteBundleActivationRegistry(CLOCK);
		private final Map<String, RouteTimetable> timetables;
		private String activeLabel;

		BundleReplacement() {
			RouteTimetable first = CapitalEndpointFixture.timetable();
			this.timetables = Map.of("a", first, "b", replacementTimetable(first));
			activate(FIRST_BUNDLE);
		}

		/** 이미 활성인 팩을 다시 요청하면 레지스트리가 거부하므로(CANDIDATE_ALREADY_ACTIVE) 아무것도 하지 않는다. */
		synchronized void activate(String label) {
			if (label.equals(activeLabel)) return;
			long expectedGeneration = currentGeneration();
			long generation = expectedGeneration + 1;
			String manifestSha256 = LoadScenarioBundles.manifestSha256(label);
			var runtime = RaptorRouteBundleRuntimeView.compile(manifestSha256, generation, timetables.get(label));
			registry.stage(LoadScenarioBundles.candidate(label, runtime, NOW), expectedGeneration);
			registry.activate(manifestSha256, expectedGeneration);
			activeLabel = label;
		}

		private long currentGeneration() {
			try {
				return registry.activeSnapshot().generation();
			} catch (RuntimeException noActiveBundleYet) {
				return 0;
			}
		}

		boolean knows(String label) {
			return timetables.containsKey(label);
		}

		RouteBundleActivationRegistry registry() {
			return registry;
		}
	}

	// @Configuration을 붙이지 않는다. 붙이면 애플리케이션 컨텍스트 테스트의 컴포넌트 스캔이 이 클래스를 읽어 빈이 섞인다.
	@Import({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
		WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class})
	static class Wiring {

		@Bean
		BundleReplacement bundleReplacement() {
			return new BundleReplacement();
		}

		@Bean
		SwitchableFacilityAvailability facilityAvailability() {
			return new SwitchableFacilityAvailability();
		}

		/**
		 * 제어 면. 컨트롤러 애너테이션 대신 함수형 라우터를 쓴다. 컨트롤러 스테레오타입을 붙이면 애플리케이션 컨텍스트 테스트의
		 * 컴포넌트 스캔이 이 클래스를 읽어 빈이 섞이고, 애너테이션 없는 클래스는 Spring 6에서 핸들러로 인식되지 않는다.
		 */
		@Bean
		RouterFunction<ServerResponse> loadControl(BundleReplacement bundles, SwitchableFacilityAvailability facility) {
			return RouterFunctions.route()
				.POST("/__load/bundle/{label}", request -> {
					String label = request.pathVariable("label");
					if (!bundles.knows(label)) return ServerResponse.badRequest().build();
					bundles.activate(label);
					return ServerResponse.ok().body(Map.of("active", label));
				})
				.POST("/__load/facility/{state}", request -> {
					String state = request.pathVariable("state");
					if (!"up".equals(state) && !"down".equals(state)) return ServerResponse.badRequest().build();
					facility.down.set("down".equals(state));
					return ServerResponse.ok().body(Map.of("facility", state));
				})
				.GET("/__load/fixture", request -> ServerResponse.ok().body(Map.of(
					"serviceDate", CapitalEndpointFixture.serviceDate().toString(),
					"stations", CapitalEndpointFixture.stations(),
					"bundles", Map.of("a", LoadScenarioBundles.manifestSha256("a"), "b", LoadScenarioBundles.manifestSha256("b")),
					"replacementShiftSeconds", REPLACEMENT_SHIFT_SECONDS)))
				.build();
		}

		@Bean
		JourneySessionService sessions() {
			var sessions = mock(JourneySessionService.class);
			when(sessions.authorize(anyString(), anyInt())).thenReturn(
				new JourneySessionService.AuthorizedSession("journey:v3", NOW.plusSeconds(3_600)));
			return sessions;
		}

		/** 정책 값은 platform 후보 정책 RAPTOR_RESOURCE_POLICY_V1 1.1.0(점 탐색 마감 2초)이다. */
		@Bean
		JourneyProfileResourcePolicy policy() {
			return new JourneyProfileResourcePolicy(
				new JourneyProfileResourcePolicy.Identity("RAPTOR_RESOURCE_POLICY_V1", "1.1.0", "a".repeat(64)),
				Duration.ofSeconds(3_600), 2, 10_000_000L, 2_048, 128, 128, Duration.ofSeconds(3_600),
				Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(8), 1, 2, 3, 4, 10);
		}

		@Bean
		JourneySearchRecorder recorder(SimpleMeterRegistry meters) {
			var recorderThread = Executors.newSingleThreadExecutor(task -> {
				var thread = new Thread(task, "journey-search-recorder");
				thread.setDaemon(true);
				return thread;
			});
			return new JourneySearchRecorder(new InMemoryJourneySearchRecordStore(), recorderThread, CLOCK, meters);
		}

		@Bean
		SimpleMeterRegistry meters() {
			return new SimpleMeterRegistry();
		}

		@Bean
		JourneySearchLatencyMetrics latency(SimpleMeterRegistry meters) {
			return new JourneySearchLatencyMetrics(meters);
		}

		@Bean
		JourneySearchController searchController(
			JourneySessionService sessions, JourneyProfileResourcePolicy policy, JourneySearchRecorder recorder,
			JourneySearchLatencyMetrics latency, BundleReplacement bundles, SwitchableFacilityAvailability facility
		) {
			var snapshots = new RouteBundleActiveJourneySnapshotAdapter(bundles.registry());
			JourneyRealtimePort timetableOnly = (request, snapshot, instant) -> {
				throw new IllegalStateException("timetable-only load scenario");
			};
			var raptor = new JourneyRaptorAdapter(new ScanWorkspacePool(16), facility, true, CLOCK);
			var service = new JourneyApplicationService(snapshots, timetableOnly, raptor, CLOCK);
			var workers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("journey-search-", 0).factory());
			var executor = new JourneyApplicationDeadlineExecutor(service, workers, workers, policy.pointSearchDeadline());
			return new JourneySearchController(sessions, executor, policy, recorder, latency);
		}

		@Bean
		JourneySearchExceptionHandler exceptionHandler() {
			return new JourneySearchExceptionHandler();
		}
	}
}
