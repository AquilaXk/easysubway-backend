package com.easysubway.admin.transition;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("폐기된 관리자 플랫폼 전환 설정 차단")
class AdminPlatformTransitionLegacyConfigurationGuardTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withBean(AdminPlatformTransitionLegacyConfigurationGuard.class);

	@Test
	void startsWhenNoLegacyConfigurationIsPresent() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(AdminPlatformTransitionLegacyConfigurationGuard.class);
		});
	}

	@Test
	void rejectsCanonicalDescendantProperties() {
		assertStartupFailure(
			"easysubway.admin.platform-transition.flags.audit-enforcement=sensitive-leaf-value",
			"easysubway.admin.platform-transition");
		assertStartupFailure(
			"easysubway.admin.platform-transition.unknown.nested=sensitive-nested-value",
			"easysubway.admin.platform-transition");
	}

	@Test
	void rejectsEveryHistoricalEnvironmentAlias() {
		for (String alias : historicalEnvironmentAliases()) {
			assertStartupFailure(alias + "=configured", alias);
		}
	}

	@Test
	void trackedApplicationConfigurationContainsNoLegacyTransitionSurface() throws IOException {
		for (String resource : List.of("application.yml", "application-prod.yml")) {
			String source = Files.readString(Path.of("src/main/resources", resource));

			assertThat(source).doesNotContain("platform-transition");
			for (String alias : historicalEnvironmentAliases()) {
				assertThat(source).doesNotContain(alias);
			}
		}
	}

	private void assertStartupFailure(String property, String expectedIdentity) {
		String configuredValue = property.substring(property.indexOf('=') + 1);
		contextRunner.withPropertyValues(property).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure())
				.hasMessageContaining(expectedIdentity)
				.hasMessageNotContaining(configuredValue);
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
}
