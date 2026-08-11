package com.easysubway.admin.transition;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("폐기된 관리자 플랫폼 전환 설정 차단")
class AdminPlatformTransitionLegacyConfigurationGuardTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withBean(AdminPlatformTransitionLegacyConfigurationGuard.class)
		.withUserConfiguration(MutationTrackingConfiguration.class);

	@Test
	void startsWhenNoLegacyConfigurationIsPresent() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(AdminPlatformTransitionLegacyConfigurationGuard.class);
		});
	}

	@Test
	void rejectsCanonicalDescendantProperties() {
		assertNoSingletonMutation(
			"easysubway.admin.platform-transition.flags.audit-enforcement=sensitive-leaf-value",
			"easysubway.admin.platform-transition");
		assertNoSingletonMutation(
			"easysubway.admin.platform-transition.unknown.nested=sensitive-nested-value",
			"easysubway.admin.platform-transition");
	}

	@Test
	void rejectsBreakGlassRootAndDescendantsBeforeSingletonMutation() {
		assertNoSingletonMutation(
			"easysubway.admin.break-glass=sensitive-root-value",
			"easysubway.admin.break-glass");
		assertNoSingletonMutation(
			"easysubway.admin.break-glass.unknown.nested=sensitive-descendant-value",
			"easysubway.admin.break-glass");
	}

	@Test
	void rejectsEveryHistoricalEnvironmentAlias() {
		for (String alias : historicalEnvironmentAliases()) {
			assertNoSingletonMutation(alias + "=configured", alias);
		}
	}

	@Test
	void rejectsEveryBreakGlassEnvironmentAliasBeforeSingletonMutation() {
		for (String alias : breakGlassEnvironmentAliases()) {
			assertNoSingletonMutation(alias + "=sensitive-value", alias);
		}
	}

	@Test
	void rejectsEmptyAndMultipleBreakGlassInputsBeforeSingletonMutation() {
		assertNoSingletonMutation(
			"easysubway.admin.break-glass.username=",
			"easysubway.admin.break-glass");
		assertNoSingletonMutation(
			List.of(
				"EASYSUBWAY_ADMIN_BREAK_GLASS_USERNAME=first-sensitive-value",
				"EASYSUBWAY_ADMIN_BREAK_GLASS_REASON=second-sensitive-value"
			),
			"EASYSUBWAY_ADMIN_BREAK_GLASS_USERNAME"
		);
	}

	@Test
	void trackedApplicationConfigurationContainsNoLegacyTransitionSurface() throws IOException {
		for (String resource : List.of("application.yml", "application-prod.yml")) {
			String source = Files.readString(Path.of("src/main/resources", resource));

			assertThat(source).doesNotContain("platform-transition");
			assertThat(source).doesNotContain("break-glass");
			for (String alias : historicalEnvironmentAliases()) {
				assertThat(source).doesNotContain(alias);
			}
			for (String alias : breakGlassEnvironmentAliases()) {
				assertThat(source).doesNotContain(alias);
			}
		}
	}

	private void assertNoSingletonMutation(String property, String expectedIdentity) {
		assertNoSingletonMutation(List.of(property), expectedIdentity);
	}

	private void assertNoSingletonMutation(List<String> properties, String expectedIdentity) {
		MutationTrackingConfiguration.singletonCreations.set(0);
		List<String> configuredValues = properties.stream()
			.map(property -> property.substring(property.indexOf('=') + 1))
			.filter(value -> !value.isEmpty())
			.toList();
		contextRunner.withPropertyValues(properties.toArray(String[]::new)).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasMessageContaining(expectedIdentity);
			for (String configuredValue : configuredValues) {
				assertThat(context.getStartupFailure()).hasMessageNotContaining(configuredValue);
			}
			assertThat(MutationTrackingConfiguration.singletonCreations).hasValue(0);
		});
	}

	private List<String> historicalEnvironmentAliases() {
		return List.of(
			"EASYSUBWAY_ADMIN_PLATFORM_TRANSITION_STAGE",
			"EASYSUBWAY_ADMIN_IDENTITY_STORE_ENABLED",
			"EASYSUBWAY_ADMIN_RBAC_SHADOW_ENABLED",
			"EASYSUBWAY_ADMIN_RBAC_ENFORCEMENT_ENABLED",
			"EASYSUBWAY_ADMIN_AUDIT_SHADOW_ENABLED",
			"EASYSUBWAY_ADMIN_AUDIT_ENFORCEMENT_ENABLED",
			"EASYSUBWAY_ADMIN_LEGACY_ENV_FALLBACK_ENABLED",
			"EASYSUBWAY_ADMIN_BREAK_GLASS_BOOTSTRAP_ENABLED",
			"EASYSUBWAY_ADMIN_ROLE_SEED_REQUIRED",
			"EASYSUBWAY_ADMIN_PLATFORM_FLAGS_RBAC_ENFORCEMENT",
			"EASYSUBWAY_ADMIN_PLATFORM_FLAGS_AUDIT_ENFORCEMENT",
			"EASYSUBWAY_ADMIN_PLATFORM_FLAGS_LEGACY_ENV_ADMIN_FALLBACK",
			"EASYSUBWAY_ADMIN_PLATFORM_FLAGS_BREAK_GLASS_BOOTSTRAP",
			"EASYSUBWAY_ADMIN_PLATFORM_RELEASE_BLOCKER_MODE"
		);
	}

	private List<String> breakGlassEnvironmentAliases() {
		return List.of(
			"EASYSUBWAY_ADMIN_BREAK_GLASS_USERNAME",
			"EASYSUBWAY_ADMIN_BREAK_GLASS_PASSWORD",
			"EASYSUBWAY_ADMIN_BREAK_GLASS_REASON"
		);
	}

	@Configuration(proxyBeanMethods = false)
	static class MutationTrackingConfiguration {

		private static final AtomicInteger singletonCreations = new AtomicInteger();

		@Bean
		Object mutableSingleton() {
			singletonCreations.incrementAndGet();
			return new Object();
		}
	}
}
