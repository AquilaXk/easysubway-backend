package com.easysubway.collection.application.port.out;

import com.easysubway.collection.domain.InvalidDataCollectionException;

public record TransitMasterCollectionSnapshot(
	String inputSource,
	String artifactReference,
	String checksum,
	int recordCount
) {

	public TransitMasterCollectionSnapshot {
		if (inputSource == null || inputSource.isBlank()) {
			throw new InvalidDataCollectionException("수집 입력 출처가 필요합니다.");
		}
		if (artifactReference == null || artifactReference.isBlank()) {
			throw new InvalidDataCollectionException("수집 artifact 참조가 필요합니다.");
		}
		if (checksum == null || checksum.isBlank()) {
			throw new InvalidDataCollectionException("수집 checksum이 필요합니다.");
		}
		if (recordCount < 0) {
			throw new InvalidDataCollectionException("수집 건수는 0 이상이어야 합니다.");
		}
		inputSource = inputSource.trim();
		artifactReference = artifactReference.trim();
		checksum = checksum.trim();
	}
}
