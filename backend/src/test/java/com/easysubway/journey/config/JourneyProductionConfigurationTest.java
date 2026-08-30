package com.easysubway.journey.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.journey.application.ActiveJourneySnapshotPort;
import com.easysubway.journey.application.JourneyApplicationDeadlineExecutor;
import com.easysubway.journey.application.JourneyApplicationService;
import com.easysubway.journey.application.JourneyRaptorPort;
import com.easysubway.journey.application.JourneyRealtimePort;
import com.easysubway.journey.application.JourneySessionIntegrityPort;
import com.easysubway.journey.application.JourneySessionService;
import com.easysubway.journey.application.JourneySessionStore;
import com.easysubway.journey.application.StationTimetableSearchService;
import com.easysubway.journey.activation.JourneyActivationService;
import com.easysubway.journey.adapter.in.web.JourneyActivationController;
import com.easysubway.journey.adapter.in.web.JourneyCandidateCanaryController;
import com.easysubway.journey.adapter.in.web.JourneyBenchmarkObservationController;
import com.easysubway.journey.adapter.in.web.JourneyReadinessController;
import com.easysubway.journey.bundle.ActiveRouteBundleSnapshot;
import com.easysubway.journey.bundle.RouteBundleAdmissionEvidence;
import com.easysubway.journey.bundle.RouteBundleActivationRegistry;
import com.easysubway.journey.bundle.RouteBundleActiveJourneySnapshotAdapter;
import com.easysubway.journey.bundle.RouteBundleIdentity;
import com.easysubway.journey.canary.JourneyCandidateCanaryService;
import com.easysubway.journey.readiness.JourneyReadinessProperties;
import com.easysubway.journey.readiness.JourneyReadinessService;
import com.easysubway.route.application.service.JourneyRaptorAdapter;
import com.easysubway.route.application.service.JourneyRealtimeAdapter;
import com.easysubway.route.application.service.JourneyTimetableRealtimeResolver;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.AvailabilityState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("Journey 운영 composition")
class JourneyProductionConfigurationTest {

	private static final String CERTIFICATE_SHA256 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
	private static final String SHA_A = "a".repeat(64);
	private static final String SHA_B = "b".repeat(64);
	private static final String SHA_C = "c".repeat(64);
	private static final String SHA_D = "d".repeat(64);
	private static final String READINESS_TOKEN = "readiness-token-with-at-least-32-characters";
	private static final String SESSION_PATH = "/api/v3/journeys/session";
	private static final String SEARCH_PATH = "/api/v3/journeys/search";
	private static final String CANDIDATE_READINESS_PATH = "/internal/v1/journey/readiness/candidate";
	private static final String ACTIVE_READINESS_PATH = "/internal/v1/journey/readiness/active";
	private static final String ACTIVATION_PATH = "/internal/v1/journey/activation";
	private static final String CANARY_PATH = "/internal/v1/journey/canary";
	private static final String BENCHMARK_OBSERVATION_PATH = "/internal/v1/journey/benchmark-observation";
	private static final Instant VERIFIED_AT = Instant.parse("2026-08-12T00:00:00Z");
	private static final Instant STAGED_AT = Instant.parse("2026-08-12T00:00:01Z");
	private static final Instant ACTIVATED_AT = Instant.parse("2026-08-12T00:00:02Z");
	private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(
			SecurityAutoConfiguration.class,
			WebMvcAutoConfiguration.class
		))
		.withUserConfiguration(
			JourneyProductionConfiguration.class,
			JourneyReadinessController.class,
			JourneyActivationController.class,
			JourneyCandidateCanaryController.class,
			JourneyBenchmarkObservationController.class,
			JourneyEndpointProbeController.class
		);

	@Test
	@DisplayName("운영 프로필은 required config와 exact one Journey 실행 graph를 조립한다")
	void productionProfileComposesOneJourneyExecutionGraph() {
		validProductionContext().run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(JourneySearchPolicyProperties.class);
			assertThat(context).hasSingleBean(JourneySessionService.class);
			assertThat(context).hasSingleBean(ActiveJourneySnapshotPort.class);
			assertThat(context.getBean(ActiveJourneySnapshotPort.class))
				.isInstanceOf(RouteBundleActiveJourneySnapshotAdapter.class);
			assertThat(context).hasSingleBean(JourneyRaptorPort.class);
			assertThat(context.getBean(JourneyRaptorPort.class)).isInstanceOf(JourneyRaptorAdapter.class);
			assertThat(context).hasSingleBean(JourneyRealtimePort.class);
			assertThat(context.getBean(JourneyRealtimePort.class)).isInstanceOf(JourneyRealtimeAdapter.class);
			assertThat(context).hasSingleBean(JourneyApplicationService.class);
			assertThat(context).hasSingleBean(StationTimetableSearchService.class);
			assertThat(context.getBeansOfType(ExecutorService.class)).hasSize(2)
				.containsKeys("journeyApplicationExecutor", "journeyMeasurementExecutor");
			assertThat(context).hasSingleBean(JourneyApplicationDeadlineExecutor.class);
			assertThat(context).hasSingleBean(JourneyReadinessProperties.class);
			assertThat(context).hasSingleBean(JourneyReadinessService.class);
			assertThat(context).hasSingleBean(JourneyReadinessController.class);
			assertThat(context).hasSingleBean(JourneyActivationService.class);
			assertThat(context).hasSingleBean(JourneyActivationController.class);
			assertThat(context).hasSingleBean(JourneyCandidateCanaryService.class);
			assertThat(context).hasSingleBean(JourneyCandidateCanaryController.class);
			assertThat(context).hasSingleBean(JourneyBenchmarkObservationController.class);
		});
	}

	@Test
	@DisplayName("internal benchmark observation은 readiness Bearer로 exact POST만 허용한다")
	void benchmarkObservationIngressRequiresReadinessBearer() {
		validProductionContext().run(context -> {
			assertThat(context).hasNotFailed();
			var mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.apply(springSecurity())
				.build();

			mockMvc.perform(post(BENCHMARK_OBSERVATION_PATH)
					.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
					.content("{}"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
			mockMvc.perform(get(BENCHMARK_OBSERVATION_PATH)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + READINESS_TOKEN))
				.andExpect(status().isForbidden())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
		});
	}

	@Test
	@DisplayName("internal activation은 readiness와 같은 Bearer로 exact POST만 허용한다")
	void activationIngressRequiresBearerAndDeniesOtherMethods() {
		validProductionContext().run(context -> {
			assertThat(context).hasNotFailed();
			var registry = context.getBean(RouteBundleActivationRegistry.class);
			var identity = identity();
			var evidence = new RouteBundleAdmissionEvidence(
				SHA_A, "final", "promotion", "receipt", "activation-request-228");
			when(registry.candidateSnapshot()).thenReturn(new RouteBundleActivationRegistry.CandidateSnapshot(
				1, identity, evidence, VERIFIED_AT, STAGED_AT));
			var active = mock(ActiveRouteBundleSnapshot.class);
			when(active.generation()).thenReturn(1L);
			when(active.identity()).thenReturn(identity);
			when(active.admissionEvidence()).thenReturn(evidence);
			when(active.activatedAt()).thenReturn(ACTIVATED_AT);
			when(registry.activate(SHA_A, 0)).thenReturn(active);
			when(registry.activeSnapshot()).thenReturn(active);
			var mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.apply(springSecurity())
				.build();

			mockMvc.perform(post(ACTIVATION_PATH)
					.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
					.content(activationCommand()))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(content().string(""));
			mockMvc.perform(post(ACTIVATION_PATH)
					.header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token")
					.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
					.content(activationCommand()))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(content().string(""));
			mockMvc.perform(get(ACTIVATION_PATH)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + READINESS_TOKEN))
				.andExpect(status().isForbidden())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(content().string(""));
			mockMvc.perform(post(ACTIVATION_PATH)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + READINESS_TOKEN)
					.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
					.content(activationCommand()))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(jsonPath("$.artifactKind").value("journey-v3-active-readiness"))
				.andExpect(jsonPath("$.trafficGeneration").value(31));

			verify(registry).activate(SHA_A, 0);
		});
	}

	@Test
	@DisplayName("internal candidate canary는 readiness와 같은 Bearer로 exact POST만 허용한다")
	void candidateCanaryIngressRequiresBearerAndDeniesOtherMethods() {
		validProductionContext().run(context -> {
			assertThat(context).hasNotFailed();
			var registry = context.getBean(RouteBundleActivationRegistry.class);
			var emptyRegistry = new RouteBundleActivationRegistry(Clock.fixed(VERIFIED_AT, ZoneOffset.UTC));
			when(registry.candidateExecutionSnapshot())
				.thenAnswer(ignored -> emptyRegistry.candidateExecutionSnapshot());
			var mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.apply(springSecurity())
				.build();

			mockMvc.perform(post(CANARY_PATH)
					.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
					.content(canaryCommand()))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(content().string(""));
			mockMvc.perform(post(CANARY_PATH)
					.header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token")
					.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
					.content(canaryCommand()))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(content().string(""));
			mockMvc.perform(get(CANARY_PATH)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + READINESS_TOKEN))
				.andExpect(status().isForbidden())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(content().string(""));
			mockMvc.perform(post(CANARY_PATH)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + READINESS_TOKEN)
					.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
					.content(canaryCommand()))
				.andExpect(status().isServiceUnavailable())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(jsonPath("$.artifactKind").value("journey-v3-candidate-canary-failure"))
				.andExpect(jsonPath("$.passed").value(false))
				.andExpect(jsonPath("$.reason").value("UNAVAILABLE"));

			verify(registry).candidateExecutionSnapshot();
		});
	}

	@Test
	@DisplayName("운영 프로필은 required execution dependency 누락·중복을 거부한다")
	void productionProfileRejectsMissingOrDuplicateExecutionDependencies() {
		validProductionProperties()
			.withUserConfiguration(MissingRegistryDependencyTestConfiguration.class)
			.run(context -> assertThat(context).hasFailed());

		validProductionProperties()
			.withUserConfiguration(MissingResolverDependencyTestConfiguration.class)
			.run(context -> assertThat(context).hasFailed());

		validProductionProperties()
			.withUserConfiguration(MissingAvailabilityDependencyTestConfiguration.class)
			.run(context -> assertThat(context).hasFailed());

		validProductionContext()
			.withUserConfiguration(DuplicateRaptorTestConfiguration.class)
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	@DisplayName("search disabled 운영 프로필도 readiness registry를 요구하고 execution graph는 만들지 않는다")
	void searchDisabledProductionProfileComposesSessionOnly() {
		productionContext(
			"easysubway.journey.search.timeout=PT2S",
			"easysubway.journey.search.max-searches-per-session=12",
			"easysubway.journey.session.certificate-sha256=" + CERTIFICATE_SHA256,
			"easysubway.journey-v3.readiness.service-token=" + READINESS_TOKEN,
			"easysubway.journey-v3.readiness.instance-id=backend-a",
			"easysubway.journey-v3.readiness.release-tuple-sha256=" + SHA_A,
			"easysubway.journey-v3.readiness.backend-image-digest=sha256:" + SHA_B,
			"easysubway.journey-v3.readiness.backend-config-sha256=" + SHA_C,
			"easysubway.journey-v3.readiness.journey-contract-sha256=" + SHA_D,
			"easysubway.journey-v3.readiness.traffic-generation=31"
		).withUserConfiguration(DependencyTestConfiguration.class)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(JourneySessionService.class);
				assertThat(context).doesNotHaveBean(ActiveJourneySnapshotPort.class);
				assertThat(context).doesNotHaveBean(JourneyApplicationService.class);
				assertThat(context).doesNotHaveBean(JourneyApplicationDeadlineExecutor.class);
				assertThat(context).doesNotHaveBean(ExecutorService.class);
				assertThat(context).doesNotHaveBean(JourneyCandidateCanaryService.class);
				assertThat(context).doesNotHaveBean(JourneyCandidateCanaryController.class);
			});
	}

	@Test
	@DisplayName("운영 Journey executors는 context 종료 때 shutdown된다")
	void productionExecutorsShutDownWithContext() {
		var executor = new AtomicReference<ExecutorService>();
		var measurementExecutor = new AtomicReference<ExecutorService>();
		validProductionContext().run(context -> {
			executor.set(context.getBean("journeyApplicationExecutor", ExecutorService.class));
			measurementExecutor.set(context.getBean("journeyMeasurementExecutor", ExecutorService.class));
			assertThat(executor.get()).isNotNull();
			assertThat(executor.get().isShutdown()).isFalse();
			assertThat(measurementExecutor.get()).isNotNull();
			assertThat(measurementExecutor.get()).isNotSameAs(executor.get());
			assertThat(measurementExecutor.get().isShutdown()).isFalse();
			Thread firstMeasurementThread = CompletableFuture.supplyAsync(Thread::currentThread, measurementExecutor.get()).join();
			Thread secondMeasurementThread = CompletableFuture.supplyAsync(Thread::currentThread, measurementExecutor.get()).join();
			assertThat(secondMeasurementThread).isNotSameAs(firstMeasurementThread);
		});
		assertThat(executor.get().isShutdown()).isTrue();
		assertThat(measurementExecutor.get().isShutdown()).isTrue();
	}

	@Test
	@DisplayName("운영 Journey measurement executor는 동시 측정을 fail-closed로 거부한다")
	void productionMeasurementExecutorRejectsConcurrentMeasurement() throws Exception {
		validProductionContext().run(context -> {
			ExecutorService measurementExecutor = context.getBean("journeyMeasurementExecutor", ExecutorService.class);
			var started = new CountDownLatch(1);
			var release = new CountDownLatch(1);
			measurementExecutor.execute(() -> {
				started.countDown();
				try { release.await(); }
				catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
			});
			try {
				assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
				assertThatThrownBy(() -> measurementExecutor.execute(() -> { }))
					.isInstanceOf(RejectedExecutionException.class);
			} finally {
				release.countDown();
			}
		});
	}

	@Test
	@DisplayName("운영 Journey measurement executor는 delegate 거부 뒤 capacity를 반환한다")
	void productionMeasurementExecutorReleasesCapacityAfterDelegateRejection() {
		validProductionContext().run(context -> {
			ExecutorService measurementExecutor = context.getBean("journeyMeasurementExecutor", ExecutorService.class);
			assertThat(measurementExecutor.shutdownNow()).isEmpty();
			for (int attempt = 0; attempt < 2; attempt++) {
				assertThatThrownBy(() -> measurementExecutor.execute(() -> { }))
					.isInstanceOf(RejectedExecutionException.class)
					.hasMessageNotContaining("journey measurement is already running");
			}
		});
	}

	@Test
	@DisplayName("운영 Journey POST ingress만 controller에 전달한다")
	void journeyIngressPermitsOnlyPost() {
		validProductionContext().run(context -> {
			var mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.apply(springSecurity())
				.build();

			mockMvc.perform(post(SESSION_PATH)).andExpect(status().isNoContent());
			mockMvc.perform(post(SEARCH_PATH)).andExpect(status().isNoContent());
			mockMvc.perform(get(SESSION_PATH)).andExpect(status().isForbidden());
			mockMvc.perform(get(SEARCH_PATH)).andExpect(status().isForbidden());
		});
	}

	@Test
	@DisplayName("internal candidate/active readiness는 exact Bearer와 closed no-store schema만 허용한다")
	void readinessIngressRequiresBearerAndSeparatesCandidateFromActive() {
		validProductionContext().run(context -> {
			assertThat(context).hasNotFailed();
			var registry = context.getBean(RouteBundleActivationRegistry.class);
			var identity = identity();
			var evidence = evidence();
			when(registry.candidateSnapshot()).thenReturn(new RouteBundleActivationRegistry.CandidateSnapshot(
				7, identity, evidence, VERIFIED_AT, STAGED_AT));
			var active = mock(ActiveRouteBundleSnapshot.class);
			when(active.generation()).thenReturn(7L);
			when(active.identity()).thenReturn(identity);
			when(active.admissionEvidence()).thenReturn(evidence);
			when(active.activatedAt()).thenReturn(ACTIVATED_AT);
			when(registry.activeSnapshot()).thenReturn(active);

			var mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.apply(springSecurity())
				.build();

			mockMvc.perform(get(CANDIDATE_READINESS_PATH))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
			mockMvc.perform(get(CANDIDATE_READINESS_PATH)
				.header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
			mockMvc.perform(get(CANDIDATE_READINESS_PATH)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + READINESS_TOKEN))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(jsonPath("$.schemaVersion").value(1))
				.andExpect(jsonPath("$.artifactKind").value("journey-v3-candidate-readiness"))
				.andExpect(jsonPath("$.instanceId").value("backend-a"))
				.andExpect(jsonPath("$.releaseTupleSha256").value(SHA_A))
				.andExpect(jsonPath("$.backendImageDigest").value("sha256:" + SHA_B))
				.andExpect(jsonPath("$.backendConfigSha256").value(SHA_C))
				.andExpect(jsonPath("$.journeyContractSha256").value(SHA_D))
				.andExpect(jsonPath("$.routeBundleManifestSha256").value(SHA_A))
				.andExpect(jsonPath("$.generation").value(7))
				.andExpect(jsonPath("$.warmed").value(true))
				.andExpect(jsonPath("$.ready").value(true))
				.andExpect(jsonPath("$.trafficGeneration").doesNotExist())
				.andExpect(jsonPath("$.servingReady").doesNotExist())
				.andExpect(jsonPath("$.evidenceSha256").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")));
			mockMvc.perform(get(ACTIVE_READINESS_PATH)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + READINESS_TOKEN))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(jsonPath("$.artifactKind").value("journey-v3-active-readiness"))
				.andExpect(jsonPath("$.generation").value(7))
				.andExpect(jsonPath("$.trafficGeneration").value(31))
				.andExpect(jsonPath("$.servingReady").value(true))
				.andExpect(jsonPath("$.draining").value(false))
				.andExpect(jsonPath("$.ready").doesNotExist())
				.andExpect(jsonPath("$.evidenceSha256").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")));
			mockMvc.perform(post(CANDIDATE_READINESS_PATH)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + READINESS_TOKEN))
				.andExpect(status().isForbidden())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

			verify(registry).candidateSnapshot();
			verify(registry).activeSnapshot();
		});
	}

	@Test
	@DisplayName("non-root servlet context에서도 readiness Bearer challenge를 유지한다")
	void readinessBearerChallengeUsesContextRelativePath() {
		validProductionContext().run(context -> {
			assertThat(context).hasNotFailed();
			var mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.apply(springSecurity())
				.build();

			mockMvc.perform(get("/gateway" + CANDIDATE_READINESS_PATH).contextPath("/gateway"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
		});
	}

	@Test
	@DisplayName("candidate/active 부재는 authenticated sanitized 503이고 registry를 바꾸지 않는다")
	void readinessUnavailableIsSanitized() {
		validProductionContext().run(context -> {
			assertThat(context).hasNotFailed();
			var registry = context.getBean(RouteBundleActivationRegistry.class);
			var emptyRegistry = new RouteBundleActivationRegistry(Clock.fixed(VERIFIED_AT, ZoneOffset.UTC));
			when(registry.candidateSnapshot()).thenAnswer(ignored -> emptyRegistry.candidateSnapshot());
			when(registry.activeSnapshot()).thenAnswer(ignored -> emptyRegistry.activeSnapshot());
			var mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.apply(springSecurity())
				.build();

			for (String path : new String[] {CANDIDATE_READINESS_PATH, ACTIVE_READINESS_PATH}) {
				mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + READINESS_TOKEN))
					.andExpect(status().isServiceUnavailable())
					.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
					.andExpect(jsonPath("$.schemaVersion").value(1))
					.andExpect(jsonPath("$.artifactKind").value("journey-v3-readiness-failure"))
					.andExpect(jsonPath("$.ready").value(false))
					.andExpect(jsonPath("$.reason").value("UNAVAILABLE"))
					.andExpect(jsonPath("$.instanceId").doesNotExist())
					.andExpect(jsonPath("$.detail").doesNotExist());
			}
			verify(registry).candidateSnapshot();
			verify(registry).activeSnapshot();
		});
	}

	@Test
	@DisplayName("candidate와 active readiness는 Spring application availability 전환을 evidence에 결속한다")
	void readinessTracksApplicationAvailability() {
		validProductionContext().run(context -> {
			assertThat(context).hasNotFailed();
			var registry = context.getBean(RouteBundleActivationRegistry.class);
			var availability = new MutableApplicationAvailability();
			var readinessService = new JourneyReadinessService(
				registry,
				context.getBean(JourneyReadinessProperties.class),
				availability);
			var identity = identity();
			var evidence = evidence();
			when(registry.candidateSnapshot()).thenReturn(new RouteBundleActivationRegistry.CandidateSnapshot(
				7, identity, evidence, VERIFIED_AT, STAGED_AT));
			var active = mock(ActiveRouteBundleSnapshot.class);
			when(active.generation()).thenReturn(7L);
			when(active.identity()).thenReturn(identity);
			when(active.admissionEvidence()).thenReturn(evidence);
			when(active.activatedAt()).thenReturn(ACTIVATED_AT);

			availability.readinessState(ReadinessState.ACCEPTING_TRAFFIC);
			var acceptingCandidate = readinessService.candidate();
			var acceptingActive = readinessService.active(active);
			assertThat(acceptingCandidate.ready()).isTrue();
			assertThat(acceptingActive.servingReady()).isTrue();
			assertThat(acceptingActive.draining()).isFalse();

			availability.readinessState(ReadinessState.REFUSING_TRAFFIC);
			var refusingCandidate = readinessService.candidate();
			var refusingActive = readinessService.active(active);
			assertThat(refusingCandidate.ready()).isFalse();
			assertThat(refusingActive.servingReady()).isFalse();
			assertThat(refusingActive.draining()).isTrue();
			assertThat(refusingCandidate.evidenceSha256())
				.isNotEqualTo(acceptingCandidate.evidenceSha256());
			assertThat(refusingActive.evidenceSha256())
				.isNotEqualTo(acceptingActive.evidenceSha256());

			verify(registry, times(2)).candidateSnapshot();
		});
	}

	@Test
	@DisplayName("Active readiness rejects a non-current route bundle service timezone")
	void readinessRejectsNonCurrentActiveRouteBundleServiceTimezone() {
		validProductionContext().run(context -> {
			assertThat(context).hasNotFailed();
			var registry = context.getBean(RouteBundleActivationRegistry.class);
			var availability = new MutableApplicationAvailability();
			var readinessService = new JourneyReadinessService(
				registry,
				context.getBean(JourneyReadinessProperties.class),
				availability);
			var identity = mock(RouteBundleIdentity.class);
			when(identity.serviceTimezone()).thenReturn("UTC");
			var active = mock(ActiveRouteBundleSnapshot.class);
			when(active.identity()).thenReturn(identity);

			assertThatThrownBy(() -> readinessService.active(active))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("active route bundle service timezone is not current");
		});
	}

	@Test
	@DisplayName("Active readiness rejects a non-current service-day identity")
	void activeReadinessRejectsNonCurrentServiceDayIdentity() {
		assertThatThrownBy(() -> activeReadiness("UTC", "03:00"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("active readiness service-day identity is not current");
		assertThatThrownBy(() -> activeReadiness("Asia/Seoul", "04:00"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("active readiness service-day identity is not current");
	}

	@Test
	@DisplayName("readiness runtime identity 누락·형식 오류·non-positive traffic generation은 startup을 거부한다")
	void productionProfileRejectsInvalidReadinessIdentity() {
		productionContext(
			"easysubway.journey.search.timeout=PT2S",
			"easysubway.journey.search.max-searches-per-session=12",
			"easysubway.journey.session.certificate-sha256=" + CERTIFICATE_SHA256,
			"easysubway.journey-v3.search-web.enabled=true"
		).withUserConfiguration(DependencyTestConfiguration.class)
			.run(context -> assertThat(context).hasFailed());

		validProductionProperties()
			.withPropertyValues("easysubway.journey-v3.readiness.backend-image-digest=invalid")
			.withUserConfiguration(DependencyTestConfiguration.class)
			.run(context -> assertThat(context).hasFailed());

		validProductionProperties()
			.withPropertyValues("easysubway.journey-v3.readiness.traffic-generation=0")
			.withUserConfiguration(DependencyTestConfiguration.class)
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	@DisplayName("운영 프로필은 timeout 누락을 거부한다")
	void productionProfileRejectsMissingTimeout() {
		productionContext(
			"easysubway.journey.search.max-searches-per-session=12",
			"easysubway.journey.session.certificate-sha256=" + CERTIFICATE_SHA256
		).withUserConfiguration(MissingRegistryDependencyTestConfiguration.class)
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	@DisplayName("운영 프로필은 session limit 누락을 거부한다")
	void productionProfileRejectsMissingSessionLimit() {
		productionContext(
			"easysubway.journey.search.timeout=PT2S",
			"easysubway.journey.session.certificate-sha256=" + CERTIFICATE_SHA256
		).withUserConfiguration(MissingRegistryDependencyTestConfiguration.class)
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	@DisplayName("운영 프로필은 certificate 누락과 잘못된 값을 거부한다")
	void productionProfileRejectsMissingOrInvalidCertificate() {
		productionContext(
			"easysubway.journey.search.timeout=PT2S",
			"easysubway.journey.search.max-searches-per-session=12"
		).withUserConfiguration(MissingRegistryDependencyTestConfiguration.class)
			.run(context -> assertThat(context).hasFailed());

		productionContext(
			"easysubway.journey.search.timeout=PT2S",
			"easysubway.journey.search.max-searches-per-session=12",
			"easysubway.journey.session.certificate-sha256=invalid"
		).withUserConfiguration(MissingRegistryDependencyTestConfiguration.class)
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	@DisplayName("개발 프로필은 Journey 운영 bean과 config를 요구하지 않는다")
	void developmentProfileDoesNotComposeProductionJourney() {
		contextRunner
			.withPropertyValues("spring.profiles.active=dev")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(JourneySearchPolicyProperties.class);
				assertThat(context).doesNotHaveBean(JourneySessionService.class);
			});
	}

	@Test
	@DisplayName("capacity evidence 프로필은 Journey 운영 bean과 config를 요구하지 않는다")
	void capacityEvidenceProfileDoesNotComposeProductionJourney() {
		contextRunner
			.withPropertyValues("spring.profiles.active=prod,capacity-evidence")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(JourneySearchPolicyProperties.class);
				assertThat(context).doesNotHaveBean(JourneySessionService.class);
			});
	}

	private WebApplicationContextRunner validProductionContext() {
		return validProductionProperties().withUserConfiguration(DependencyTestConfiguration.class);
	}

	private WebApplicationContextRunner validProductionProperties() {
		return productionContext(
			"easysubway.journey.search.timeout=PT2S",
			"easysubway.journey.search.max-searches-per-session=12",
			"easysubway.journey.session.certificate-sha256=" + CERTIFICATE_SHA256,
			"easysubway.journey-v3.search-web.enabled=true",
			"easysubway.journey-v3.readiness.service-token=" + READINESS_TOKEN,
			"easysubway.journey-v3.readiness.instance-id=backend-a",
			"easysubway.journey-v3.readiness.release-tuple-sha256=" + SHA_A,
			"easysubway.journey-v3.readiness.backend-image-digest=sha256:" + SHA_B,
			"easysubway.journey-v3.readiness.backend-config-sha256=" + SHA_C,
			"easysubway.journey-v3.readiness.journey-contract-sha256=" + SHA_D,
			"easysubway.journey-v3.readiness.traffic-generation=31"
		);
	}

	private static RouteBundleIdentity identity() {
		return new RouteBundleIdentity(
			1,
			"server-route-bundle",
			"bundle-31",
			31,
			SHA_B,
			SHA_C,
			SHA_D,
			"e".repeat(64),
			"f".repeat(64),
			"1".repeat(64),
			"2".repeat(64),
			"3".repeat(64),
			"Asia/Seoul",
			"2026-08-12T09:00:00.000+09:00",
			"2026-08-13T09:00:00.000+09:00",
			new RouteBundleIdentity.SchemaCompatibility(3, 3),
			"route-bundle-key",
			new RouteBundleIdentity.Signature("rsa-sha256-server-route-bundle-v1", "AQID"));
	}

	private static RouteBundleAdmissionEvidence evidence() {
		return new RouteBundleAdmissionEvidence(SHA_A, "final", "promotion", "receipt", "activation");
	}

	private static JourneyReadinessService.ActiveReadiness activeReadiness(
		String serviceTimezone,
		String serviceDayCutoff) {
		return new JourneyReadinessService.ActiveReadiness(
			1, "journey-v3-active-readiness", "backend-a", SHA_A, "sha256:" + SHA_A,
			SHA_A, SHA_A, SHA_A, "bundle-a", 1, 1, serviceTimezone, serviceDayCutoff,
			31, true, false, VERIFIED_AT, ACTIVATED_AT, SHA_A);
	}

	private static String activationCommand() {
		return """
			{"schemaVersion":1,"artifactKind":"journey-v3-activation-command","activationRequestIdentity":"activation-request-228","candidateManifestSha256":"%s","candidateGeneration":1,"expectedActiveGeneration":0,"trafficGeneration":31}
			""".formatted(SHA_A).strip();
	}

	private static String canaryCommand() {
		return """
			{"schemaVersion":1,"artifactKind":"journey-v3-candidate-canary-command","canaryRequestIdentity":"canary-request-236","candidateManifestSha256":"%s","candidateGeneration":1,"requestId":"01K1Y000000000000000000000","originStationId":"station-origin","destinationStationId":"station-destination","mobilityProfile":"STEP_FREE","constraintMode":"REQUIRE_STEP_FREE","maxTransfers":2,"alternativeCount":1}
			""".formatted(SHA_A).strip();
	}

	private WebApplicationContextRunner productionContext(String... propertyValues) {
		return contextRunner
			.withPropertyValues("spring.profiles.active=prod")
			.withPropertyValues(propertyValues);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class DependencyTestConfiguration {

		@Bean
		JourneySessionIntegrityPort journeySessionIntegrityPort() {
			return mock(JourneySessionIntegrityPort.class);
		}

		@Bean
		JourneySessionStore journeySessionStore() {
			return mock(JourneySessionStore.class);
		}

		@Bean
		RouteBundleActivationRegistry routeBundleActivationRegistry() {
			return mock(RouteBundleActivationRegistry.class);
		}

		@Bean
		MutableApplicationAvailability applicationAvailability() {
			return new MutableApplicationAvailability();
		}

		@Bean
		JourneyTimetableRealtimeResolver journeyTimetableRealtimeResolver() {
			return mock(JourneyTimetableRealtimeResolver.class);
		}

		@Bean
		LoadRouteTimetablePort loadRouteTimetablePort() {
			return mock(LoadRouteTimetablePort.class);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class MissingRegistryDependencyTestConfiguration {

		@Bean
		JourneySessionIntegrityPort journeySessionIntegrityPort() {
			return mock(JourneySessionIntegrityPort.class);
		}

		@Bean
		JourneySessionStore journeySessionStore() {
			return mock(JourneySessionStore.class);
		}

		@Bean
		MutableApplicationAvailability applicationAvailability() {
			return new MutableApplicationAvailability();
		}

		@Bean
		JourneyTimetableRealtimeResolver journeyTimetableRealtimeResolver() {
			return mock(JourneyTimetableRealtimeResolver.class);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class MissingResolverDependencyTestConfiguration {

		@Bean
		JourneySessionIntegrityPort journeySessionIntegrityPort() {
			return mock(JourneySessionIntegrityPort.class);
		}

		@Bean
		JourneySessionStore journeySessionStore() {
			return mock(JourneySessionStore.class);
		}

		@Bean
		MutableApplicationAvailability applicationAvailability() {
			return new MutableApplicationAvailability();
		}

		@Bean
		RouteBundleActivationRegistry routeBundleActivationRegistry() {
			return mock(RouteBundleActivationRegistry.class);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class MissingAvailabilityDependencyTestConfiguration {

		@Bean
		JourneySessionIntegrityPort journeySessionIntegrityPort() {
			return mock(JourneySessionIntegrityPort.class);
		}

		@Bean
		JourneySessionStore journeySessionStore() {
			return mock(JourneySessionStore.class);
		}

		@Bean
		RouteBundleActivationRegistry routeBundleActivationRegistry() {
			return mock(RouteBundleActivationRegistry.class);
		}

		@Bean
		JourneyTimetableRealtimeResolver journeyTimetableRealtimeResolver() {
			return mock(JourneyTimetableRealtimeResolver.class);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class DuplicateRaptorTestConfiguration {

		@Bean
		JourneyRaptorPort duplicateJourneyRaptorPort() {
			return mock(JourneyRaptorPort.class);
		}
	}

	static final class MutableApplicationAvailability implements ApplicationAvailability {

		private ReadinessState readinessState = ReadinessState.ACCEPTING_TRAFFIC;

		void readinessState(ReadinessState readinessState) {
			this.readinessState = readinessState;
		}

		@Override
		public <S extends AvailabilityState> S getState(Class<S> stateType) {
			return getState(stateType, null);
		}

		@Override
		public <S extends AvailabilityState> S getState(Class<S> stateType, S defaultState) {
			if (stateType == ReadinessState.class) {
				return stateType.cast(readinessState);
			}
			return defaultState;
		}

		@Override
		public <S extends AvailabilityState> AvailabilityChangeEvent<S> getLastChangeEvent(Class<S> stateType) {
			return null;
		}
	}

	@RestController
	static class JourneyEndpointProbeController {

		@PostMapping({SESSION_PATH, SEARCH_PATH})
		ResponseEntity<Void> accept() {
			return ResponseEntity.noContent().build();
		}
	}
}
