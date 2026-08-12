package com.easysubway.journey.readiness;

import com.easysubway.journey.bundle.RouteBundleActivationRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("(prod | staging | release | prod-like) & !capacity-evidence")
class JourneyReadinessRegistryConfiguration {

	@Bean
	@ConditionalOnMissingBean(RouteBundleActivationRegistry.class)
	RouteBundleActivationRegistry routeBundleActivationRegistry() {
		return new RouteBundleActivationRegistry(Clock.systemUTC());
	}
}
