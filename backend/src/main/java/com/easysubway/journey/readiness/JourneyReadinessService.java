package com.easysubway.journey.readiness;

import com.easysubway.journey.bundle.ActiveRouteBundleSnapshot;
import com.easysubway.journey.bundle.RouteBundleActivationRegistry;
import com.easysubway.journey.bundle.RouteBundleIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

public final class JourneyReadinessService {

	private static final int SCHEMA_VERSION = 1;
	private static final String CANDIDATE_KIND = "journey-v3-candidate-readiness";
	private static final String ACTIVE_KIND = "journey-v3-active-readiness";

	private final RouteBundleActivationRegistry registry;
	private final JourneyReadinessProperties properties;

	public JourneyReadinessService(
		RouteBundleActivationRegistry registry,
		JourneyReadinessProperties properties) {
		this.registry = Objects.requireNonNull(registry, "registry");
		this.properties = Objects.requireNonNull(properties, "properties");
	}

	public CandidateReadiness candidate() {
		var snapshot = registry.candidateSnapshot();
		var identity = snapshot.identity();
		String evidenceSha256 = evidenceSha256(
			"schemaVersion", SCHEMA_VERSION,
			"artifactKind", CANDIDATE_KIND,
			"instanceId", properties.instanceId(),
			"releaseTupleSha256", properties.releaseTupleSha256(),
			"backendImageDigest", properties.backendImageDigest(),
			"backendConfigSha256", properties.backendConfigSha256(),
			"journeyContractSha256", properties.journeyContractSha256(),
			"routeBundleManifestSha256", snapshot.admissionEvidence().manifestSha256(),
			"bundleId", identity.bundleId(),
			"bundleReleaseSequence", identity.releaseSequence(),
			"generation", snapshot.generation(),
			"warmed", true,
			"ready", true,
			"freshUntil", identity.freshUntilInstant(),
			"verifiedAt", snapshot.verifiedAt(),
			"stagedAt", snapshot.stagedAt());
		return new CandidateReadiness(
			SCHEMA_VERSION,
			CANDIDATE_KIND,
			properties.instanceId(),
			properties.releaseTupleSha256(),
			properties.backendImageDigest(),
			properties.backendConfigSha256(),
			properties.journeyContractSha256(),
			snapshot.admissionEvidence().manifestSha256(),
			identity.bundleId(),
			identity.releaseSequence(),
			snapshot.generation(),
			true,
			true,
			identity.freshUntilInstant(),
			snapshot.verifiedAt(),
			snapshot.stagedAt(),
			evidenceSha256);
	}

	public ActiveReadiness active() {
		return active(registry.activeSnapshot());
	}

	public ActiveReadiness active(ActiveRouteBundleSnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot");
		RouteBundleIdentity identity = snapshot.identity();
		String evidenceSha256 = evidenceSha256(
			"schemaVersion", SCHEMA_VERSION,
			"artifactKind", ACTIVE_KIND,
			"instanceId", properties.instanceId(),
			"releaseTupleSha256", properties.releaseTupleSha256(),
			"backendImageDigest", properties.backendImageDigest(),
			"backendConfigSha256", properties.backendConfigSha256(),
			"journeyContractSha256", properties.journeyContractSha256(),
			"routeBundleManifestSha256", snapshot.admissionEvidence().manifestSha256(),
			"bundleId", identity.bundleId(),
			"bundleReleaseSequence", identity.releaseSequence(),
			"generation", snapshot.generation(),
			"trafficGeneration", properties.trafficGeneration(),
			"servingReady", true,
			"draining", false,
			"freshUntil", identity.freshUntilInstant(),
			"activatedAt", snapshot.activatedAt());
		return new ActiveReadiness(
			SCHEMA_VERSION,
			ACTIVE_KIND,
			properties.instanceId(),
			properties.releaseTupleSha256(),
			properties.backendImageDigest(),
			properties.backendConfigSha256(),
			properties.journeyContractSha256(),
			snapshot.admissionEvidence().manifestSha256(),
			identity.bundleId(),
			identity.releaseSequence(),
			snapshot.generation(),
			properties.trafficGeneration(),
			true,
			false,
			identity.freshUntilInstant(),
			snapshot.activatedAt(),
			evidenceSha256);
	}

	private static String evidenceSha256(Object... values) {
		var canonical = new StringBuilder();
		for (Object value : values) {
			byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
			canonical.append(bytes.length).append(':').append(new String(bytes, StandardCharsets.UTF_8));
		}
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	public record CandidateReadiness(
		int schemaVersion,
		String artifactKind,
		String instanceId,
		String releaseTupleSha256,
		String backendImageDigest,
		String backendConfigSha256,
		String journeyContractSha256,
		String routeBundleManifestSha256,
		String bundleId,
		long bundleReleaseSequence,
		long generation,
		boolean warmed,
		boolean ready,
		Instant freshUntil,
		Instant verifiedAt,
		Instant stagedAt,
		String evidenceSha256) {
	}

	public record ActiveReadiness(
		int schemaVersion,
		String artifactKind,
		String instanceId,
		String releaseTupleSha256,
		String backendImageDigest,
		String backendConfigSha256,
		String journeyContractSha256,
		String routeBundleManifestSha256,
		String bundleId,
		long bundleReleaseSequence,
		long generation,
		long trafficGeneration,
		boolean servingReady,
		boolean draining,
		Instant freshUntil,
		Instant activatedAt,
		String evidenceSha256) {
	}
}
