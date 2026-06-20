package com.easysubway.report.application.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
class ProductionReportReceiptTokenPepperValidator {

	private static final String LOCAL_DEV_RECEIPT_TOKEN_PEPPER = "local-dev-report-receipt-pepper";

	ProductionReportReceiptTokenPepperValidator(
		@Value("${easysubway.report.receipt-token-pepper:}") String receiptTokenPepper
	) {
		if (receiptTokenPepper == null
			|| receiptTokenPepper.isBlank()
			|| LOCAL_DEV_RECEIPT_TOKEN_PEPPER.equals(receiptTokenPepper.trim())
			|| receiptTokenPepper.trim().length() < 32) {
			throw new IllegalStateException("운영 receipt token pepper 설정이 필요합니다.");
		}
	}
}
