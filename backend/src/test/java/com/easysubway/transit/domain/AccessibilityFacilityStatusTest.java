package com.easysubway.transit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("접근성 시설 상태")
class AccessibilityFacilityStatusTest {

	@Test
	@DisplayName("확인 필요 판정은 고장·공사·폐쇄·확인 필요·사용자 제보만 참이다")
	void needsAttentionCoversNonOperationalStatuses() {
		// 역 목록 "확인 필요 시설" 뱃지와 시설 상태판 정렬이 공유하는 단일 판정 기준(#1741).
		assertThat(AccessibilityFacilityStatus.BROKEN.needsAttention()).isTrue();
		assertThat(AccessibilityFacilityStatus.UNDER_CONSTRUCTION.needsAttention()).isTrue();
		assertThat(AccessibilityFacilityStatus.CLOSED.needsAttention()).isTrue();
		assertThat(AccessibilityFacilityStatus.UNKNOWN.needsAttention()).isTrue();
		assertThat(AccessibilityFacilityStatus.USER_REPORTED.needsAttention()).isTrue();

		assertThat(AccessibilityFacilityStatus.NORMAL.needsAttention()).isFalse();
		assertThat(AccessibilityFacilityStatus.ADMIN_VERIFIED.needsAttention()).isFalse();
	}

	@Test
	@DisplayName("모든 시설 상태는 한국어 라벨을 제공한다")
	void allStatusesHaveKoreanLabels() {
		for (AccessibilityFacilityStatus status : AccessibilityFacilityStatus.values()) {
			assertThat(status.label()).isNotBlank();
		}
		assertThat(AccessibilityFacilityStatus.NORMAL.label()).isEqualTo("정상");
		assertThat(AccessibilityFacilityStatus.BROKEN.label()).isEqualTo("고장");
		assertThat(AccessibilityFacilityStatus.UNDER_CONSTRUCTION.label()).isEqualTo("공사 중");
		assertThat(AccessibilityFacilityStatus.CLOSED.label()).isEqualTo("폐쇄");
		assertThat(AccessibilityFacilityStatus.UNKNOWN.label()).isEqualTo("확인 필요");
		assertThat(AccessibilityFacilityStatus.USER_REPORTED.label()).isEqualTo("사용자 제보");
		assertThat(AccessibilityFacilityStatus.ADMIN_VERIFIED.label()).isEqualTo("관리자 확인");
	}
}
