package com.easysubway.route.application.port.out;

import java.time.Instant;
import java.util.List;

public interface PlayIntegrityDecoder {

	PlayIntegrityVerdict decode(String integrityToken);

	record PlayIntegrityVerdict(
		String requestPackageName,
		String requestHash,
		Instant requestTimestamp,
		String appPackageName,
		String appRecognitionVerdict,
		List<String> certificateSha256Digests,
		String appLicensingVerdict,
		List<String> deviceRecognitionVerdicts
	) {
		public PlayIntegrityVerdict {
			certificateSha256Digests = List.copyOf(certificateSha256Digests);
			deviceRecognitionVerdicts = List.copyOf(deviceRecognitionVerdicts);
		}
	}
}
