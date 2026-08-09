package com.easysubway.journey.bundle;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fixture-only candidate/active state machine. It intentionally owns neither
 * loading nor verification; callers provide an already verified candidate.
 */
public final class RouteBundleActivationRegistry {

    private final Clock clock;
    private final AtomicReference<State> state = new AtomicReference<>(new State(0, null, null));

    public RouteBundleActivationRegistry(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void stage(VerifiedRouteBundleCandidate candidate, long expectedGeneration) {
        candidate = Objects.requireNonNull(candidate, "candidate");
        while (true) {
            var current = state.get();
            requireExpectedGeneration(current, expectedGeneration);
            requireCandidateIsCurrent(candidate, clock.instant());
            if (current.active != null && current.active.identity().manifestSha256()
                .equals(candidate.identity().manifestSha256())) {
                throw failure(RouteBundleActivationException.Reason.CANDIDATE_ALREADY_ACTIVE);
            }
            if (current.staged != null) {
                if (current.staged.identity().manifestSha256().equals(candidate.identity().manifestSha256())) {
                    throw failure(RouteBundleActivationException.Reason.CANDIDATE_ALREADY_STAGED);
                }
                throw failure(RouteBundleActivationException.Reason.ACTIVATION_CONFLICT);
            }
            if (state.compareAndSet(current, new State(current.generation, current.active, candidate))) {
                return;
            }
        }
    }

    public ActiveRouteBundleSnapshot activate(String candidateManifestSha256, long expectedGeneration) {
        while (true) {
            var current = state.get();
            requireExpectedGeneration(current, expectedGeneration);
            var candidate = current.staged;
            if (candidate == null) {
                throw failure(RouteBundleActivationException.Reason.BUNDLE_UNAVAILABLE);
            }
            if (!candidate.identity().manifestSha256().equals(candidateManifestSha256)) {
                throw failure(RouteBundleActivationException.Reason.CANDIDATE_IDENTITY_MISMATCH);
            }
            var activatedAt = clock.instant();
            requireCandidateIsCurrent(candidate, activatedAt);
            var snapshot = new ActiveRouteBundleSnapshot(
                current.generation + 1, candidate.identity(), candidate.runtimeView(), activatedAt);
            if (state.compareAndSet(current, new State(snapshot.generation(), snapshot, null))) {
                return snapshot;
            }
        }
    }

    public ActiveRouteBundleSnapshot activeSnapshot() {
        var active = state.get().active;
        if (active == null) {
            throw failure(RouteBundleActivationException.Reason.BUNDLE_UNAVAILABLE);
        }
        return active;
    }

    private static void requireExpectedGeneration(State state, long expectedGeneration) {
        if (expectedGeneration < 0 || state.generation != expectedGeneration) {
            throw failure(RouteBundleActivationException.Reason.ACTIVATION_CONFLICT);
        }
    }

    private static void requireCandidateIsCurrent(VerifiedRouteBundleCandidate candidate, Instant now) {
        var identity = candidate.identity();
        if (candidate.verifiedAt().isAfter(now) || identity.activeFrom().isAfter(now)) {
            throw failure(RouteBundleActivationException.Reason.BUNDLE_FUTURE);
        }
        if (!identity.freshUntil().isAfter(now)) {
            throw failure(RouteBundleActivationException.Reason.BUNDLE_STALE);
        }
    }

    private static RouteBundleActivationException failure(RouteBundleActivationException.Reason reason) {
        return new RouteBundleActivationException(reason);
    }

    private record State(
        long generation,
        ActiveRouteBundleSnapshot active,
        VerifiedRouteBundleCandidate staged) {
    }
}
