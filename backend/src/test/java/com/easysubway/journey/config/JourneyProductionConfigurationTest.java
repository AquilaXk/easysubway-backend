package com.easysubway.journey.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.journey.application.ActiveJourneySnapshotPort;
import com.easysubway.journey.application.JourneyApplicationDeadlineExecutor;
import com.easysubway.journey.application.JourneyApplicationService;
import com.easysubway.journey.application.JourneyRaptorPort;
import com.easysubway.journey.application.JourneyRealtimePort;
import com.easysubway.journey.application.JourneySessionIntegrityPort;
import com.easysubway.journey.application.JourneySessionService;
import com.easysubway.journey.application.JourneySessionStore;
import com.easysubway.journey.bundle.RouteBundleActivationRegistry;
import com.easysubway.journey.bundle.RouteBundleActiveJourneySnapshotAdapter;
import com.easysubway.route.application.service.JourneyRaptorAdapter;
import com.easysubway.route.application.service.JourneyRealtimeAdapter;
import com.easysubway.route.application.service.JourneyTimetableRealtimeResolver;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("Journey 운영 composition")
class JourneyProductionConfigurationTest {

	private static final String CERTIFICATE_SHA256 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
	private static final String SESSION_PATH = "/api/v3/journeys/session";
	private static final String SEARCH_PATH = "/api/v3/journeys/search";
	private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(
			SecurityAutoConfiguration.class,
			WebMvcAutoConfiguration.class
		))
		.withUserConfiguration(
			JourneyProductionConfiguration.class,
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
	@DisplayName("search disabled 운영 프로필은 session만 조립하고 execution graph를 만들지 않는다")
	void searchDisabledProductionProfileComposesSessionOnly() {
		productionContext(
			"easysubway.journey.search.timeout=PT2S",
			"easysubway.journey.search.max-searches-per-session=12",
			"easysubway.journey.session.certificate-sha256=" + CERTIFICATE_SHA256
		).withUserConfiguration(MissingRegistryDependencyTestConfiguration.class)
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
	@DisplayName("운영 프로필은 timeout 누락을 거부한다")
	void productionProfileRejectsMissingTimeout() {
		productionContext(
			"easysubway.journey.search.max-searches-per-session=12",
			"easysubway.journey.session.certificate-sha256=" + CERTIFICATE_SHA256
		).run(context -> assertThat(context).hasFailed());
	}

	@Test
	@DisplayName("운영 프로필은 session limit 누락을 거부한다")
	void productionProfileRejectsMissingSessionLimit() {
		productionContext(
			"easysubway.journey.search.timeout=PT2S",
			"easysubway.journey.session.certificate-sha256=" + CERTIFICATE_SHA256
		).run(context -> assertThat(context).hasFailed());
	}

	@Test
	@DisplayName("운영 프로필은 certificate 누락과 잘못된 값을 거부한다")
	void productionProfileRejectsMissingOrInvalidCertificate() {
		productionContext(
			"easysubway.journey.search.timeout=PT2S",
			"easysubway.journey.search.max-searches-per-session=12"
		).run(context -> assertThat(context).hasFailed());

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
			"easysubway.journey-v3.search-web.enabled=true"
		);
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
