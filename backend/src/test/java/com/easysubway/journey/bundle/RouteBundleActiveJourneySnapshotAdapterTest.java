package com.easysubway.journey.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.easysubway.journey.application.ActiveJourneySnapshotPort;
import com.easysubway.journey.application.ActiveJourneySnapshotPort.ActiveServingEvidence;
import com.easysubway.journey.application.ActiveJourneySnapshotPort.SnapshotBoundaryReceipt;
import com.easysubway.journey.application.ActiveJourneySnapshotPort.SnapshotMeasurementReceipt;
import com.easysubway.journey.application.JourneyExecutionResult;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRaptorRuntimeView;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.JourneyRequestMeasurement;
import com.easysubway.journey.application.ServiceDayResolver;
import com.easysubway.journey.readiness.JourneyReadinessProperties;
import com.easysubway.journey.readiness.JourneyReadinessService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;

class RouteBundleActiveJourneySnapshotAdapterTest {

	private static final Instant NOW = Instant.parse("2026-08-12T02:30:00Z");
	private static final Instant ACTIVE_FROM = Instant.parse("2026-08-12T02:00:00Z");
	private static final Instant FRESH_UNTIL = Instant.parse("2026-08-12T04:00:00Z");
	private static final String MANIFEST_SHA = "a".repeat(64);
	private static final String TIMETABLE_SHA = "b".repeat(64);
	private static final String ACCESSIBILITY_SHA = "c".repeat(64);
	private static final String DESCRIPTOR_SHA = "d".repeat(64);
	private static final String RECEIPT_SHA = "e".repeat(64);
	private static final String RELEASE_TUPLE_SHA = "f".repeat(64);
	private static final String DEPLOYMENT_REVISION = "1".repeat(40);
	private static final String REQUEST_ID = "01K1Y000000000000000000000";

	@Test
	void mapsOneExactActiveRegistrySnapshotToTheJourneyPort() {
		var clock = new CountingClock(NOW);
		var runtime = new TestRuntime(MANIFEST_SHA, 1);
		var registry = activeRegistry(clock, runtime);
		var adapter = new RouteBundleActiveJourneySnapshotAdapter(registry);

		var snapshot = requireActive(adapter, NOW);

		assertThat(snapshot.identity()).isEqualTo(MANIFEST_SHA + ":1");
		assertThat(snapshot.routeBundleId()).isEqualTo("capital-v1");
		assertThat(snapshot.routeBundleSha256()).isEqualTo(MANIFEST_SHA);
		assertThat(snapshot.timetableSnapshotId()).isEqualTo(TIMETABLE_SHA);
		assertThat(snapshot.accessibilitySnapshotId()).isEqualTo(ACCESSIBILITY_SHA);
		assertThat(snapshot.generation()).isEqualTo(1);
		assertThat(snapshot.runtimeView()).isSameAs(runtime);
		assertThat(snapshot.validUntil()).isEqualTo(FRESH_UNTIL);
		assertThat(snapshot.fresh()).isTrue();
		assertThat(snapshot.servingEvidence()).isEqualTo(ActiveServingEvidence.unobservable());
		assertThat(snapshot.boundaryReceipt()).isEqualTo(SnapshotBoundaryReceipt.observed(0, 0));
		assertThat(clock.instantCalls()).isEqualTo(3);
	}

	@Test
	void projectsCapturedObservedServingEvidenceWithTheActiveGeneration() {
		var servingEvidence = RouteBundleServingEvidence.observed(DESCRIPTOR_SHA, RECEIPT_SHA);
		var registry = activeRegistry(Clock.fixed(NOW, ZoneOffset.UTC), new TestRuntime(MANIFEST_SHA, 1),
			servingEvidence);

		var snapshot = requireActive(new RouteBundleActiveJourneySnapshotAdapter(registry), NOW);

		assertThat(snapshot.generation()).isOne();
		assertThat(snapshot.servingEvidence()).isEqualTo(ActiveServingEvidence.observed(
			DESCRIPTOR_SHA, RECEIPT_SHA));
	}

	@Test
	void capturesOneRequestBoundActiveIdentityAndActualRegistryBoundaryCounts() {
		var registry = activeRegistry(Clock.fixed(NOW, ZoneOffset.UTC), new TestRuntime(MANIFEST_SHA, 1),
			RouteBundleServingEvidence.observed(DESCRIPTOR_SHA, RECEIPT_SHA));
		var adapter = measurementAdapter(registry, DEPLOYMENT_REVISION);

		var measurement = requireActive(adapter, NOW).measurementReceipt();

		assertThat(measurement.status()).isEqualTo(SnapshotMeasurementReceipt.Status.OBSERVED);
		assertThat(measurement.providerCalls()).isZero();
		assertThat(measurement.cacheHits()).isZero();
		assertThat(measurement.staleArtifactUses()).isZero();
		assertThat(measurement.identity().requestId()).isEqualTo(REQUEST_ID);
		assertThat(measurement.identity().routeBundleSha256()).isEqualTo(MANIFEST_SHA);
		assertThat(measurement.identity().generation()).isOne();
		assertThat(measurement.identity().activeReadinessIdentity()).satisfies(readiness -> {
			assertThat(readiness.routeBundleManifestSha256()).isEqualTo(MANIFEST_SHA);
			assertThat(readiness.releaseTupleSha256()).isEqualTo(RELEASE_TUPLE_SHA);
			assertThat(readiness.generation()).isOne();
			assertThat(readiness.servingReady()).isTrue();
			assertThat(readiness.draining()).isFalse();
		});
		assertThat(measurement.identity().activeServingIdentity())
			.isEqualTo(new JourneyExecutionResult.ActiveServingIdentity(
				JourneyExecutionResult.ActiveServingIdentity.Status.OBSERVED,
				DESCRIPTOR_SHA,
				RECEIPT_SHA,
				"sha256:" + RELEASE_TUPLE_SHA,
				DEPLOYMENT_REVISION,
				ServiceDayResolver.CUTOFF_LOCAL_TIME));
	}

	@Test
	void keepsUnconfiguredAndStaleSnapshotMeasurementsUnobservable() {
		var registry = activeRegistry(Clock.fixed(NOW, ZoneOffset.UTC), new TestRuntime(MANIFEST_SHA, 1),
			RouteBundleServingEvidence.observed(DESCRIPTOR_SHA, RECEIPT_SHA));
		var adapter = measurementAdapter(registry, DEPLOYMENT_REVISION);

		assertThat(requireActive(new RouteBundleActiveJourneySnapshotAdapter(registry), NOW).measurementReceipt())
			.isEqualTo(SnapshotMeasurementReceipt.unobservable());
		assertThat(requireActive(adapter, FRESH_UNTIL).measurementReceipt())
			.isEqualTo(SnapshotMeasurementReceipt.unobservable());
	}

	@Test
	void keepsMeasurementUnobservableWithoutCompleteServingAndDeploymentIdentity() {
		var observedRegistry = activeRegistry(
			Clock.fixed(NOW, ZoneOffset.UTC),
			new TestRuntime(MANIFEST_SHA, 1),
			RouteBundleServingEvidence.observed(DESCRIPTOR_SHA, RECEIPT_SHA));
		for (String revision : new String[] {null, "", "bad", "A".repeat(40), "1".repeat(39)}) {
			var adapter = measurementAdapter(observedRegistry, revision);
			assertThat(requireActive(adapter, NOW).measurementReceipt())
				.isEqualTo(SnapshotMeasurementReceipt.unobservable());
		}

		var unobservedRegistry = activeRegistry(
			Clock.fixed(NOW, ZoneOffset.UTC), new TestRuntime(MANIFEST_SHA, 1));
		var adapter = measurementAdapter(unobservedRegistry, DEPLOYMENT_REVISION);
		assertThat(requireActive(adapter, NOW).measurementReceipt())
			.isEqualTo(SnapshotMeasurementReceipt.unobservable());
	}

	@Test
	void keepsMeasurementUnobservableWhenActiveReadinessIsNotServingTraffic() {
		var registry = activeRegistry(
			Clock.fixed(NOW, ZoneOffset.UTC),
			new TestRuntime(MANIFEST_SHA, 1),
			RouteBundleServingEvidence.observed(DESCRIPTOR_SHA, RECEIPT_SHA));
		var properties = readinessProperties(DEPLOYMENT_REVISION);
		var availability = mock(ApplicationAvailability.class);
		when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
		var adapter = new RouteBundleActiveJourneySnapshotAdapter(registry, properties,
			new JourneyReadinessService(registry, properties, availability));

		assertThat(requireActive(adapter, NOW).measurementReceipt())
			.isEqualTo(SnapshotMeasurementReceipt.unobservable());
	}

	@Test
	void marksTheSnapshotReceiptUnobservableWhenFreshnessCannotBeProved() {
		var adapter = new RouteBundleActiveJourneySnapshotAdapter(
			activeRegistry(Clock.fixed(NOW, ZoneOffset.UTC), new TestRuntime(MANIFEST_SHA, 1)));

		assertThat(requireActive(adapter, FRESH_UNTIL).boundaryReceipt())
			.isEqualTo(SnapshotBoundaryReceipt.unobservable());
	}

	@Test
	void usesInclusiveActiveFromAndExclusiveFreshUntilForTheEffectiveInstant() {
		var registry = activeRegistry(Clock.fixed(NOW, ZoneOffset.UTC), new TestRuntime(MANIFEST_SHA, 1));
		var adapter = new RouteBundleActiveJourneySnapshotAdapter(registry);

		assertThat(requireActive(adapter, ACTIVE_FROM).fresh()).isTrue();
		assertThat(requireActive(adapter, FRESH_UNTIL).fresh()).isFalse();
		assertThat(requireActive(adapter, ACTIVE_FROM.minusNanos(1)).fresh()).isFalse();
	}

	@Test
	void mapsTheProfileQueryWithItsOwnFreshnessReference() {
		var adapter = new RouteBundleActiveJourneySnapshotAdapter(
			activeRegistry(Clock.fixed(NOW, ZoneOffset.UTC), new TestRuntime(MANIFEST_SHA, 1)));
		var query = new JourneyRaptorQuery(REQUEST_ID, "origin", "destination",
			new JourneyRaptorQuery.DepartBetween(ACTIVE_FROM, FRESH_UNTIL),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 1, 1, () -> false);

		var snapshot = adapter.requireActive(query, FRESH_UNTIL,
			new JourneyRequestMeasurement(REQUEST_ID));

		assertThat(snapshot.routeBundleSha256()).isEqualTo(MANIFEST_SHA);
		assertThat(snapshot.fresh()).isFalse();
		assertThat(snapshot.boundaryReceipt()).isEqualTo(SnapshotBoundaryReceipt.unobservable());
	}

	@Test
	void failsClosedWhenNoActiveSnapshotExists() {
		var adapter = new RouteBundleActiveJourneySnapshotAdapter(
			new RouteBundleActivationRegistry(Clock.fixed(NOW, ZoneOffset.UTC)));

		assertThatThrownBy(() -> requireActive(adapter, NOW))
			.isInstanceOf(RouteBundleActivationException.class)
			.extracting(error -> ((RouteBundleActivationException) error).reason())
			.isEqualTo(RouteBundleActivationException.Reason.BUNDLE_UNAVAILABLE);
	}

	@Test
	void failsClosedWhenTheActiveRuntimeIsNotAJourneyRaptorRuntime() {
		var registry = activeRegistry(Clock.fixed(NOW, ZoneOffset.UTC), new OtherRuntime());

		assertThatThrownBy(() -> requireActive(new RouteBundleActiveJourneySnapshotAdapter(registry), NOW))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("active route-bundle runtime is not a Journey RAPTOR runtime");
	}

	@Test
	void failsClosedWhenTheRuntimeDigestOrGenerationDoesNotMatchTheActiveSnapshot() {
		for (var runtime : new TestRuntime[] {
			new TestRuntime("d".repeat(64), 1),
			new TestRuntime(MANIFEST_SHA, 2)
		}) {
			var registry = activeRegistry(Clock.fixed(NOW, ZoneOffset.UTC), runtime);

			assertThatThrownBy(() -> requireActive(new RouteBundleActiveJourneySnapshotAdapter(registry), NOW))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	private static RouteBundleActivationRegistry activeRegistry(Clock clock, RouteBundleRuntimeView runtime) {
		return activeRegistry(clock, runtime, RouteBundleServingEvidence.unobservable());
	}

	private static ActiveJourneySnapshotPort.ActiveJourneySnapshot requireActive(
		RouteBundleActiveJourneySnapshotAdapter adapter, Instant effectiveInstant) {
		return adapter.requireActive(request(), effectiveInstant, new JourneyRequestMeasurement(REQUEST_ID));
	}

	private static RouteBundleActivationRegistry activeRegistry(
		Clock clock, RouteBundleRuntimeView runtime, RouteBundleServingEvidence servingEvidence) {
		var registry = new RouteBundleActivationRegistry(clock);
		registry.stage(new VerifiedRouteBundleCandidate(
			identity(),
			new RouteBundleAdmissionEvidence(
				MANIFEST_SHA,
				"final-evidence",
				"promotion-evidence",
				"publication-receipt",
				"activation-request"),
			servingEvidence,
			runtime,
			NOW.minusSeconds(1)), 0);
		registry.activate(MANIFEST_SHA, 0);
		return registry;
	}

	private static RouteBundleIdentity identity() {
		return new RouteBundleIdentity(
			1,
			"server-route-bundle",
			"capital-v1",
			1,
			"0".repeat(64),
			"1".repeat(64),
			"2".repeat(64),
			TIMETABLE_SHA,
			ACCESSIBILITY_SHA,
			"3".repeat(64),
			"4".repeat(64),
			"5".repeat(64),
			"Asia/Seoul",
			"2026-08-12T11:00:00.000+09:00",
			"2026-08-12T13:00:00.000+09:00",
			new RouteBundleIdentity.SchemaCompatibility(3, 3),
			"launch-key",
			new RouteBundleIdentity.Signature("rsa-sha256-server-route-bundle-v1", "AQID"));
	}

	private static JourneyReadinessProperties readinessProperties(String deploymentRevision) {
		return new JourneyReadinessProperties(
			"readiness-token-with-at-least-32-characters",
			"backend-a",
			RELEASE_TUPLE_SHA,
			"sha256:" + "6".repeat(64),
			"7".repeat(64),
			"8".repeat(64),
			deploymentRevision,
			1);
	}

	private static RouteBundleActiveJourneySnapshotAdapter measurementAdapter(
		RouteBundleActivationRegistry registry, String deploymentRevision) {
		var properties = readinessProperties(deploymentRevision);
		var availability = mock(ApplicationAvailability.class);
		when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
		return new RouteBundleActiveJourneySnapshotAdapter(registry, properties,
			new JourneyReadinessService(registry, properties, availability));
	}

	private static JourneyRequest request() {
		return new JourneyRequest(
			REQUEST_ID,
			"origin",
			"destination",
			new JourneyRequest.Departure.Now(),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE,
			1,
			1,
			() -> false);
	}

	private record TestRuntime(String routeBundleSha256, long generation)
		implements RouteBundleRuntimeView, JourneyRaptorRuntimeView {
	}

	private record OtherRuntime() implements RouteBundleRuntimeView {
	}

	private static final class CountingClock extends Clock {
		private final Instant instant;
		private int instantCalls;

		private CountingClock(Instant instant) {
			this.instant = instant;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			instantCalls++;
			return instant;
		}

		private int instantCalls() {
			return instantCalls;
		}
	}
}
