package com.easysubway.journey.bundle;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.codec.digest.DigestUtils;

/** Binds delivered publication bytes to one verified v1 handoff or v2 descriptor. */
public final class RouteBundleObjectAdmission {

	private static final String SIGNING_INPUT_PATH = "manifest.signing-input.json";

	private RouteBundleObjectAdmission() {
	}

	public static VerifiedObjectAdmission admit(
		byte[] handoffBytes,
		String activationRequestIdentity,
		Map<String, byte[]> rawObjects,
		RouteBundleCurrentKeyVerifier.CurrentKey currentKey) {
		byte[] stableHandoffBytes = handoffBytes == null ? null : handoffBytes.clone();
		RouteBundleConsumerHandoff handoff = RouteBundleConsumerHandoffParser.parse(
			stableHandoffBytes, activationRequestIdentity);
		List<String> expectedPaths = handoff.objects().stream()
			.map(RouteBundleConsumerHandoff.PublishedObject::path)
			.toList();
		if (rawObjects == null
			|| rawObjects.size() != expectedPaths.size()
			|| !rawObjects.keySet().equals(Set.copyOf(expectedPaths))) {
			throw failure(Reason.OBJECT_PATH_SET_MISMATCH, "publication objects must match the handoff path set");
		}

		var admittedObjects = new LinkedHashMap<String, byte[]>(expectedPaths.size());
		for (RouteBundleConsumerHandoff.PublishedObject object : handoff.objects()) {
			byte[] source = rawObjects.get(object.path());
			if (source == null || source.length == 0) {
				throw failure(Reason.OBJECT_BYTES_INVALID, "publication object bytes are required");
			}
			if (object.sizeBytes() != source.length) {
				throw failure(Reason.OBJECT_SIZE_MISMATCH, "publication object size does not match the handoff");
			}
			byte[] stableBytes = source.clone();
			if (!object.sha256().equals(sha256(stableBytes))) {
				throw failure(Reason.OBJECT_DIGEST_MISMATCH, "publication object digest does not match the handoff");
			}
			admittedObjects.put(object.path(), stableBytes);
		}

		RouteBundleCurrentKeyVerifier.VerifiedSignature verifiedSignature =
			RouteBundleCurrentKeyVerifier.verify(
				stableHandoffBytes,
				activationRequestIdentity,
				admittedObjects.get(SIGNING_INPUT_PATH),
				currentKey);
		return new VerifiedObjectAdmission(verifiedSignature, admittedObjects);
	}

	public static VerifiedPublicationObjectAdmission admitPublicationDescriptor(
		byte[] descriptorBytes,
		String activationRequestIdentity,
		RouteBundlePublicationObjectFetcher.FetchedPublicationObjects fetched,
		RouteBundleCurrentKeyVerifier.CurrentKey currentKey) {
		byte[] stableDescriptorBytes = descriptorBytes == null ? null : descriptorBytes.clone();
		RouteBundlePublicationDescriptor descriptor =
			RouteBundleConsumerHandoffParser.parsePublicationDescriptor(
				stableDescriptorBytes, activationRequestIdentity);
		if (fetched == null
			|| !descriptor.descriptorSha256().equals(fetched.descriptorSha256())
			|| !descriptor.identity().keyId().equals(fetched.keyId())) {
			throw failure(
				Reason.FETCHED_DESCRIPTOR_IDENTITY_MISMATCH,
				"fetched publication objects do not match the descriptor identity");
		}

		List<RouteBundlePublicationObjectFetcher.FetchedObject> fetchedObjects = fetched.objects();
		if (fetchedObjects.size() != descriptor.objects().size()) {
			throw failure(Reason.OBJECT_PATH_SET_MISMATCH, "publication objects must match the descriptor inventory");
		}
		var admittedObjects = new LinkedHashMap<String, byte[]>(fetchedObjects.size());
		for (int index = 0; index < descriptor.objects().size(); index++) {
			RouteBundlePublicationDescriptor.PublishedObject expected = descriptor.objects().get(index);
			RouteBundlePublicationObjectFetcher.FetchedObject actual = fetchedObjects.get(index);
			if (!expected.path().equals(actual.path())
				|| !expected.objectKey().equals(actual.objectKey())) {
				throw failure(
					Reason.OBJECT_PATH_SET_MISMATCH,
					"publication objects must preserve the descriptor inventory");
			}
			byte[] source = actual.bytes();
			admittedObjects.put(expected.path(), validateObject(
				expected.sizeBytes(), expected.sha256(), source));
		}

		RouteBundleCurrentKeyVerifier.VerifiedPublicationDescriptorSignature signature =
			RouteBundleCurrentKeyVerifier.verifyPublicationDescriptor(
				stableDescriptorBytes,
				activationRequestIdentity,
				admittedObjects.get(SIGNING_INPUT_PATH),
				currentKey);
		return new VerifiedPublicationObjectAdmission(signature, admittedObjects);
	}

	private static byte[] validateObject(long expectedSize, String expectedSha256, byte[] source) {
		if (source == null || source.length == 0) {
			throw failure(Reason.OBJECT_BYTES_INVALID, "publication object bytes are required");
		}
		if (expectedSize != source.length) {
			throw failure(Reason.OBJECT_SIZE_MISMATCH, "publication object size does not match the descriptor");
		}
		byte[] stableBytes = source.clone();
		if (!expectedSha256.equals(sha256(stableBytes))) {
			throw failure(Reason.OBJECT_DIGEST_MISMATCH, "publication object digest does not match the descriptor");
		}
		return stableBytes;
	}

	private static String sha256(byte[] value) {
		return DigestUtils.sha256Hex(value);
	}

	private static AdmissionException failure(Reason reason, String message) {
		return new AdmissionException(reason, message);
	}

	public enum Reason {
		FETCHED_DESCRIPTOR_IDENTITY_MISMATCH,
		OBJECT_PATH_SET_MISMATCH,
		OBJECT_BYTES_INVALID,
		OBJECT_SIZE_MISMATCH,
		OBJECT_DIGEST_MISMATCH
	}

	public static final class AdmissionException extends RuntimeException {
		private final Reason reason;

		private AdmissionException(Reason reason, String message) {
			super(message);
			this.reason = Objects.requireNonNull(reason, "reason");
		}

		public Reason reason() {
			return reason;
		}
	}

	public static final class VerifiedObjectAdmission {
		private final RouteBundleCurrentKeyVerifier.VerifiedSignature verifiedSignature;
		private final Map<String, byte[]> objects;

		private VerifiedObjectAdmission(
			RouteBundleCurrentKeyVerifier.VerifiedSignature verifiedSignature,
			Map<String, byte[]> objects) {
			this.verifiedSignature = Objects.requireNonNull(verifiedSignature, "verifiedSignature");
			this.objects = copyObjects(objects);
		}

		public RouteBundleCurrentKeyVerifier.VerifiedSignature verifiedSignature() {
			return verifiedSignature;
		}

		public Map<String, byte[]> objects() {
			return copyObjects(objects);
		}

		public byte[] objectBytes(String path) {
			byte[] value = objects.get(path);
			if (value == null) throw new IllegalArgumentException("unknown admitted object path");
			return value.clone();
		}

		private static Map<String, byte[]> copyObjects(Map<String, byte[]> source) {
			var copy = new LinkedHashMap<String, byte[]>(source.size());
			for (var entry : source.entrySet()) copy.put(entry.getKey(), entry.getValue().clone());
			return Collections.unmodifiableMap(copy);
		}
	}

	public static final class VerifiedPublicationObjectAdmission {
		private final RouteBundleCurrentKeyVerifier.VerifiedPublicationDescriptorSignature verifiedDescriptorSignature;
		private final Map<String, byte[]> objects;

		private VerifiedPublicationObjectAdmission(
			RouteBundleCurrentKeyVerifier.VerifiedPublicationDescriptorSignature verifiedDescriptorSignature,
			Map<String, byte[]> objects) {
			this.verifiedDescriptorSignature = Objects.requireNonNull(
				verifiedDescriptorSignature, "verifiedDescriptorSignature");
			this.objects = copyObjects(objects);
		}

		public RouteBundleCurrentKeyVerifier.VerifiedPublicationDescriptorSignature verifiedDescriptorSignature() {
			return verifiedDescriptorSignature;
		}

		public Map<String, byte[]> objects() {
			return copyObjects(objects);
		}

		public byte[] objectBytes(String path) {
			byte[] value = objects.get(path);
			if (value == null) throw new IllegalArgumentException("unknown admitted object path");
			return value.clone();
		}

		private static Map<String, byte[]> copyObjects(Map<String, byte[]> source) {
			var copy = new LinkedHashMap<String, byte[]>(source.size());
			for (var entry : source.entrySet()) copy.put(entry.getKey(), entry.getValue().clone());
			return Collections.unmodifiableMap(copy);
		}
	}
}
