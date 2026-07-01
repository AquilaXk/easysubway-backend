package com.easysubway.realtime.application;

public record RealtimeProviderHealthSnapshot(
	String providerId,
	boolean providerEnabled,
	String disabledReason,
	long providerCallCount,
	long providerTimeoutCount,
	long providerQuotaExceededCount,
	long providerEmptyResultCount,
	double freshResultRatio,
	double staleResultRatio,
	double unsupportedRatio,
	long averageProviderLatencyMs
) {
}
