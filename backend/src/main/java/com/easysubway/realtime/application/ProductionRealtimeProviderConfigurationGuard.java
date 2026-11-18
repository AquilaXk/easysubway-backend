package com.easysubway.realtime.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod | staging | release | prod-like")
final class ProductionRealtimeProviderConfigurationGuard {

	ProductionRealtimeProviderConfigurationGuard(
		@Value("${EASYSUBWAY_SEOUL_TOPIS_SERVICE_KEY:}") String serviceKey
	) {
		if (serviceKey == null || serviceKey.isBlank()) {
			throw new IllegalStateException("EASYSUBWAY_SEOUL_TOPIS_SERVICE_KEY must not be blank");
		}
	}
}
