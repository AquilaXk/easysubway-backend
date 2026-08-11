package com.easysubway.journey.application;

import java.time.Instant;
import java.util.List;

@FunctionalInterface
public interface JourneySessionIntegrityPort {

	Verdict decode(String integrityToken);

	record Verdict(
		String requestPackageName,
		String requestHash,
		Instant requestTimestamp,
		String appPackageName,
		String appRecognitionVerdict,
		List<String> certificateSha256Digests,
		String appLicensingVerdict,
		List<String> deviceRecognitionVerdicts
	) {
		public Verdict {
			certificateSha256Digests = copyOrNull(certificateSha256Digests);
			deviceRecognitionVerdicts = copyOrNull(deviceRecognitionVerdicts);
		}

		private static List<String> copyOrNull(List<String> values) {
			return values == null ? null : List.copyOf(values);
		}
	}

	final class ProviderUnavailableException extends RuntimeException {
		public ProviderUnavailableException(Throwable cause) {
			super("Journey integrity provider is unavailable", cause);
		}
	}
}
