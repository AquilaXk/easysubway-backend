package com.easysubway.admin.transition;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
final class AdminPlatformTransitionLegacyConfigurationGuard implements SmartInitializingSingleton {

	private static final String LEGACY_PREFIX = "easysubway.admin.platform-transition";
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
		"EASYSUBWAY_ADMIN_PLATFORM_RELEASE_BLOCKER_MODE"
	);

	private final Environment environment;

	AdminPlatformTransitionLegacyConfigurationGuard(Environment environment) {
		this.environment = environment;
	}

	@Override
	public void afterSingletonsInstantiated() {
		if (environment.containsProperty(LEGACY_PREFIX)
			|| Binder.get(environment)
				.bind(LEGACY_PREFIX, Bindable.mapOf(String.class, Object.class))
				.isBound()) {
			throw legacyConfiguration(LEGACY_PREFIX);
		}
		for (String alias : LEGACY_ENVIRONMENT_ALIASES) {
			if (environment.containsProperty(alias)) {
				throw legacyConfiguration(alias);
			}
		}
	}

	private IllegalStateException legacyConfiguration(String key) {
		return new IllegalStateException("폐기된 관리자 플랫폼 전환 설정은 허용되지 않습니다: " + key);
	}
}
