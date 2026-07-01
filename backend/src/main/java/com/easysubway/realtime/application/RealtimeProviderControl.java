package com.easysubway.realtime.application;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public class RealtimeProviderControl {

	private final ConcurrentMap<String, ProviderSwitch> switches = new ConcurrentHashMap<>();

	public boolean providerEnabled(String providerId) {
		return switchState(providerId).enabled();
	}

	public void disableProvider(String providerId, String reason) {
		switches.put(providerId, new ProviderSwitch(false, cleanReason(reason)));
	}

	public void enableProvider(String providerId) {
		switches.put(providerId, new ProviderSwitch(true, null));
	}

	public RealtimeProviderSwitchState switchState(String providerId) {
		ProviderSwitch state = switches.getOrDefault(providerId, new ProviderSwitch(true, null));
		return new RealtimeProviderSwitchState(providerId, state.enabled(), state.disabledReason());
	}

	private String cleanReason(String reason) {
		return reason == null || reason.isBlank() ? "DISABLED_BY_OPERATOR" : reason.strip();
	}

	private record ProviderSwitch(boolean enabled, String disabledReason) {
	}

	public record RealtimeProviderSwitchState(String providerId, boolean enabled, String disabledReason) {
	}
}
