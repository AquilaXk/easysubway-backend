package com.easysubway.journey.bundle;

import java.time.Instant;
import java.util.Objects;

/** Immutable authority token issued only by the activation registry. */
public final class ActiveRouteBundleSnapshot {

	private final long generation;
	private final RouteBundleIdentity identity;
	private final RouteBundleAdmissionEvidence admissionEvidence;
	private final RouteBundleRuntimeView runtimeView;
	private final Instant activatedAt;

	ActiveRouteBundleSnapshot(
		long generation,
		RouteBundleIdentity identity,
		RouteBundleAdmissionEvidence admissionEvidence,
		RouteBundleRuntimeView runtimeView,
		Instant activatedAt) {
		if (generation < 1) {
			throw new IllegalArgumentException("generation must be positive");
		}
		this.generation = generation;
		this.identity = Objects.requireNonNull(identity, "identity");
		this.admissionEvidence = Objects.requireNonNull(admissionEvidence, "admissionEvidence");
		this.runtimeView = Objects.requireNonNull(runtimeView, "runtimeView");
		this.activatedAt = Objects.requireNonNull(activatedAt, "activatedAt");
	}

	public long generation() {
		return generation;
	}

	public RouteBundleIdentity identity() {
		return identity;
	}

	public RouteBundleAdmissionEvidence admissionEvidence() {
		return admissionEvidence;
	}

	public RouteBundleRuntimeView runtimeView() {
		return runtimeView;
	}

	public Instant activatedAt() {
		return activatedAt;
	}
}
