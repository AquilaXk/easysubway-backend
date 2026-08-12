package com.easysubway.journey.bundle;

import java.util.List;
import java.util.Objects;

/** Immutable Data publication facts plus Backend-owned admission provenance. */
public record RouteBundlePublicationDescriptor(
	String repositoryGitSha,
	RouteBundleIdentity identity,
	String sourceSnapshotSetHash,
	RouteBundleAdmissionEvidence admissionEvidence,
	PublicationLocator locator,
	List<PublishedObject> objects,
	String prePublicationFinalSha256,
	ReleaseEvidence release,
	String descriptorSha256) {

	public RouteBundlePublicationDescriptor {
		repositoryGitSha = Objects.requireNonNull(repositoryGitSha, "repositoryGitSha");
		identity = Objects.requireNonNull(identity, "identity");
		sourceSnapshotSetHash = Objects.requireNonNull(sourceSnapshotSetHash, "sourceSnapshotSetHash");
		admissionEvidence = Objects.requireNonNull(admissionEvidence, "admissionEvidence");
		locator = Objects.requireNonNull(locator, "locator");
		objects = List.copyOf(objects);
		prePublicationFinalSha256 = Objects.requireNonNull(
			prePublicationFinalSha256, "prePublicationFinalSha256");
		release = Objects.requireNonNull(release, "release");
		descriptorSha256 = Objects.requireNonNull(descriptorSha256, "descriptorSha256");
	}

	public record PublicationLocator(String publicBaseUrl, String objectPrefix) {
		public PublicationLocator {
			Objects.requireNonNull(publicBaseUrl, "publicBaseUrl");
			Objects.requireNonNull(objectPrefix, "objectPrefix");
		}
	}

	public record PublishedObject(String path, String objectKey, long sizeBytes, String sha256) {
		public PublishedObject {
			Objects.requireNonNull(path, "path");
			Objects.requireNonNull(objectKey, "objectKey");
			Objects.requireNonNull(sha256, "sha256");
		}
	}

	public record ReleaseEvidence(
		String result,
		String finalSha256,
		String finalRawSha256,
		String publicationReceiptSha256,
		String publicationReceiptRawSha256,
		String promotionEvidenceSha256) {
		public ReleaseEvidence {
			Objects.requireNonNull(result, "result");
			Objects.requireNonNull(finalSha256, "finalSha256");
			Objects.requireNonNull(finalRawSha256, "finalRawSha256");
			Objects.requireNonNull(publicationReceiptSha256, "publicationReceiptSha256");
			Objects.requireNonNull(publicationReceiptRawSha256, "publicationReceiptRawSha256");
			Objects.requireNonNull(promotionEvidenceSha256, "promotionEvidenceSha256");
		}
	}
}
