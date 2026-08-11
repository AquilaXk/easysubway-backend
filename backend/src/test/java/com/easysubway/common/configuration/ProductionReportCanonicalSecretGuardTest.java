package com.easysubway.common.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

@DisplayName("운영 report secret canonical-only 설정")
class ProductionReportCanonicalSecretGuardTest {

	private static final String RECEIPT_PROPERTY = "easysubway.report.receipt-token-pepper";
	private static final String INTENT_PROPERTY = "easysubway.report.upload.intent-signing-key";
	private static final String CANONICAL_RECEIPT_KEY = "EASYSUBWAY_REPORT_RECEIPT_PEPPER";
	private static final String LEGACY_RECEIPT_KEY = "EASYSUBWAY_REPORT_RECEIPT_TOKEN_PEPPER";
	private static final String CANONICAL_INTENT_KEY = "EASYSUBWAY_REPORT_UPLOAD_INTENT_SIGNING_KEY";
	private static final String STRONG_RECEIPT = "canonical-receipt-pepper-32-bytes-minimum";
	private static final String STRONG_INTENT = "canonical-upload-intent-key-32-bytes-minimum";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(ProductionReportCanonicalSecretGuard.class)
		.withPropertyValues("spring.profiles.active=prod");

	@Test
	void startsWithTwoIndependentCanonicalSecrets() {
		contextRunner
			.withPropertyValues(
				RECEIPT_PROPERTY + "=" + STRONG_RECEIPT,
				INTENT_PROPERTY + "=" + STRONG_INTENT
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(ProductionReportCanonicalSecretGuard.class);
			});
	}

	@Test
	void rejectsLegacyInputWhenGlobalLazyInitializationIsEnabled() {
		String legacyValue = "sensitive-legacy-receipt-pepper-with-enough-entropy";
		Throwable startupFailure;
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			TestPropertyValues.of(
				"spring.main.lazy-initialization=true",
				RECEIPT_PROPERTY + "=" + STRONG_RECEIPT,
				INTENT_PROPERTY + "=" + STRONG_INTENT,
				LEGACY_RECEIPT_KEY + "=" + legacyValue
			).applyTo(context);
			context.getEnvironment().setActiveProfiles("prod");
			context.register(ProductionReportCanonicalSecretGuard.class);
			context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor());
			startupFailure = catchThrowable(context::refresh);
		}

		assertThat(startupFailure).hasStackTraceContaining(LEGACY_RECEIPT_KEY);
		assertThat(rootCause(startupFailure).getMessage()).doesNotContain(legacyValue);
	}

	@Test
	void rejectsEqualNormalizedCanonicalSecrets() {
		String sharedValue = "shared-report-secret-value-with-enough-entropy";
		assertStartupFailure(
			new String[] {
				RECEIPT_PROPERTY + "=" + sharedValue,
				INTENT_PROPERTY + "= " + sharedValue + " "
			},
			CANONICAL_INTENT_KEY,
			sharedValue
		);
	}

	@Test
	void rejectsMissingBlankWeakAndLocalDevelopmentValues() {
		assertStartupFailure(
			new String[] { INTENT_PROPERTY + "=" + STRONG_INTENT },
			CANONICAL_RECEIPT_KEY,
			null
		);
		assertStartupFailure(
			new String[] { RECEIPT_PROPERTY + "=", INTENT_PROPERTY + "=" + STRONG_INTENT },
			CANONICAL_RECEIPT_KEY,
			null
		);
		assertStartupFailure(
			new String[] { RECEIPT_PROPERTY + "=too-short", INTENT_PROPERTY + "=" + STRONG_INTENT },
			CANONICAL_RECEIPT_KEY,
			"too-short"
		);
		assertStartupFailure(
			new String[] {
				RECEIPT_PROPERTY + "=local-dev-report-receipt-pepper",
				INTENT_PROPERTY + "=" + STRONG_INTENT
			},
			CANONICAL_RECEIPT_KEY,
			"local-dev-report-receipt-pepper"
		);

		assertStartupFailure(
			new String[] { RECEIPT_PROPERTY + "=" + STRONG_RECEIPT },
			CANONICAL_INTENT_KEY,
			null
		);
		assertStartupFailure(
			new String[] { RECEIPT_PROPERTY + "=" + STRONG_RECEIPT, INTENT_PROPERTY + "=" },
			CANONICAL_INTENT_KEY,
			null
		);
		assertStartupFailure(
			new String[] { RECEIPT_PROPERTY + "=" + STRONG_RECEIPT, INTENT_PROPERTY + "=too-short" },
			CANONICAL_INTENT_KEY,
			"too-short"
		);
		assertStartupFailure(
			new String[] {
				RECEIPT_PROPERTY + "=" + STRONG_RECEIPT,
				INTENT_PROPERTY + "=local-dev-report-upload-intent-signing-key"
			},
			CANONICAL_INTENT_KEY,
			"local-dev-report-upload-intent-signing-key"
		);
	}

	@Test
	void rejectsLegacyReceiptAliasAloneOrAlongsideCanonicalSecrets() {
		String legacyValue = "sensitive-legacy-receipt-pepper-with-enough-entropy";
		assertStartupFailure(
			new String[] { LEGACY_RECEIPT_KEY + "=" + legacyValue },
			LEGACY_RECEIPT_KEY,
			legacyValue
		);
		assertStartupFailure(
			new String[] {
				RECEIPT_PROPERTY + "=" + STRONG_RECEIPT,
				INTENT_PROPERTY + "=" + STRONG_INTENT,
				LEGACY_RECEIPT_KEY + "=" + legacyValue
			},
			LEGACY_RECEIPT_KEY,
			legacyValue
		);
	}

	@Test
	void productionYamlUsesRequiredCanonicalInputsWithoutFallback() throws IOException {
		String source = Files.readString(Path.of("src/main/resources/application-prod.yml"));

		assertThat(source)
			.contains("receipt-token-pepper: ${" + CANONICAL_RECEIPT_KEY + "}")
			.contains("intent-signing-key: ${" + CANONICAL_INTENT_KEY + "}")
			.doesNotContain(LEGACY_RECEIPT_KEY)
			.doesNotContain(CANONICAL_RECEIPT_KEY + ":")
			.doesNotContain(CANONICAL_INTENT_KEY + ":");
	}

	private void assertStartupFailure(String[] properties, String expectedIdentity, String configuredValue) {
		contextRunner.withPropertyValues(properties).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure())
				.isInstanceOf(IllegalStateException.class)
				.hasStackTraceContaining(expectedIdentity);
			if (configuredValue != null) {
				assertThat(rootCause(context.getStartupFailure()).getMessage()).doesNotContain(configuredValue);
			}
		});
	}

	private Throwable rootCause(Throwable failure) {
		Throwable current = failure;
		while (current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}
}
