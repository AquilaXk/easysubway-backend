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
		RouteBundleIdentity identity = handoff.identity();
		if (verifiedAt.isBefore(identity.activeFromInstant())
			|| !verifiedAt.isBefore(identity.freshUntilInstant())) {
			throw new IllegalArgumentException("candidate verification time is outside the bundle window");
		}

		var payloads = new LinkedHashMap<String, byte[]>(PAYLOAD_PATHS.size());
		PAYLOAD_PATHS.forEach(path -> payloads.put(path, admission.objectBytes(path)));
		RouteBundlePayloadInspection inspection = RouteBundleArtifactInspector.inspect(
			admission.objectBytes(MANIFEST_PATH), payloads);
		String manifestSha256 = inspection.manifestSha256();
		if (!inspection.identity().equals(identity)
			|| !inspection.payloadSha256().equals(identity.payloadSha256())
			|| !manifestSha256.equals(handoff.admissionEvidence().manifestSha256())
			|| !("sha256:" + manifestSha256).equals(handoff.platformServerRouteBundleDigest())) {
			throw new IllegalArgumentException("route-bundle candidate identity mismatch");
		}

		Map<String, String> payloadDigests = admittedPayloadDigests(handoff);
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
			identity, handoff.admissionEvidence(), runtime, verifiedAt);
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

	@FunctionalInterface
	interface RuntimeCompiler {
		RouteBundleRuntimeView compile(RouteBundleSqliteRuntimeCompiler.Input input);
	}
}
