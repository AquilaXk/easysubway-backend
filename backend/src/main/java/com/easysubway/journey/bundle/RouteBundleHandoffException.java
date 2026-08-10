package com.easysubway.journey.bundle;

/** Typed structural failure before signature, payload, compilation, or activation admission. */
public final class RouteBundleHandoffException extends RuntimeException {

	public enum Reason {
		ACTIVATION_REQUEST_IDENTITY_INVALID,
		HANDOFF_UTF8_OR_JSON_INVALID,
		HANDOFF_CANONICAL_BYTES_MISMATCH,
		HANDOFF_SCHEMA_INVALID,
		HANDOFF_SELF_DIGEST_MISMATCH,
		PUBLICATION_RECEIPT_IDENTITY_MISMATCH,
		MANIFEST_IDENTITY_MISMATCH,
		RELEASE_EVIDENCE_IDENTITY_MISMATCH
	}

	private final Reason reason;

	RouteBundleHandoffException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	RouteBundleHandoffException(Reason reason, String message, Throwable cause) {
		super(message, cause);
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}
}
