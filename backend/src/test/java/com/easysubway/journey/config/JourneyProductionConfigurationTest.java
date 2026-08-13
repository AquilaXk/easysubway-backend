package com.easysubway.journey.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.easysubway.journey.activation.JourneyActivationService;
import com.easysubway.journey.adapter.in.web.JourneyActivationController;
import com.easysubway.journey.adapter.in.web.JourneyReadinessController;
import com.easysubway.journey.bundle.ActiveRouteBundleSnapshot;
import com.easysubway.journey.bundle.RouteBundleAdmissionEvidence;
import com.easysubway.journey.bundle.RouteBundleActivationRegistry;
import com.easysubway.journey.bundle.RouteBundleActiveJourneySnapshotAdapter;
import com.easysubway.journey.bundle.RouteBundleIdentity;
import com.easysubway.journey.readiness.JourneyReadinessProperties;
import com.easysubway.journey.readiness.JourneyReadinessService;
import com.easysubway.route.application.service.JourneyRaptorAdapter;
import com.easysubway.route.application.service.JourneyRealtimeAdapter;
import com.easysubway.route.application.service.JourneyTimetableRealtimeResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
			assertThat(context).hasSingleBean(ExecutorService.class);
			assertThat(context).hasSingleBean(JourneyApplicationDeadlineExecutor.class);
			assertThat(context).hasSingleBean(JourneyReadinessProperties.class);
			assertThat(context).hasSingleBean(JourneyReadinessService.class);
			assertThat(context).hasSingleBean(JourneyReadinessController.class);
			assertThat(context).hasSingleBean(JourneyActivationService.class);
			assertThat(context).hasSingleBean(JourneyActivationController.class);
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
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
			mockMvc.perform(get(ACTIVATION_PATH)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + READINESS_TOKEN))
				.andExpect(status().isForbidden())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
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
	@DisplayName("운영 프로필은 required execution dependency 누락·중복을 거부한다")
	void productionProfileRejectsMissingOrDuplicateExecutionDependencies() {
		validProductionProperties()
			.withUserConfiguration(MissingRegistryDependencyTestConfiguration.class)
			.run(context -> assertThat(context).hasFailed());

		validProductionProperties()
			.withUserConfiguration(MissingResolverDependencyTestConfiguration.class)
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
			});
	}

	@Test
	@DisplayName("운영 Journey executor는 context 종료 때 shutdown된다")
	void productionExecutorShutsDownWithContext() {
		var executor = new AtomicReference<ExecutorService>();
		validProductionContext().run(context -> {
			executor.set(context.getBean("journeyApplicationExecutor", ExecutorService.class));
			assertThat(executor.get()).isNotNull();
			assertThat(executor.get().isShutdown()).isFalse();
		});
		assertThat(executor.get().isShutdown()).isTrue();
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

	private static String activationCommand() {
		return """
			{"schemaVersion":1,"artifactKind":"journey-v3-activation-command","activationRequestIdentity":"activation-request-228","candidateManifestSha256":"%s","candidateGeneration":1,"expectedActiveGeneration":0,"trafficGeneration":31}
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
		JourneyTimetableRealtimeResolver journeyTimetableRealtimeResolver() {
			return mock(JourneyTimetableRealtimeResolver.class);
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
		RouteBundleActivationRegistry routeBundleActivationRegistry() {
			return mock(RouteBundleActivationRegistry.class);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class DuplicateRaptorTestConfiguration {

		@Bean
		JourneyRaptorPort duplicateJourneyRaptorPort() {
			return mock(JourneyRaptorPort.class);
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
