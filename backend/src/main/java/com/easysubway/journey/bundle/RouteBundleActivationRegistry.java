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
		Instant now = clock.instant();
		requireCandidateIsCurrent(candidate, now);
		while (true) {
			var current = state.get();
			requireExpectedGeneration(current, expectedGeneration);
			if (current.active != null && current.active.admissionEvidence().manifestSha256()
				.equals(candidate.admissionEvidence().manifestSha256())) {
				throw failure(RouteBundleActivationException.Reason.CANDIDATE_ALREADY_ACTIVE);
			}
			if (current.staged != null) {
				if (isStale(current.staged, now)) {
					var replacement = new State(current.generation, current.active, candidate);
					if (state.compareAndSet(current, replacement)) {
						return;
					}
					continue;
				}
				throw failure(RouteBundleActivationException.Reason.CANDIDATE_ALREADY_STAGED);
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
				if (current.active != null && current.active.admissionEvidence().manifestSha256()
					.equals(candidateManifestSha256)) {
					throw failure(RouteBundleActivationException.Reason.CANDIDATE_ALREADY_ACTIVE);
				}
				throw failure(RouteBundleActivationException.Reason.CANDIDATE_NOT_STAGED);
			}
			if (!candidate.admissionEvidence().manifestSha256().equals(candidateManifestSha256)) {
				throw failure(RouteBundleActivationException.Reason.CANDIDATE_IDENTITY_MISMATCH);
			}
			var activatedAt = clock.instant();
			requireCandidateIsCurrent(candidate, activatedAt);
			var snapshot = new ActiveRouteBundleSnapshot(
				current.generation + 1,
				candidate.identity(),
				candidate.admissionEvidence(),
				candidate.runtimeView(),
				activatedAt);
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
		requireIdentityIsCurrent(active.identity(), clock.instant());
		return active;
	}

	private static void requireExpectedGeneration(State state, long expectedGeneration) {
		if (expectedGeneration < 0 || state.generation != expectedGeneration) {
			throw failure(RouteBundleActivationException.Reason.ACTIVATION_CONFLICT);
		}
	}

	private static void requireCandidateIsCurrent(VerifiedRouteBundleCandidate candidate, Instant now) {
		if (candidate.verifiedAt().isAfter(now)) {
			throw failure(RouteBundleActivationException.Reason.BUNDLE_FUTURE);
		}
		requireIdentityIsCurrent(candidate.identity(), now);
	}

	private static void requireIdentityIsCurrent(RouteBundleIdentity identity, Instant now) {
		if (now.isBefore(identity.activeFromInstant())) {
			throw failure(RouteBundleActivationException.Reason.BUNDLE_FUTURE);
		}
		if (!now.isBefore(identity.freshUntilInstant())) {
			throw failure(RouteBundleActivationException.Reason.BUNDLE_STALE);
		}
	}

	private static boolean isStale(VerifiedRouteBundleCandidate candidate, Instant now) {
		return !now.isBefore(candidate.identity().freshUntilInstant());
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
