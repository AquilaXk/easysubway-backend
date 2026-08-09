package com.easysubway.journey.bundle;

import java.time.Instant;
import java.util.Objects;

/** Immutable state captured once per request; it is never updated in place. */
public record ActiveRouteBundleSnapshot(
    long generation,
    RouteBundleIdentity identity,
	RouteBundleAdmissionEvidence admissionEvidence,
    RouteBundleRuntimeView runtimeView,
    Instant activatedAt) {

    public ActiveRouteBundleSnapshot {
        if (generation < 1) {
            throw new IllegalArgumentException("generation must be positive");
        }
        identity = Objects.requireNonNull(identity, "identity");
		admissionEvidence = Objects.requireNonNull(admissionEvidence, "admissionEvidence");
        runtimeView = Objects.requireNonNull(runtimeView, "runtimeView");
        activatedAt = Objects.requireNonNull(activatedAt, "activatedAt");
    }
}
