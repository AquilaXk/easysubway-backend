package com.easysubway.common.configuration;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("prod | staging | release | prod-like")
final class ProductionReportCanonicalSecretGuard implements SmartInitializingSingleton {

	private static final String CANONICAL_RECEIPT_KEY = "EASYSUBWAY_REPORT_RECEIPT_PEPPER";
	private static final String LEGACY_RECEIPT_KEY = "EASYSUBWAY_REPORT_RECEIPT_TOKEN_PEPPER";
	private static final String CANONICAL_INTENT_KEY = "EASYSUBWAY_REPORT_UPLOAD_INTENT_SIGNING_KEY";
	private static final String LOCAL_RECEIPT_VALUE = "local-dev-report-receipt-pepper";
	private static final String LOCAL_INTENT_VALUE = "local-dev-report-upload-intent-signing-key";
	private static final int MINIMUM_SECRET_LENGTH = 32;

	private final Environment environment;
	private final String receiptTokenPepper;
	private final String uploadIntentSigningKey;

	ProductionReportCanonicalSecretGuard(
		Environment environment,
		@Value("${easysubway.report.receipt-token-pepper:}") String receiptTokenPepper,
		@Value("${easysubway.report.upload.intent-signing-key:}") String uploadIntentSigningKey
	) {
		this.environment = environment;
		this.receiptTokenPepper = receiptTokenPepper;
		this.uploadIntentSigningKey = uploadIntentSigningKey;
	}

	@Override
	public void afterSingletonsInstantiated() {
		if (environment.containsProperty(LEGACY_RECEIPT_KEY)) {
			throw invalid(LEGACY_RECEIPT_KEY);
		}
		requireProductionSecret(CANONICAL_RECEIPT_KEY, receiptTokenPepper, LOCAL_RECEIPT_VALUE);
		requireProductionSecret(CANONICAL_INTENT_KEY, uploadIntentSigningKey, LOCAL_INTENT_VALUE);
		if (receiptTokenPepper.trim().equals(uploadIntentSigningKey.trim())) {
			throw new IllegalStateException(
				"운영 report secret은 서로 달라야 합니다: "
					+ CANONICAL_RECEIPT_KEY
					+ ", "
					+ CANONICAL_INTENT_KEY
			);
		}
	}

	private static void requireProductionSecret(String key, String value, String localValue) {
		if (value == null
			|| value.isBlank()
			|| value.trim().length() < MINIMUM_SECRET_LENGTH
			|| localValue.equals(value.trim())) {
			throw invalid(key);
		}
	}

	private static IllegalStateException invalid(String key) {
		return new IllegalStateException("운영 report secret canonical 설정이 필요합니다: " + key);
	}
}
