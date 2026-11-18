package com.easysubway.journey.adapter.out.integrity;

import com.easysubway.journey.application.JourneySessionIntegrityPort;
import com.easysubway.journey.application.JourneySessionIntegrityPort.ProviderUnavailableException;
import com.easysubway.route.application.port.out.PlayIntegrityDecoder;
import com.easysubway.route.application.port.out.PlayIntegrityProviderUnavailableException;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("(prod | staging | release | prod-like) & !capacity-evidence")
public final class JourneyPlayIntegrityAdapter implements JourneySessionIntegrityPort {

	private final PlayIntegrityDecoder decoder;

	public JourneyPlayIntegrityAdapter(PlayIntegrityDecoder decoder) {
		this.decoder = Objects.requireNonNull(decoder, "decoder");
	}

	@Override
	public Verdict decode(String integrityToken) {
		PlayIntegrityDecoder.PlayIntegrityVerdict verdict;
		try {
			verdict = decoder.decode(integrityToken);
		} catch (PlayIntegrityProviderUnavailableException exception) {
			throw new ProviderUnavailableException(exception);
		}
		return new Verdict(
			verdict.requestPackageName(),
			verdict.requestHash(),
			verdict.requestTimestamp(),
			verdict.appPackageName(),
			verdict.appRecognitionVerdict(),
			verdict.certificateSha256Digests(),
			verdict.appLicensingVerdict(),
			verdict.deviceRecognitionVerdicts()
		);
	}
}
