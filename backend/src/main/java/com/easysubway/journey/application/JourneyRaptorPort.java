package com.easysubway.journey.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@FunctionalInterface
public interface JourneyRaptorPort {
	PlanResult plan(
		JourneyRequest request,
		ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot,
		Instant effectiveInstant,
		JourneyRealtimePort.RealtimeObservation realtimeOrNull
	);

	record PlanResult(
		String queryId,
		List<JourneyCandidate> candidates,
		ScanMetrics scanMetrics,
		RouteBoundaryReceipt boundaryReceipt,
		RouteMeasurementReceipt measurementReceipt
	) {
		public PlanResult {
			Objects.requireNonNull(queryId, "queryId");
			if (queryId.isBlank()) throw new IllegalArgumentException("queryId must not be blank");
			candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
			scanMetrics = Objects.requireNonNull(scanMetrics, "scanMetrics");
			boundaryReceipt = Objects.requireNonNull(boundaryReceipt, "boundaryReceipt");
			measurementReceipt = Objects.requireNonNull(measurementReceipt, "measurementReceipt");
		}

		public PlanResult(String queryId, List<JourneyCandidate> candidates, ScanMetrics scanMetrics,
			RouteBoundaryReceipt boundaryReceipt) {
			this(queryId, candidates, scanMetrics, boundaryReceipt, RouteMeasurementReceipt.unobservable());
		}
	}

	/** Immutable evidence emitted by the route-planning boundary for this plan result. */
	record RouteBoundaryReceipt(Status status, Long fallbackUses) {
		enum Status { OBSERVED, UNOBSERVABLE }

		public RouteBoundaryReceipt {
			status = Objects.requireNonNull(status, "status");
			if (status == Status.UNOBSERVABLE) {
				if (fallbackUses != null) {
					throw new IllegalArgumentException("unobservable route receipt must not have counters");
				}
			} else if (fallbackUses == null || fallbackUses < 0) {
				throw new IllegalArgumentException("observed route receipt is incomplete");
			}
		}

		public static RouteBoundaryReceipt observed(long fallbackUses) {
			return new RouteBoundaryReceipt(Status.OBSERVED, fallbackUses);
		}

		public static RouteBoundaryReceipt unobservable() {
			return new RouteBoundaryReceipt(Status.UNOBSERVABLE, null);
		}
	}

	record RouteMeasurementReceipt(Status status, ActiveJourneySnapshotPort.RequestExecutionIdentity identity,
		Long fallbackUses) {
		enum Status { OBSERVED, UNOBSERVABLE }

		public RouteMeasurementReceipt {
			status = Objects.requireNonNull(status, "status");
			if (status == Status.OBSERVED && (identity == null || fallbackUses == null || fallbackUses < 0)) {
				throw new IllegalArgumentException("observed route measurement is incomplete");
			}
			if (status == Status.UNOBSERVABLE && (identity != null || fallbackUses != null)) {
				throw new IllegalArgumentException("unobservable route measurement must not have values");
			}
		}

		public static RouteMeasurementReceipt observed(
			ActiveJourneySnapshotPort.RequestExecutionIdentity identity, long fallbackUses) {
			return new RouteMeasurementReceipt(Status.OBSERVED, identity, fallbackUses);
		}

		public static RouteMeasurementReceipt unobservable() {
			return new RouteMeasurementReceipt(Status.UNOBSERVABLE, null, null);
		}
	}

	record ScanMetrics(int expandedRoutes, int expandedTrips, int expandedTransfers) {
		public ScanMetrics {
			if (expandedRoutes < 0 || expandedTrips < 0 || expandedTransfers < 0) {
				throw new IllegalArgumentException("scan metrics must not be negative");
			}
		}
	}
}
