package com.easysubway.journey.analytics;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Journey V3 검색 한 건의 운영 분석 기록. 출발·도착역, 세션, 요청 ID 같은 개인정보가 될 수 있는 값은 담지 않고,
 * 검색 시각도 정확한 시각이 아니라 서비스 시간대(Asia/Seoul) 날짜만 남긴다.
 * 알 수 없는 값은 {@link #UNKNOWN}, 해당하지 않는 값은 {@link #NOT_APPLICABLE}로 명시한다.
 */
public record JourneySearchRecord(
	String recordId,
	LocalDate recordedOn,
	JourneySearchKind kind,
	JourneySearchOutcome outcome,
	int httpStatus,
	String machineCode,
	String engineVersion,
	String mobilityProfile,
	List<String> alternativeCategories,
	String stairFreeStatus
) {

	public static final String UNKNOWN = "UNKNOWN";
	public static final String NOT_APPLICABLE = "NOT_APPLICABLE";

	public JourneySearchRecord {
		Objects.requireNonNull(recordId, "recordId");
		Objects.requireNonNull(recordedOn, "recordedOn");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(outcome, "outcome");
		Objects.requireNonNull(engineVersion, "engineVersion");
		Objects.requireNonNull(mobilityProfile, "mobilityProfile");
		alternativeCategories = List.copyOf(Objects.requireNonNull(alternativeCategories, "alternativeCategories"));
		Objects.requireNonNull(stairFreeStatus, "stairFreeStatus");
	}

	/** 저장·집계용으로 대표 분류를 쉼표로 이어 붙인 값(없으면 빈 문자열). */
	public String alternativeCategoriesText() {
		return String.join(",", alternativeCategories);
	}
}
