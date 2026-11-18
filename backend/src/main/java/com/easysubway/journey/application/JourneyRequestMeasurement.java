package com.easysubway.journey.application;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Request-scoped event recorder for benchmark-only runtime boundary observations. */
public final class JourneyRequestMeasurement {

	private enum Event {
		ACTIVE_REGISTRY_READ,
		PROVIDER_CALL,
		CACHE_HIT,
		STALE_ARTIFACT_USE,
		DIRECT_RAPTOR,
		FALLBACK_USE
	}

	private final String requestId;
	private final Map<Event, Long> events = new EnumMap<>(Event.class);
	private String routeBundleSha256;
	private long generation;
	private ActiveJourneySnapshotPort.RequestExecutionIdentity identity;
	private boolean unobservable;

	public JourneyRequestMeasurement(String requestId) {
		this.requestId = Objects.requireNonNull(requestId, "requestId");
	}

	public void observeActiveRegistryRead(
		String observedRequestId, String observedRouteBundleSha256, long observedGeneration) {
		if (unobservable || count(Event.ACTIVE_REGISTRY_READ) != 0
			|| !requestId.equals(observedRequestId) || observedRouteBundleSha256 == null
			|| observedGeneration < 1) {
			unobservable = true;
			return;
		}
		routeBundleSha256 = observedRouteBundleSha256;
		generation = observedGeneration;
		record(Event.ACTIVE_REGISTRY_READ);
	}

	public SnapshotObservation bindActiveIdentity(
		ActiveJourneySnapshotPort.RequestExecutionIdentity observedIdentity) {
		if (unobservable || identity != null || count(Event.ACTIVE_REGISTRY_READ) != 1
			|| observedIdentity == null || !requestId.equals(observedIdentity.requestId())
			|| !routeBundleSha256.equals(observedIdentity.routeBundleSha256())
			|| generation != observedIdentity.generation()) {
			unobservable = true;
			return null;
		}
		identity = observedIdentity;
		return new SnapshotObservation(identity, count(Event.PROVIDER_CALL), count(Event.CACHE_HIT),
			count(Event.STALE_ARTIFACT_USE));
	}

	public void observeProviderCall(String observedRequestId, String routeBundleSha256, long generation) {
		recordBound(Event.PROVIDER_CALL, observedRequestId, routeBundleSha256, generation);
	}

	public void observeCacheHit(String observedRequestId, String routeBundleSha256, long generation) {
		recordBound(Event.CACHE_HIT, observedRequestId, routeBundleSha256, generation);
	}

	public void observeStaleArtifactUse(String observedRequestId, String routeBundleSha256, long generation) {
		recordBound(Event.STALE_ARTIFACT_USE, observedRequestId, routeBundleSha256, generation);
	}

	public RouteObservation observeDirectRaptor(
		String observedRequestId, String routeBundleSha256, long generation) {
		if (!matches(observedRequestId, routeBundleSha256, generation)) {
			unobservable = true;
			return null;
		}
		record(Event.DIRECT_RAPTOR);
		return new RouteObservation(identity, count(Event.FALLBACK_USE));
	}

	public void observeFallbackUse(String observedRequestId, String routeBundleSha256, long generation) {
		recordBound(Event.FALLBACK_USE, observedRequestId, routeBundleSha256, generation);
	}

	public void markUnobservable() {
		unobservable = true;
	}

	public JourneyExecutionResult.RequestMeasurement complete(
		JourneyRequest request,
		ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot
	) {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(snapshot, "snapshot");
		if (unobservable || identity == null || count(Event.ACTIVE_REGISTRY_READ) != 1
			|| count(Event.DIRECT_RAPTOR) != 1 || !request.requestId().equals(identity.requestId())
			|| !snapshot.routeBundleSha256().equals(identity.routeBundleSha256())
			|| snapshot.generation() != identity.generation()) {
			return JourneyExecutionResult.RequestMeasurement.unobservable();
		}
		var serving = identity.activeServingIdentity();
		var evidence = snapshot.servingEvidence();
		if (evidence.status() != ActiveJourneySnapshotPort.ActiveServingEvidence.Status.OBSERVED
			|| !evidence.descriptorSha256().equals(serving.descriptorSha256())
			|| !evidence.publicationReceiptSha256().equals(serving.receiptSha256())) {
			return JourneyExecutionResult.RequestMeasurement.unobservable();
		}
		return JourneyExecutionResult.RequestMeasurement.observed(identity,
			JourneyExecutionResult.BoundaryObservation.observed(
				count(Event.PROVIDER_CALL), count(Event.CACHE_HIT), count(Event.STALE_ARTIFACT_USE),
				count(Event.FALLBACK_USE)));
	}

	private void recordBound(Event event, String observedRequestId, String routeBundleSha256, long generation) {
		if (!matches(observedRequestId, routeBundleSha256, generation)) {
			unobservable = true;
			return;
		}
		record(event);
	}

	private boolean matches(String observedRequestId, String routeBundleSha256, long generation) {
		return !unobservable && identity != null && identity.requestId().equals(observedRequestId)
			&& identity.routeBundleSha256().equals(routeBundleSha256) && identity.generation() == generation;
	}

	private void record(Event event) {
		events.merge(event, 1L, Long::sum);
	}

	private long count(Event event) {
		return events.getOrDefault(event, 0L);
	}

	public record SnapshotObservation(ActiveJourneySnapshotPort.RequestExecutionIdentity identity,
		long providerCalls, long cacheHits, long staleArtifactUses) {
		public SnapshotObservation {
			Objects.requireNonNull(identity, "identity");
			if (providerCalls < 0 || cacheHits < 0 || staleArtifactUses < 0) {
				throw new IllegalArgumentException("snapshot observation counters must be nonnegative");
			}
		}
	}

	public record RouteObservation(ActiveJourneySnapshotPort.RequestExecutionIdentity identity,
		long fallbackUses) {
		public RouteObservation {
			Objects.requireNonNull(identity, "identity");
			if (fallbackUses < 0) throw new IllegalArgumentException("route observation counter must be nonnegative");
		}
	}
}
