package com.easysubway.journey.bundle;

import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable serving evidence captured only from a verified publication descriptor. */
public record RouteBundleServingEvidence(
	Status status,
	String descriptorSha256,
	String publicationReceiptSha256) {

	private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");

	public enum Status { OBSERVED, UNOBSERVABLE }

	public RouteBundleServingEvidence {
		status = Objects.requireNonNull(status, "status");
		if (status == Status.UNOBSERVABLE) {
			if (descriptorSha256 != null || publicationReceiptSha256 != null) {
				throw new IllegalArgumentException("unobservable serving evidence must not have digests");
			}
		} else {
			descriptorSha256 = requireSha256(descriptorSha256, "descriptorSha256");
			publicationReceiptSha256 = requireSha256(
				publicationReceiptSha256, "publicationReceiptSha256");
		}
	}

	public static RouteBundleServingEvidence observed(
		String descriptorSha256, String publicationReceiptSha256) {
		return new RouteBundleServingEvidence(Status.OBSERVED, descriptorSha256, publicationReceiptSha256);
	}

	public static RouteBundleServingEvidence unobservable() {
		return new RouteBundleServingEvidence(Status.UNOBSERVABLE, null, null);
	}

	private static String requireSha256(String value, String name) {
		value = Objects.requireNonNull(value, name);
		if (!SHA256.matcher(value).matches()) {
			throw new IllegalArgumentException(name + " must be lowercase SHA-256");
		}
		return value;
	}
}
