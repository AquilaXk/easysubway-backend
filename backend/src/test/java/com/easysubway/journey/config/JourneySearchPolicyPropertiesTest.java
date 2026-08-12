package com.easysubway.journey.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class JourneySearchPolicyPropertiesTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
		.withUserConfiguration(TestConfiguration.class);

	@Test
	void bindsExactRequiredPolicyValues() {
		contextRunner
			.withPropertyValues(
				"easysubway.journey.search.timeout=2500ms",
				"easysubway.journey.search.max-searches-per-session=12")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(JourneySearchPolicyProperties.class);
				assertThat(context.getBean(JourneySearchPolicyProperties.class))
					.isEqualTo(new JourneySearchPolicyProperties(Duration.ofMillis(2500), 12));
			});
	}

	@Test
	void rejectsEitherMissingValueWithoutDefaulting() {
		assertRejected(List.of("easysubway.journey.search.max-searches-per-session=12"), "timeout");
		assertRejected(List.of("easysubway.journey.search.timeout=2s"), "maxSearchesPerSession");
	}

	@Test
	void rejectsNonPositiveTimeoutAndOutOfRangeLimit() {
		assertRejected(policy("0s", 12), "timeout");
		assertRejected(policy("-1s", 12), "timeout");
		assertRejected(policy("2s", 0), "maxSearchesPerSession");
		assertRejected(policy("2s", 51), "maxSearchesPerSession");
	}

	@Test
	void rejectsMalformedDurationAndUnknownFieldsAtTheBindingBoundary() {
		assertRejected(policy("not-a-duration", 12), "easysubway.journey.search.timeout");
		assertRejected(List.of(
			"easysubway.journey.search.timeout=2s",
			"easysubway.journey.search.max-searches-per-session=12",
			"easysubway.journey.search.fallback-timeout=5s"), "fallback-timeout");
	}

	private List<String> policy(String timeout, int maxSearchesPerSession) {
		return List.of(
			"easysubway.journey.search.timeout=" + timeout,
			"easysubway.journey.search.max-searches-per-session=" + maxSearchesPerSession);
	}

	private void assertRejected(List<String> properties, String expectedIdentity) {
		contextRunner.withPropertyValues(properties.toArray(String[]::new)).run(context -> {
			assertThat(context).hasFailed();
			String diagnostic = Stream.iterate(
				context.getStartupFailure(),
				cause -> cause != null,
				Throwable::getCause)
				.map(Throwable::getMessage)
				.filter(message -> message != null)
				.collect(Collectors.joining("\n"));
			assertThat(diagnostic).contains(expectedIdentity);
		});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(JourneySearchPolicyProperties.class)
	static class TestConfiguration {
	}
}
