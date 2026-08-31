package com.easysubway.journey.application;

import java.util.Objects;

/** Request-scoped event recorder for benchmark-only runtime boundary observations. */
public final class JourneyRequestMeasurement {

	private final String requestId;
	private String routeBundleSha256;
	private long generation;
	private ActiveJourneySnapshotPort.RequestExecutionIdentity identity;
	private ActiveJourneySnapshotPort.SnapshotBoundaryReceipt snapshotReceipt;
	private JourneyRaptorPort.RouteBoundaryReceipt routeReceipt;
	private boolean unobservable;

	public JourneyRequestMeasurement(String requestId) {
		this.requestId = Objects.requireNonNull(requestId, "requestId");
	}

	public void observeSnapshotBoundary(String observedRequestId, String observedRouteBundleSha256,
		long observedGeneration, ActiveJourneySnapshotPort.SnapshotBoundaryReceipt receipt) {
		if (unobservable || snapshotReceipt != null
			|| !requestId.equals(observedRequestId) || observedRouteBundleSha256 == null
			|| observedGeneration < 1 || receipt == null
			|| receipt.status() != ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.Status.OBSERVED) {
			unobservable = true;
			return;
		}
		routeBundleSha256 = observedRouteBundleSha256;
		generation = observedGeneration;
		snapshotReceipt = receipt;
	}

	public SnapshotObservation bindActiveIdentity(
		ActiveJourneySnapshotPort.RequestExecutionIdentity observedIdentity) {
		if (unobservable || identity != null || snapshotReceipt == null
			|| observedIdentity == null || !requestId.equals(observedIdentity.requestId())
			|| !routeBundleSha256.equals(observedIdentity.routeBundleSha256())
			|| generation != observedIdentity.generation()) {
			unobservable = true;
			return null;
		}
		identity = observedIdentity;
		return new SnapshotObservation(identity, snapshotReceipt.providerCalls(), snapshotReceipt.cacheHits(),
			snapshotReceipt.staleArtifactUses());
	}

	public RouteObservation observeRouteBoundary(String observedRequestId, String routeBundleSha256,
		long generation, JourneyRaptorPort.RouteBoundaryReceipt receipt) {
		if (!matches(observedRequestId, routeBundleSha256, generation) || routeReceipt != null
			|| receipt == null || receipt.status() != JourneyRaptorPort.RouteBoundaryReceipt.Status.OBSERVED) {
			unobservable = true;
			return null;
		}
		routeReceipt = receipt;
		return new RouteObservation(identity, receipt.fallbackUses());
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
		if (unobservable || identity == null || snapshotReceipt == null || routeReceipt == null
			|| !request.requestId().equals(identity.requestId())
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
				snapshotReceipt.providerCalls(), snapshotReceipt.cacheHits(), snapshotReceipt.staleArtifactUses(),
				routeReceipt.fallbackUses()));
	}

	private boolean matches(String observedRequestId, String routeBundleSha256, long generation) {
		return !unobservable && identity != null && identity.requestId().equals(observedRequestId)
			&& identity.routeBundleSha256().equals(routeBundleSha256) && identity.generation() == generation;
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
