package com.easysubway.journey.bundle;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compiles one already-admitted publication into an immutable candidate token. */
public final class RouteBundleCandidateAssembler {

	private static final String MANIFEST_PATH = "manifest.json";
	private static final List<String> PAYLOAD_PATHS = List.of(
		"payload/accessibility.sqlite.zst",
		"payload/fare.sqlite.zst",
		"payload/timetable.sqlite.zst",
		"payload/topology.sqlite.zst");

	private final RuntimeCompiler compiler;

	public RouteBundleCandidateAssembler() {
		var sqliteCompiler = new RouteBundleSqliteRuntimeCompiler();
		this.compiler = sqliteCompiler::compile;
	}

	RouteBundleCandidateAssembler(RuntimeCompiler compiler) {
		this.compiler = Objects.requireNonNull(compiler, "compiler");
	}

	public VerifiedRouteBundleCandidate assemble(
		RouteBundleObjectAdmission.VerifiedObjectAdmission admission,
		long candidateGeneration,
		Instant verifiedAt) {
		Objects.requireNonNull(admission, "admission");
		if (candidateGeneration < 1) {
			throw new IllegalArgumentException("candidateGeneration must be positive");
		}
		Objects.requireNonNull(verifiedAt, "verifiedAt");

		RouteBundleConsumerHandoff handoff = admission.verifiedSignature().handoff();
		if (!("sha256:" + handoff.admissionEvidence().manifestSha256())
			.equals(handoff.platformServerRouteBundleDigest())) {
			throw new IllegalArgumentException("route-bundle candidate identity mismatch");
		}
		return assemble(
			handoff.identity(),
			handoff.admissionEvidence(),
			admittedPayloadDigests(handoff),
			admission::objectBytes,
			candidateGeneration,
			verifiedAt);
	}

	public VerifiedRouteBundleCandidate assemble(
		RouteBundleObjectAdmission.VerifiedPublicationObjectAdmission admission,
		long candidateGeneration,
		Instant verifiedAt) {
		Objects.requireNonNull(admission, "admission");
		RouteBundlePublicationDescriptor descriptor =
			admission.verifiedDescriptorSignature().descriptor();
		return assemble(
			descriptor.identity(),
			descriptor.admissionEvidence(),
			admittedPayloadDigests(descriptor),
			admission::objectBytes,
			candidateGeneration,
			verifiedAt);
	}

	private VerifiedRouteBundleCandidate assemble(
		RouteBundleIdentity identity,
		RouteBundleAdmissionEvidence admissionEvidence,
		Map<String, String> payloadDigests,
		ObjectBytes objectBytes,
		long candidateGeneration,
		Instant verifiedAt) {
		if (candidateGeneration < 1) {
			throw new IllegalArgumentException("candidateGeneration must be positive");
		}
		Objects.requireNonNull(verifiedAt, "verifiedAt");
		if (verifiedAt.isBefore(identity.activeFromInstant())
			|| !verifiedAt.isBefore(identity.freshUntilInstant())) {
			throw new IllegalArgumentException("candidate verification time is outside the bundle window");
		}

		var payloads = new LinkedHashMap<String, byte[]>(PAYLOAD_PATHS.size());
		PAYLOAD_PATHS.forEach(path -> payloads.put(path, objectBytes.get(path)));
		RouteBundlePayloadInspection inspection = RouteBundleArtifactInspector.inspect(
			objectBytes.get(MANIFEST_PATH), payloads);
		String manifestSha256 = inspection.manifestSha256();
		if (!inspection.identity().equals(identity)
			|| !manifestSha256.equals(admissionEvidence.manifestSha256())) {
			throw new IllegalArgumentException("route-bundle candidate identity mismatch");
		}

		var input = new RouteBundleSqliteRuntimeCompiler.Input(
			manifestSha256,
			candidateGeneration,
			identity.bundleId(),
			identity.releaseSequence(),
			identity.stationSetSha256(),
			payloadDigests,
			payloads);
		RouteBundleRuntimeView runtime = Objects.requireNonNull(
			compiler.compile(input), "compiled runtime");
		return new VerifiedRouteBundleCandidate(
			identity, admissionEvidence, runtime, verifiedAt);
	}

	private static Map<String, String> admittedPayloadDigests(RouteBundleConsumerHandoff handoff) {
		var digests = new LinkedHashMap<String, String>(PAYLOAD_PATHS.size());
		for (RouteBundleConsumerHandoff.PublishedObject object : handoff.objects()) {
			if (PAYLOAD_PATHS.contains(object.path())) digests.put(object.path(), object.sha256());
		}
		if (!digests.keySet().equals(java.util.Set.copyOf(PAYLOAD_PATHS))) {
			throw new IllegalArgumentException("route-bundle admitted payload digest inventory is invalid");
		}
		return Map.copyOf(digests);
	}

	private static Map<String, String> admittedPayloadDigests(RouteBundlePublicationDescriptor descriptor) {
		var digests = new LinkedHashMap<String, String>(PAYLOAD_PATHS.size());
		for (RouteBundlePublicationDescriptor.PublishedObject object : descriptor.objects()) {
			if (PAYLOAD_PATHS.contains(object.path())) digests.put(object.path(), object.sha256());
		}
		if (!digests.keySet().equals(java.util.Set.copyOf(PAYLOAD_PATHS))) {
			throw new IllegalArgumentException("route-bundle admitted payload digest inventory is invalid");
		}
		return Map.copyOf(digests);
	}

	@FunctionalInterface
	private interface ObjectBytes {
		byte[] get(String path);
	}

	@FunctionalInterface
	interface RuntimeCompiler {
		RouteBundleRuntimeView compile(RouteBundleSqliteRuntimeCompiler.Input input);
	}
}
