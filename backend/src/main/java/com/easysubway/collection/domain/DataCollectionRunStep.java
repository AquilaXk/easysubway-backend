package com.easysubway.collection.domain;

public record DataCollectionRunStep(
	String name,
	DataCollectionStepStatus status,
	String inputSource,
	String artifactReference,
	String checksum,
	int recordCount,
	String failureMessage
) {

	public DataCollectionRunStep {
		if (name == null || name.isBlank()) {
			throw new InvalidDataCollectionException("수집 단계명이 필요합니다.");
		}
		if (status == null) {
			throw new InvalidDataCollectionException("수집 단계 상태가 필요합니다.");
		}
		if (recordCount < 0) {
			throw new InvalidDataCollectionException("수집 단계 건수는 0 이상이어야 합니다.");
		}
		name = name.trim();
		inputSource = trimToNull(inputSource);
		artifactReference = trimToNull(artifactReference);
		checksum = trimToNull(checksum);
		failureMessage = trimToNull(failureMessage);
		if (status == DataCollectionStepStatus.FAILED && failureMessage == null) {
			throw new InvalidDataCollectionException("실패한 수집 단계는 실패 사유가 필요합니다.");
		}
	}

	private static String trimToNull(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}
}
