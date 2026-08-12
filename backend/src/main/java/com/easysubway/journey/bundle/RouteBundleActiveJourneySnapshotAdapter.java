package com.easysubway.journey.bundle;

import com.easysubway.journey.application.ActiveJourneySnapshotPort;
import com.easysubway.journey.application.JourneyRaptorRuntimeView;
import java.time.Instant;
import java.util.Objects;

/** Projects one immutable active route-bundle generation into the Journey application boundary. */
public final class RouteBundleActiveJourneySnapshotAdapter implements ActiveJourneySnapshotPort {

	private final RouteBundleActivationRegistry registry;

	public RouteBundleActiveJourneySnapshotAdapter(RouteBundleActivationRegistry registry) {
		this.registry = Objects.requireNonNull(registry, "registry");
	}

	@Override
	public ActiveJourneySnapshot requireActive(Instant effectiveInstant) {
		Objects.requireNonNull(effectiveInstant, "effectiveInstant");
		var active = registry.activeSnapshot();
		if (!(active.runtimeView() instanceof JourneyRaptorRuntimeView runtimeView)) {
			throw new IllegalStateException("active route-bundle runtime is not a Journey RAPTOR runtime");
		}

		var identity = active.identity();
		var manifestSha256 = active.admissionEvidence().manifestSha256();
		var fresh = !effectiveInstant.isBefore(identity.activeFromInstant())
			&& effectiveInstant.isBefore(identity.freshUntilInstant());
		return new ActiveJourneySnapshot(
			manifestSha256 + ":" + active.generation(),
			identity.bundleId(),
			manifestSha256,
			identity.timetableSha256(),
			identity.accessibilitySha256(),
			active.generation(),
			runtimeView,
			identity.freshUntilInstant(),
			fresh);
	}
}
