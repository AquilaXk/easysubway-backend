package com.easysubway.journey.bundle;

import com.easysubway.journey.application.ActiveJourneySnapshotPort;
import com.easysubway.journey.application.ActiveJourneySnapshotPort.ActiveServingEvidence;
import com.easysubway.journey.application.JourneyExecutionResult;
import com.easysubway.journey.application.JourneyProfileSnapshotPort;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRaptorRuntimeView;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.JourneyRequestMeasurement;
import com.easysubway.journey.application.ServiceDayResolver;
import com.easysubway.journey.readiness.JourneyReadinessProperties;
import com.easysubway.journey.readiness.JourneyReadinessService;
import java.time.Instant;
import java.util.Objects;

/** Projects one immutable active route-bundle generation into the Journey application boundary. */
public final class RouteBundleActiveJourneySnapshotAdapter
	implements ActiveJourneySnapshotPort, JourneyProfileSnapshotPort {

	private final RouteBundleActivationRegistry registry;
	private final JourneyReadinessProperties readinessProperties;
	private final JourneyReadinessService readinessService;

	public RouteBundleActiveJourneySnapshotAdapter(RouteBundleActivationRegistry registry) {
		this.registry = Objects.requireNonNull(registry, "registry");
		this.readinessProperties = null;
		this.readinessService = null;
	}

	public RouteBundleActiveJourneySnapshotAdapter(RouteBundleActivationRegistry registry,
		JourneyReadinessProperties readinessProperties, JourneyReadinessService readinessService) {
		this.registry = Objects.requireNonNull(registry, "registry");
		this.readinessProperties = Objects.requireNonNull(readinessProperties, "readinessProperties");
		this.readinessService = Objects.requireNonNull(readinessService, "readinessService");
	}

	@Override
	public ActiveJourneySnapshot requireActive(JourneyRequest request, Instant effectiveInstant,
		JourneyRequestMeasurement requestMeasurement) {
		Objects.requireNonNull(request, "request");
		return requireActive(request.requestId(), effectiveInstant, requestMeasurement);
	}

	@Override
	public ActiveJourneySnapshot requireActive(JourneyRaptorQuery query, Instant freshnessReference,
		JourneyRequestMeasurement requestMeasurement) {
		Objects.requireNonNull(query, "query");
		return requireActive(query.requestId(), freshnessReference, requestMeasurement);
	}

	private ActiveJourneySnapshot requireActive(String requestId, Instant freshnessReference,
		JourneyRequestMeasurement requestMeasurement) {
		Objects.requireNonNull(freshnessReference, "freshnessReference");
		Objects.requireNonNull(requestMeasurement, "requestMeasurement");
		var active = registry.activeSnapshot(requestId, requestMeasurement);
		if (!(active.runtimeView() instanceof JourneyRaptorRuntimeView runtimeView)) {
			throw new IllegalStateException("active route-bundle runtime is not a Journey RAPTOR runtime");
		}

		var identity = active.identity();
		var manifestSha256 = active.admissionEvidence().manifestSha256();
		var fresh = !freshnessReference.isBefore(identity.activeFromInstant())
			&& freshnessReference.isBefore(identity.freshUntilInstant());
		var measurement = measurement(active, requestId, manifestSha256, fresh, requestMeasurement);
		return new ActiveJourneySnapshot(
			manifestSha256 + ":" + active.generation(),
			identity.bundleId(),
			manifestSha256,
			identity.timetableSha256(),
			identity.accessibilitySha256(),
			active.generation(),
			runtimeView,
			identity.freshUntilInstant(),
			fresh,
			projectServingEvidence(active.servingEvidence()),
			fresh ? ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.observed(0, 0)
				: ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.unobservable(), measurement);
	}

	private ActiveJourneySnapshotPort.SnapshotMeasurementReceipt measurement(
		ActiveRouteBundleSnapshot active, String requestId, String manifestSha256, boolean fresh,
		JourneyRequestMeasurement requestMeasurement) {
		if (!fresh || readinessProperties == null || readinessService == null
			|| readinessProperties.deploymentRevision() == null
			|| active.servingEvidence().status() != RouteBundleServingEvidence.Status.OBSERVED) {
			requestMeasurement.markUnobservable();
			return ActiveJourneySnapshotPort.SnapshotMeasurementReceipt.unobservable();
		}
		try {
			var activeServingIdentity = servingIdentity(active);
			var activeReadinessIdentity = activeReadinessIdentity(readinessService.active(active));
			var identity = new ActiveJourneySnapshotPort.RequestExecutionIdentity(
				requestId, manifestSha256, active.generation(), activeReadinessIdentity,
				activeServingIdentity);
			var observation = requestMeasurement.bindActiveIdentity(identity);
			return observation == null ? ActiveJourneySnapshotPort.SnapshotMeasurementReceipt.unobservable()
				: ActiveJourneySnapshotPort.SnapshotMeasurementReceipt.observed(observation);
		} catch (IllegalArgumentException | IllegalStateException exception) {
			requestMeasurement.markUnobservable();
			return ActiveJourneySnapshotPort.SnapshotMeasurementReceipt.unobservable();
		}
	}

	private static JourneyExecutionResult.ActiveReadinessIdentity activeReadinessIdentity(
		JourneyReadinessService.ActiveReadiness readiness) {
		return new JourneyExecutionResult.ActiveReadinessIdentity(
			readiness.schemaVersion(), readiness.artifactKind(), readiness.instanceId(),
			readiness.releaseTupleSha256(), readiness.backendImageDigest(), readiness.backendConfigSha256(),
			readiness.journeyContractSha256(), readiness.routeBundleManifestSha256(), readiness.bundleId(),
			readiness.bundleReleaseSequence(), readiness.generation(), readiness.serviceTimezone(),
			readiness.serviceDayCutoff(), readiness.trafficGeneration(), readiness.servingReady(),
			readiness.draining(), readiness.freshUntil(), readiness.activatedAt(), readiness.evidenceSha256());
	}

	private JourneyExecutionResult.ActiveServingIdentity servingIdentity(ActiveRouteBundleSnapshot active) {
		if (readinessProperties == null || readinessProperties.deploymentRevision() == null
			|| active.servingEvidence().status() != RouteBundleServingEvidence.Status.OBSERVED) {
			return JourneyExecutionResult.ActiveServingIdentity.unobservable();
		}
		return new JourneyExecutionResult.ActiveServingIdentity(
			JourneyExecutionResult.ActiveServingIdentity.Status.OBSERVED,
			active.servingEvidence().descriptorSha256(), active.servingEvidence().publicationReceiptSha256(),
			"sha256:" + readinessProperties.releaseTupleSha256(), readinessProperties.deploymentRevision(),
			ServiceDayResolver.CUTOFF_LOCAL_TIME);
	}

	private static ActiveServingEvidence projectServingEvidence(RouteBundleServingEvidence evidence) {
		return switch (evidence.status()) {
			case OBSERVED -> ActiveServingEvidence.observed(
				evidence.descriptorSha256(), evidence.publicationReceiptSha256());
			case UNOBSERVABLE -> ActiveServingEvidence.unobservable();
		};
	}
}
