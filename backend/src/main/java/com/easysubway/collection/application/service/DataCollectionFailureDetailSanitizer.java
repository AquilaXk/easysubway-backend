package com.easysubway.collection.application.service;

import org.springframework.batch.core.BatchStatus;

final class DataCollectionFailureDetailSanitizer {

	static final int MAX_LENGTH = 500;

	private static final String PROTECTED_DETAIL = "상세 오류는 보호 정책에 따라 생략되었습니다.";

	private DataCollectionFailureDetailSanitizer() {
	}

	static String operatorSafe(Throwable failure) {
		return safeDetail(failureCode(failure));
	}

	static String operatorSafe(Throwable failure, BatchStatus status) {
		String failureCode = failure == null
			? "BatchStatus." + (status == null ? "UNKNOWN" : status.name())
			: failureCode(failure);
		return safeDetail(failureCode);
	}

	private static String failureCode(Throwable failure) {
		if (failure == null) {
			return "BatchFailure";
		}
		String simpleName = failure.getClass().getSimpleName();
		return simpleName.isBlank() || simpleName.length() > 100 ? "BatchFailure" : simpleName;
	}

	private static String safeDetail(String failureCode) {
		return failureCode + ": " + PROTECTED_DETAIL;
	}
}
