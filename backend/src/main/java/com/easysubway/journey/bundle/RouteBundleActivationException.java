package com.easysubway.journey.bundle;

import java.util.Objects;

/** A closed, caller-visible failure reason for fixture activation. */
public final class RouteBundleActivationException extends RuntimeException {

	public enum Reason {
		BUNDLE_UNAVAILABLE,
		BUNDLE_STALE,
		BUNDLE_FUTURE,
		CANDIDATE_ALREADY_STAGED,
		CANDIDATE_ALREADY_ACTIVE,
		CANDIDATE_NOT_STAGED,
		CANDIDATE_IDENTITY_MISMATCH,
		ACTIVATION_CONFLICT
	}

	private final Reason reason;

	RouteBundleActivationException(Reason reason) {
		super(Objects.requireNonNull(reason, "reason").name());
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}
}
