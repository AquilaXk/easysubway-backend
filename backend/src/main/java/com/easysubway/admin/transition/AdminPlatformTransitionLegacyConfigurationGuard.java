package com.easysubway.admin.transition;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
final class AdminPlatformTransitionLegacyConfigurationGuard
	implements BeanFactoryPostProcessor, EnvironmentAware {

	private static final String LEGACY_PREFIX = "easysubway.admin.platform-transition";
	private static final String BREAK_GLASS_PREFIX = "easysubway.admin.break-glass";
	private static final List<String> LEGACY_ENVIRONMENT_ALIASES = List.of(
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
		"EASYSUBWAY_ADMIN_PLATFORM_RELEASE_BLOCKER_MODE",
		"EASYSUBWAY_ADMIN_BREAK_GLASS_USERNAME",
		"EASYSUBWAY_ADMIN_BREAK_GLASS_PASSWORD",
		"EASYSUBWAY_ADMIN_BREAK_GLASS_REASON"
	);

	private Environment environment;

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		rejectPrefix(LEGACY_PREFIX);
		rejectPrefix(BREAK_GLASS_PREFIX);
		for (String alias : LEGACY_ENVIRONMENT_ALIASES) {
			if (environment.containsProperty(alias)) {
				throw legacyConfiguration(alias);
			}
		}
	}

	private void rejectPrefix(String prefix) {
		if (environment.containsProperty(prefix)
			|| Binder.get(environment)
				.bind(prefix, Bindable.mapOf(String.class, Object.class))
				.isBound()) {
			throw legacyConfiguration(prefix);
		}
	}

	private IllegalStateException legacyConfiguration(String key) {
		return new IllegalStateException("폐기된 관리자 설정은 허용되지 않습니다: " + key);
	}
}
