package com.easysubway.journey.bundle;

import java.time.Instant;
import java.util.Objects;

/** A candidate that has already been verified by the fixture boundary. */
public record VerifiedRouteBundleCandidate(
    RouteBundleIdentity identity,
    RouteBundleRuntimeView runtimeView,
    Instant verifiedAt) {

    public VerifiedRouteBundleCandidate {
        identity = Objects.requireNonNull(identity, "identity");
        runtimeView = Objects.requireNonNull(runtimeView, "runtimeView");
        verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt");
    }
}
