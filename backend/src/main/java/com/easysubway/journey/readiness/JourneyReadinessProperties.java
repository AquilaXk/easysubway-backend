package com.easysubway.journey.readiness;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "easysubway.journey-v3.readiness", ignoreUnknownFields = false)
public record JourneyReadinessProperties(
	String serviceToken,
	String instanceId,
	String releaseTupleSha256,
	String backendImageDigest,
	String backendConfigSha256,
	String journeyContractSha256,
	String deploymentRevision,
	long trafficGeneration) {

	private static final Pattern INSTANCE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
	private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern IMAGE_DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
	private static final Pattern GIT_REVISION = Pattern.compile("[0-9a-f]{40}");

	public JourneyReadinessProperties {
		requireToken(serviceToken);
		if (instanceId == null || !INSTANCE_ID.matcher(instanceId).matches()) {
			throw new IllegalArgumentException("instanceId must be a bounded safe runtime identity");
		}
		requireSha256(releaseTupleSha256, "releaseTupleSha256");
		if (backendImageDigest == null || !IMAGE_DIGEST.matcher(backendImageDigest).matches()) {
			throw new IllegalArgumentException("backendImageDigest must be a lowercase OCI SHA-256 digest");
		}
		requireSha256(backendConfigSha256, "backendConfigSha256");
		requireSha256(journeyContractSha256, "journeyContractSha256");
		deploymentRevision = deploymentRevision != null && GIT_REVISION.matcher(deploymentRevision).matches()
			? deploymentRevision : null;
		if (trafficGeneration < 1) {
			throw new IllegalArgumentException("trafficGeneration must be positive");
		}
	}

	public JourneyReadinessProperties(String serviceToken, String instanceId, String releaseTupleSha256,
		String backendImageDigest, String backendConfigSha256, String journeyContractSha256,
		long trafficGeneration) {
		this(serviceToken, instanceId, releaseTupleSha256, backendImageDigest, backendConfigSha256,
			journeyContractSha256, null, trafficGeneration);
	}

	private static void requireToken(String value) {
		if (value == null || value.length() < 32 || value.length() > 512
			|| value.codePoints().anyMatch(codePoint -> codePoint < 0x21 || codePoint == 0x7f)) {
			throw new IllegalArgumentException("serviceToken must be a bounded non-blank secret");
		}
	}

	private static void requireSha256(String value, String field) {
		if (value == null || !SHA_256.matcher(value).matches()) {
			throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
		}
	}
}
