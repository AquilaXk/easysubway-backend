package com.easysubway.journey.bundle;

import java.time.Instant;
import java.util.Objects;

/** Package-owned token emitted only after verification and runtime compilation. */
public final class VerifiedRouteBundleCandidate {

	private final RouteBundleIdentity identity;
	private final RouteBundleAdmissionEvidence admissionEvidence;
	private final RouteBundleServingEvidence servingEvidence;
	private final RouteBundleRuntimeView runtimeView;
	private final Instant verifiedAt;

	VerifiedRouteBundleCandidate(
		RouteBundleIdentity identity,
		RouteBundleAdmissionEvidence admissionEvidence,
		RouteBundleServingEvidence servingEvidence,
		RouteBundleRuntimeView runtimeView,
		Instant verifiedAt) {
		this.identity = Objects.requireNonNull(identity, "identity");
		this.admissionEvidence = Objects.requireNonNull(admissionEvidence, "admissionEvidence");
		this.servingEvidence = Objects.requireNonNull(servingEvidence, "servingEvidence");
		this.runtimeView = Objects.requireNonNull(runtimeView, "runtimeView");
		this.verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt");
	}

	public RouteBundleIdentity identity() {
		return identity;
	}

	public RouteBundleAdmissionEvidence admissionEvidence() {
		return admissionEvidence;
	}

	public RouteBundleServingEvidence servingEvidence() {
		return servingEvidence;
	}

	public RouteBundleRuntimeView runtimeView() {
		return runtimeView;
	}

	public Instant verifiedAt() {
		return verifiedAt;
	}
}
