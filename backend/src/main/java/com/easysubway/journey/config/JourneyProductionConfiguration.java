package com.easysubway.journey.config;

import com.easysubway.journey.application.ActiveJourneySnapshotPort;
import com.easysubway.journey.application.JourneyApplicationDeadlineExecutor;
import com.easysubway.journey.application.JourneyApplicationService;
import com.easysubway.journey.application.JourneyProfileApplicationService;
import com.easysubway.journey.application.JourneyProfileDeadlineExecutor;
import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyProfileSnapshotPort;
import com.easysubway.journey.application.JourneyProfileRaptorPort;
import com.easysubway.journey.application.JourneyRaptorPort;
import com.easysubway.journey.application.JourneyRealtimePort;
import com.easysubway.journey.application.JourneySessionIntegrityPort;
import com.easysubway.journey.application.JourneySessionService;
import com.easysubway.journey.application.JourneySessionStore;
import com.easysubway.journey.application.StationTimetableSearchService;
import com.easysubway.journey.activation.JourneyActivationCommandParser;
import com.easysubway.journey.activation.JourneyActivationService;
import com.easysubway.journey.adapter.in.web.JourneyActivationController;
import com.easysubway.journey.adapter.in.web.JourneyBenchmarkObservationController;
import com.easysubway.journey.adapter.in.web.JourneyCandidateCanaryController;
import com.easysubway.journey.adapter.in.web.JourneyReadinessController;
import com.easysubway.journey.adapter.in.web.JourneyReadinessServiceTokenFilter;
import com.easysubway.journey.bundle.RouteBundleActivationRegistry;
import com.easysubway.journey.bundle.RouteBundleActiveJourneySnapshotAdapter;
import com.easysubway.journey.canary.JourneyCandidateCanaryCommandParser;
import com.easysubway.journey.canary.JourneyCandidateCanaryService;
import com.easysubway.journey.readiness.JourneyReadinessProperties;
import com.easysubway.journey.readiness.JourneyReadinessService;
import com.easysubway.route.application.service.JourneyRaptorAdapter;
import com.easysubway.route.application.service.JourneyProfileRaptorAdapter;
import com.easysubway.route.application.service.JourneyRealtimeAdapter;
import com.easysubway.route.application.service.JourneyTimetableRealtimeResolver;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import java.security.SecureRandom;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
@Profile("(prod | staging | release | prod-like) & !capacity-evidence")
@EnableConfigurationProperties(JourneyReadinessProperties.class)
public class JourneyProductionConfiguration {

	private static final String SESSION_PATH = "/api/v3/journeys/session";
	private static final String SEARCH_PATH = "/api/v3/journeys/search";
	private static final String PROFILE_PATH = "/api/v3/journeys/profile";
	private static final String STATION_TIMETABLE_PATH = "/api/v3/station-timetables/search";
	private static final Clock CLOCK = Clock.systemUTC();
	private static final Duration REALTIME_FRESHNESS_TTL = Duration.ofSeconds(90);

	@Bean
	JourneyReadinessService journeyReadinessService(
		RouteBundleActivationRegistry registry,
		JourneyReadinessProperties properties,
		ApplicationAvailability applicationAvailability) {
		return new JourneyReadinessService(registry, properties, applicationAvailability);
	}

	@Bean
	JourneyActivationCommandParser journeyActivationCommandParser() {
		return new JourneyActivationCommandParser();
	}

	@Bean
	JourneyActivationService journeyActivationService(
		RouteBundleActivationRegistry registry,
		JourneyReadinessProperties properties,
		JourneyReadinessService readinessService) {
		return new JourneyActivationService(registry, properties, readinessService);
	}

	@Bean
	@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
	JourneyCandidateCanaryCommandParser journeyCandidateCanaryCommandParser() {
		return new JourneyCandidateCanaryCommandParser();
	}

	@Bean
	@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
	JourneyCandidateCanaryService journeyCandidateCanaryService(
		RouteBundleActivationRegistry registry,
		JourneyRaptorPort raptorPort) {
		return new JourneyCandidateCanaryService(registry, raptorPort, CLOCK);
	}

	@Bean
	JourneySessionService journeySessionService(
		JourneySessionIntegrityPort integrityPort,
		JourneySessionStore sessionStore,
		JourneyProfileResourcePolicy resourcePolicy,
		@Value("${easysubway.journey.session.certificate-sha256}") String certificateSha256
	) {
		return new JourneySessionService(
			integrityPort,
			sessionStore,
			CLOCK,
			new SecureRandom(),
			certificateSha256,
			resourcePolicy.maxCostUnitsPerSession()
		);
	}

	@Bean
	@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
	RouteBundleActiveJourneySnapshotAdapter activeJourneySnapshotPort(RouteBundleActivationRegistry registry,
		JourneyReadinessProperties readinessProperties, JourneyReadinessService readinessService) {
		return new RouteBundleActiveJourneySnapshotAdapter(registry, readinessProperties, readinessService);
	}

	@Bean
	JourneyProfileResourcePolicy journeyProfileResourcePolicy(
		@Value("${easysubway.journey.profile.resource-policy-path}") String policyPath,
		@Value("${easysubway.journey.profile.resource-policy-sha256}") String policySha256
	) throws IOException {
		if (policyPath.isBlank()) throw new IllegalArgumentException("resource policy path is required");
		// 배포가 제공한 독립 digest로 파일을 검증하고 point/profile 공통 정책으로 캡처한다.
		return JourneyProfileResourcePolicyArtifact.read(Files.readAllBytes(Path.of(policyPath)), policySha256);
	}

	@Bean
	@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
	JourneyProfileRaptorPort journeyProfileRaptorPort() {
		return new JourneyProfileRaptorAdapter();
	}

	@Bean
	@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
	JourneyProfileApplicationService journeyProfileApplicationService(
		JourneyProfileSnapshotPort snapshotPort, JourneyProfileRaptorPort raptorPort
	) {
		return new JourneyProfileApplicationService(snapshotPort, raptorPort, CLOCK);
	}

	@Bean
	@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
	JourneyProfileDeadlineExecutor journeyProfileDeadlineExecutor(
		JourneyProfileApplicationService service,
		@Qualifier("journeyApplicationExecutor") ExecutorService executor
	) {
		return new JourneyProfileDeadlineExecutor(service, executor);
	}

	@Bean
	@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
	JourneyRaptorPort journeyRaptorPort() {
		return new JourneyRaptorAdapter();
	}

	@Bean
	@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
	JourneyRealtimePort journeyRealtimePort(JourneyTimetableRealtimeResolver resolver) {
		return new JourneyRealtimeAdapter(resolver, CLOCK, REALTIME_FRESHNESS_TTL);
	}

	@Bean
	@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
	JourneyApplicationService journeyApplicationService(
		ActiveJourneySnapshotPort activeSnapshotPort,
		JourneyRealtimePort realtimePort,
		JourneyRaptorPort raptorPort
	) {
		return new JourneyApplicationService(activeSnapshotPort, realtimePort, raptorPort, CLOCK);
	}

	@Bean
	@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
	StationTimetableSearchService stationTimetableSearchService(LoadRouteTimetablePort timetablePort) {
		return new StationTimetableSearchService(timetablePort, CLOCK);
	}

	@Bean(destroyMethod = "close")
	@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
	ExecutorService journeyApplicationExecutor() {
		return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("journey-search-", 0).factory());
	}

	@Bean(destroyMethod = "close")
	@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
	ExecutorService journeyMeasurementExecutor() {
		return new FreshPlatformMeasurementExecutor();
	}

	private static final class FreshPlatformMeasurementExecutor extends AbstractExecutorService {
		private final ExecutorService delegate = Executors.newThreadPerTaskExecutor(
			Thread.ofPlatform().name("journey-measurement-", 0).factory());
		private final Semaphore capacity = new Semaphore(1, true);

		@Override
		public void execute(Runnable command) {
			Objects.requireNonNull(command, "command");
			if (!capacity.tryAcquire()) throw new RejectedExecutionException("journey measurement is already running");
			try {
				delegate.execute(() -> {
					try { command.run(); }
					finally { capacity.release(); }
				});
			} catch (RuntimeException | Error exception) {
				capacity.release();
				throw exception;
			}
		}

		@Override
		public void shutdown() { delegate.shutdown(); }

		@Override
		public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }

		@Override
		public boolean isShutdown() { return delegate.isShutdown(); }

		@Override
		public boolean isTerminated() { return delegate.isTerminated(); }

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
			return delegate.awaitTermination(timeout, unit);
		}
	}

	@Bean
	@ConditionalOnProperty(name = "easysubway.journey-v3.search-web.enabled", havingValue = "true")
	JourneyApplicationDeadlineExecutor journeyApplicationDeadlineExecutor(
		JourneyApplicationService service,
		@Qualifier("journeyApplicationExecutor") ExecutorService executor,
		@Qualifier("journeyMeasurementExecutor") ExecutorService measurementExecutor,
		JourneyProfileResourcePolicy resourcePolicy
	) {
		return new JourneyApplicationDeadlineExecutor(service, executor, measurementExecutor,
			resourcePolicy.pointSearchDeadline());
	}

	@Bean
	@Order(4)
	SecurityFilterChain journeyV3IngressSecurityFilterChain(
		HttpSecurity http,
		JourneyReadinessProperties readinessProperties) throws Exception {
		return http
			.securityMatcher(
				SESSION_PATH,
				SEARCH_PATH,
				PROFILE_PATH,
				STATION_TIMETABLE_PATH,
				JourneyActivationController.PATH,
				JourneyBenchmarkObservationController.PATH,
				JourneyCandidateCanaryController.PATH,
				JourneyReadinessController.CANDIDATE_PATH,
				JourneyReadinessController.ACTIVE_PATH)
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers(HttpMethod.POST, SESSION_PATH, SEARCH_PATH, PROFILE_PATH, STATION_TIMETABLE_PATH).permitAll()
				.requestMatchers(HttpMethod.POST, JourneyActivationController.PATH)
				.hasRole("JOURNEY_READINESS")
				.requestMatchers(HttpMethod.POST, JourneyCandidateCanaryController.PATH)
				.hasRole("JOURNEY_READINESS")
				.requestMatchers(HttpMethod.POST, JourneyBenchmarkObservationController.PATH)
				.hasRole("JOURNEY_READINESS")
				.requestMatchers(
					HttpMethod.GET,
					JourneyReadinessController.CANDIDATE_PATH,
					JourneyReadinessController.ACTIVE_PATH)
				.hasRole("JOURNEY_READINESS")
				.anyRequest().denyAll()
			)
			.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, exception) -> {
				String pathWithinApplication = request.getRequestURI()
					.substring(request.getContextPath().length());
				boolean readinessGet = HttpMethod.GET.matches(request.getMethod())
					&& (JourneyReadinessController.CANDIDATE_PATH.equals(pathWithinApplication)
						|| JourneyReadinessController.ACTIVE_PATH.equals(pathWithinApplication));
				boolean activationPost = HttpMethod.POST.matches(request.getMethod())
					&& JourneyActivationController.PATH.equals(pathWithinApplication);
				boolean canaryPost = HttpMethod.POST.matches(request.getMethod())
					&& JourneyCandidateCanaryController.PATH.equals(pathWithinApplication);
				boolean benchmarkObservationPost = HttpMethod.POST.matches(request.getMethod())
					&& JourneyBenchmarkObservationController.PATH.equals(pathWithinApplication);
				response.setStatus(readinessGet || activationPost || canaryPost || benchmarkObservationPost ? 401 : 403);
				response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
				if (readinessGet || activationPost || canaryPost || benchmarkObservationPost) {
					response.setHeader("WWW-Authenticate", "Bearer");
				}
			}).accessDeniedHandler((request, response, exception) -> {
				response.setStatus(403);
				response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
			}))
			.addFilterBefore(
				new JourneyReadinessServiceTokenFilter(readinessProperties.serviceToken()),
				UsernamePasswordAuthenticationFilter.class)
			.build();
	}
}
