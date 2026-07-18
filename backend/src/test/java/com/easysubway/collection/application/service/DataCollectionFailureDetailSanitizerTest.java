package com.easysubway.collection.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.BatchStatus;

@DisplayName("데이터 수집 실패 상세 정규화")
class DataCollectionFailureDetailSanitizerTest {

	@ParameterizedTest(name = "{index}: raw 실패 상세")
	@ValueSource(strings = {
		"GET https://provider.example/v1/stations?mode=full",
		"credential=provider-secret",
		"{\"token\":\"body-secret\"}",
		"response body={\"error\":\"provider payload\"}",
		"api key: sk-live-example",
		"password value hunter2",
		"client secret is example",
		"jdbc:postgresql://admin:secret-value@db.example/prod",
		"upstream returned {\"customer\":\"raw provider payload\"}"
	})
	@DisplayName("형태와 관계없이 raw Throwable message는 고정 안전 문구로 대체한다")
	void replacesEveryRawFailureMessage(String rawFailure) {
		String safeDetail = DataCollectionFailureDetailSanitizer.operatorSafe(
			new IllegalStateException(rawFailure)
		);

		assertThat(safeDetail)
			.contains("보호 정책")
			.doesNotContain(rawFailure);
	}

	@Test
	@DisplayName("긴 일반 실패 상세도 폐기하고 DB 한도보다 짧은 고정 문구만 반환한다")
	void discardsLongOrdinaryFailureDetail() {
		String rawFailure = "RAW_PROVIDER_DETAIL_" + "q".repeat(1_001);
		String safeDetail = DataCollectionFailureDetailSanitizer.operatorSafe(
			new IllegalStateException(rawFailure)
		);

		assertThat(safeDetail)
			.hasSizeLessThanOrEqualTo(DataCollectionFailureDetailSanitizer.MAX_LENGTH)
			.contains("IllegalStateException", "보호 정책")
			.doesNotContain("RAW_PROVIDER_DETAIL_");
	}

	@Test
	@DisplayName("Throwable이 없으면 코드가 만든 BatchStatus만 실패 코드로 사용한다")
	void usesBatchStatusWhenFailureIsAbsent() {
		assertThat(DataCollectionFailureDetailSanitizer.operatorSafe(null, BatchStatus.FAILED))
			.isEqualTo("BatchStatus.FAILED: 상세 오류는 보호 정책에 따라 생략되었습니다.");
	}
}
