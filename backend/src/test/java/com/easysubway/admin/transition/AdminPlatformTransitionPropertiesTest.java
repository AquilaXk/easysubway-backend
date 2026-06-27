package com.easysubway.admin.transition;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("관리자 플랫폼 전환 설정")
class AdminPlatformTransitionPropertiesTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
		.withUserConfiguration(AdminPlatformTransitionConfiguration.class);

	@Test
	@DisplayName("기본값은 shadow 검증과 legacy env admin fallback 유지로 둔다")
	void defaultsKeepShadowModeAndLegacyFallback() {
		contextRunner.run(context -> {
			AdminPlatformTransitionProperties properties =
				context.getBean(AdminPlatformTransitionProperties.class);

			assertThat(properties.stage()).isEqualTo(AdminPlatformTransitionProperties.Stage.SHADOW);
			assertThat(properties.flags().identityStore()).isTrue();
			assertThat(properties.flags().rbacShadow()).isTrue();
			assertThat(properties.flags().rbacEnforcement()).isFalse();
			assertThat(properties.flags().auditShadow()).isTrue();
			assertThat(properties.flags().auditEnforcement()).isFalse();
			assertThat(properties.flags().legacyEnvAdminFallback()).isTrue();
			assertThat(properties.flags().breakGlassBootstrap()).isTrue();
			assertThat(properties.flags().roleSeedRequired()).isTrue();
			assertThat(properties.rbacShadow().metric()).isEqualTo("admin_rbac_shadow_denial_total");
			assertThat(properties.auditShadow().metric()).isEqualTo("admin_audit_shadow_missing_total");
			assertThat(properties.legacyEnvAdminFallback().removalCriteria())
				.contains("all production admins have admin_users rows with role seed");
			assertThat(properties.breakGlass().rotationProcedure())
				.contains("CREDENTIAL_ROTATION_REQUIRED");
			assertThat(properties.seed().roleProcedure())
				.contains("admin_role_permissions");
			assertThat(properties.seed().accountProcedure())
				.contains("admin_users seed");
			assertThat(properties.rollback().runbook())
				.contains("restore legacy env admin fallback");
			assertThat(properties.releaseGate().blockerMode())
				.isEqualTo(AdminPlatformTransitionProperties.BlockerMode.WARN);
			assertThat(properties.releaseGate().blockers())
				.contains("role or account seed is missing in prod");
		});
	}

	@Test
	@DisplayName("운영 전환 단계와 enforcement flag는 환경값으로 덮어쓸 수 있다")
	void transitionStageAndEnforcementFlagsCanBeOverridden() {
		contextRunner
			.withPropertyValues(
				"easysubway.admin.platform-transition.stage=enforce",
				"easysubway.admin.platform-transition.flags.rbac-enforcement=true",
				"easysubway.admin.platform-transition.flags.audit-enforcement=true",
				"easysubway.admin.platform-transition.flags.legacy-env-admin-fallback=false",
				"easysubway.admin.platform-transition.release-gate.blocker-mode=fail"
			)
			.run(context -> {
				AdminPlatformTransitionProperties properties =
					context.getBean(AdminPlatformTransitionProperties.class);

				assertThat(properties.stage()).isEqualTo(AdminPlatformTransitionProperties.Stage.ENFORCE);
				assertThat(properties.flags().identityStore()).isTrue();
				assertThat(properties.flags().rbacShadow()).isTrue();
				assertThat(properties.flags().rbacEnforcement()).isTrue();
				assertThat(properties.flags().auditShadow()).isTrue();
				assertThat(properties.flags().auditEnforcement()).isTrue();
				assertThat(properties.flags().legacyEnvAdminFallback()).isFalse();
				assertThat(properties.releaseGate().blockerMode())
					.isEqualTo(AdminPlatformTransitionProperties.BlockerMode.FAIL);
				assertThat(properties.releaseGate().blockers())
					.contains("role or account seed is missing in prod");
			});
	}
}
