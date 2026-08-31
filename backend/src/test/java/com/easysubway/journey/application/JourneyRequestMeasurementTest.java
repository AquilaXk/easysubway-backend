package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class JourneyRequestMeasurementTest {

	private static final String REQUEST_ID = "01K1Y000000000000000000000";
	private static final String ROUTE_BUNDLE_SHA = "a".repeat(64);
	private static final String DESCRIPTOR_SHA = "b".repeat(64);
	private static final String RECEIPT_SHA = "c".repeat(64);
	private static final JourneyExecutionResult.ActiveServingIdentity ACTIVE_SERVING =
		new JourneyExecutionResult.ActiveServingIdentity(
			JourneyExecutionResult.ActiveServingIdentity.Status.OBSERVED,
			DESCRIPTOR_SHA, RECEIPT_SHA, "sha256:" + "d".repeat(64), "e".repeat(40), "03:00");
	private static final JourneyExecutionResult.ActiveReadinessIdentity ACTIVE_READINESS =
		new JourneyExecutionResult.ActiveReadinessIdentity(
			1, "journey-v3-active-readiness", "backend-a", "d".repeat(64),
			"sha256:" + "f".repeat(64), "1".repeat(64), "2".repeat(64), ROUTE_BUNDLE_SHA,
			"bundle-1", 1, 1, "Asia/Seoul", "03:00", 1, true, false,
			Instant.parse("2026-08-11T00:10:00Z"), Instant.parse("2026-08-10T23:59:00Z"), "3".repeat(64));
	private static final ActiveJourneySnapshotPort.RequestExecutionIdentity IDENTITY =
		new ActiveJourneySnapshotPort.RequestExecutionIdentity(
			REQUEST_ID, ROUTE_BUNDLE_SHA, 1, ACTIVE_READINESS, ACTIVE_SERVING);

	@Test
	void completesObservedCountersFromOneBoundRequestIdentity() {
		var measurement = boundMeasurement();

		var routeReceipt = JourneyRaptorPort.RouteBoundaryReceipt.observed(1);
		assertThat(measurement.observeRouteBoundary(REQUEST_ID, ROUTE_BUNDLE_SHA, 1, routeReceipt))
			.isEqualTo(new JourneyRequestMeasurement.RouteObservation(IDENTITY, routeReceipt.fallbackUses()));

		assertThat(measurement.complete(request(), snapshot(DESCRIPTOR_SHA, RECEIPT_SHA)))
			.isEqualTo(JourneyExecutionResult.RequestMeasurement.observed(IDENTITY,
				JourneyExecutionResult.BoundaryObservation.observed(1, 1, 1, 1)));
	}

	@Test
	void marksMeasurementUnobservableWhenRegistryOrIdentityBindingIsNotExact() {
		var duplicateRegistryRead = boundMeasurement();
		duplicateRegistryRead.observeSnapshotBoundary(REQUEST_ID, ROUTE_BUNDLE_SHA, 1,
			ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.observed(0, 0, 0));
		assertThat(duplicateRegistryRead.complete(request(), snapshot(DESCRIPTOR_SHA, RECEIPT_SHA)))
			.isEqualTo(JourneyExecutionResult.RequestMeasurement.unobservable());

		var differentRequest = new JourneyRequestMeasurement(REQUEST_ID);
		differentRequest.observeSnapshotBoundary("01K1Y000000000000000000001", ROUTE_BUNDLE_SHA, 1,
			ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.observed(0, 0, 0));
		assertThat(differentRequest.bindActiveIdentity(IDENTITY)).isNull();
		assertThat(differentRequest.observeRouteBoundary(REQUEST_ID, ROUTE_BUNDLE_SHA, 1,
			JourneyRaptorPort.RouteBoundaryReceipt.observed(0))).isNull();
		assertThat(differentRequest.complete(request(), snapshot(DESCRIPTOR_SHA, RECEIPT_SHA)))
			.isEqualTo(JourneyExecutionResult.RequestMeasurement.unobservable());
	}

	@Test
	void marksMeasurementUnobservableWhenAnyBoundaryEventOrServingEvidenceDoesNotMatch() {
		var mismatchedBoundary = boundMeasurement();
		mismatchedBoundary.observeRouteBoundary(REQUEST_ID, "f".repeat(64), 1,
			JourneyRaptorPort.RouteBoundaryReceipt.observed(0));
		assertThat(mismatchedBoundary.observeRouteBoundary(REQUEST_ID, ROUTE_BUNDLE_SHA, 1,
			JourneyRaptorPort.RouteBoundaryReceipt.observed(0))).isNull();
		assertThat(mismatchedBoundary.complete(request(), snapshot(DESCRIPTOR_SHA, RECEIPT_SHA)))
			.isEqualTo(JourneyExecutionResult.RequestMeasurement.unobservable());

		var mismatchedEvidence = boundMeasurement();
		mismatchedEvidence.observeRouteBoundary(REQUEST_ID, ROUTE_BUNDLE_SHA, 1,
			JourneyRaptorPort.RouteBoundaryReceipt.observed(0));
		assertThat(mismatchedEvidence.complete(request(), snapshot("f".repeat(64), RECEIPT_SHA)))
			.isEqualTo(JourneyExecutionResult.RequestMeasurement.unobservable());
	}

	@Test
	void keepsExplicitlyUnobservableMeasurementUnobservable() {
		var measurement = boundMeasurement();
		measurement.markUnobservable();

		assertThat(measurement.bindActiveIdentity(IDENTITY)).isNull();
		assertThat(measurement.complete(request(), snapshot(DESCRIPTOR_SHA, RECEIPT_SHA)))
			.isEqualTo(JourneyExecutionResult.RequestMeasurement.unobservable());
	}

	private static JourneyRequestMeasurement boundMeasurement() {
		var measurement = new JourneyRequestMeasurement(REQUEST_ID);
		measurement.observeSnapshotBoundary(REQUEST_ID, ROUTE_BUNDLE_SHA, 1,
			ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.observed(1, 1, 1));
		assertThat(measurement.bindActiveIdentity(IDENTITY))
			.isEqualTo(new JourneyRequestMeasurement.SnapshotObservation(IDENTITY, 1, 1, 1));
		return measurement;
	}

	private static JourneyRequest request() {
		return new JourneyRequest(REQUEST_ID, "origin", "destination", new JourneyRequest.Departure.Now(),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 0, 1,
			new AtomicBoolean()::get);
	}

	private static ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot(
		String descriptorSha256, String receiptSha256
	) {
		return new ActiveJourneySnapshotPort.ActiveJourneySnapshot(
			"snapshot-1", "bundle-1", ROUTE_BUNDLE_SHA, "timetable-1", "accessibility-1", 1,
			new TestRuntimeView(), Instant.parse("2026-08-11T00:10:00Z"), true,
			ActiveJourneySnapshotPort.ActiveServingEvidence.observed(descriptorSha256, receiptSha256),
			ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.observed(0, 0, 0),
			ActiveJourneySnapshotPort.SnapshotMeasurementReceipt.unobservable());
	}

	private static final class TestRuntimeView implements JourneyRaptorRuntimeView {
		@Override
		public String routeBundleSha256() {
			return ROUTE_BUNDLE_SHA;
		}

		@Override
		public long generation() {
			return 1;
		}
	}
}
