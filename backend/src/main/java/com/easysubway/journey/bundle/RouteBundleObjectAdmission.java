package com.easysubway.journey.bundle;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.codec.digest.DigestUtils;

/** Binds delivered publication bytes to one verified handoff without issuing a candidate. */
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
			byte[] stableBytes = source.clone();
			if (object.sizeBytes() != stableBytes.length) {
				throw failure(Reason.OBJECT_SIZE_MISMATCH, "publication object size does not match the handoff");
			}
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

	private static String sha256(byte[] value) {
		return DigestUtils.sha256Hex(value);
	}

	private static AdmissionException failure(Reason reason, String message) {
		return new AdmissionException(reason, message);
	}

	public enum Reason {
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
}
