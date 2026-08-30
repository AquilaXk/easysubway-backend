package com.easysubway.journey.application;

/** Request-local evidence of the boundaries crossed by one Journey execution. */
public final class JourneyBoundaryObserver {

	private Long providerCalls;
	private Long cacheHits;
	private Long staleArtifactUses;
	private Long fallbackUses;
	private boolean realtimeRequested;

	public void providerBypassedForTimetable() {
		reportProviderCalls(0);
	}

	public void providerInvokedForRealtime() {
		reportProviderCalls(1);
		realtimeRequested = true;
	}

	public void directRegistryReadSucceeded() {
		reportCacheHits(0);
	}

	public void freshnessAcceptedWithoutStaleArtifact() {
		reportStaleArtifactUses(0);
	}

	public void noFallbackSelectedAtRouteBoundary() {
		reportFallbackUses(0);
	}

	private void reportProviderCalls(long value) {
		providerCalls = report(providerCalls, value, "provider");
	}

	private void reportCacheHits(long value) {
		cacheHits = report(cacheHits, value, "cache");
	}

	private void reportStaleArtifactUses(long value) {
		staleArtifactUses = report(staleArtifactUses, value, "stale artifact");
	}

	private void reportFallbackUses(long value) {
		fallbackUses = report(fallbackUses, value, "fallback");
	}

	private static Long report(Long prior, long value, String boundary) {
		if (prior != null) throw new IllegalStateException(boundary + " boundary was already reported");
		if (value < 0) throw new IllegalArgumentException(boundary + " boundary count must be nonnegative");
		return value;
	}

	public JourneyExecutionResult.BoundaryObservation completeTimetable() {
		if (realtimeRequested || providerCalls == null || cacheHits == null || staleArtifactUses == null
			|| fallbackUses == null) {
			return JourneyExecutionResult.BoundaryObservation.unobservable();
		}
		return JourneyExecutionResult.BoundaryObservation.observed(providerCalls, cacheHits,
			staleArtifactUses, fallbackUses);
	}

	public JourneyExecutionResult.BoundaryObservation completeRealtimeWithoutReceipt() {
		return JourneyExecutionResult.BoundaryObservation.unobservable();
	}
}
