package com.easysubway.journey.bundle;

import java.util.regex.Pattern;

/** Backend-local admission provenance, intentionally separate from the Data manifest identity. */
public record RouteBundleAdmissionEvidence(
	String manifestSha256,
	String finalEvidenceReference,
	String promotionEvidenceReference,
	String immutablePublicationReceiptIdentity,
	String activationRequestIdentity) {

	private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

	public RouteBundleAdmissionEvidence {
		if (manifestSha256 == null || !SHA_256.matcher(manifestSha256).matches()) {
			throw new IllegalArgumentException("manifestSha256 must be a lowercase SHA-256 digest");
		}
		requireRawReference(finalEvidenceReference, "finalEvidenceReference");
		requireRawReference(promotionEvidenceReference, "promotionEvidenceReference");
		requireRawReference(immutablePublicationReceiptIdentity, "immutablePublicationReceiptIdentity");
		requireRawReference(activationRequestIdentity, "activationRequestIdentity");
	}

	private static void requireRawReference(String value, String field) {
		if (value == null || value.isEmpty()) {
			throw new IllegalArgumentException(field + " must not be empty");
		}
	}
}
