package com.easysubway.operator.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.transit.domain.DataQualityLevel;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OperatorTextStyle}이 {@code DataQualityLevel}·{@code DataQualityService}가 만드는 원본
 * 해요체 문구를 운영기관 리포트 화면용 합니다체로 빠짐없이 바꾸는지 지키는 가드 테스트(#2349 PR⑩e
 * 리뷰 반영). 원본 도메인 텍스트가 바뀌면(예: {@code DataQualityLevel}에 새 해요체 사유가 추가되면)
 * 이 테스트가 {@code OperatorTextStyle.DECLARATIVE_OVERRIDES}에 대응 항목을 추가하라고 알려준다.
 */
@DisplayName("OperatorTextStyle 해요체→합니다체 변환 가드")
class OperatorTextStyleTest {

	// 해요체 어미("확인 중이에요"의 "이에요", "있어요"·"필요해요"·"반영됐어요"의 "어요", "확인해야
	// 해요"의 "해요") 잔존 여부를 잡아내는 패턴. "~습니다"/"~됩니다" 등 합니다체 어미는 걸리지 않는다.
	private static final Pattern INFORMAL_ENDING = Pattern.compile("(이에요|어요|해요)$");

	@Test
	@DisplayName("null은 그대로 통과한다")
	void nullPassesThrough() {
		assertThat(OperatorTextStyle.declarative(null)).isNull();
	}

	@Test
	@DisplayName("매핑표에 없는 문자열은 원형 그대로 통과한다")
	void unmappedTextPassesThroughUnchanged() {
		String text = "매핑표에 없는 임의의 문자열입니다";

		assertThat(OperatorTextStyle.declarative(text)).isEqualTo(text);
	}

	@Test
	@DisplayName("DataQualityLevel 전 항목의 description()은 변환 후 해요체 어미가 남지 않는다")
	void dataQualityLevelDescriptionsLoseInformalEnding() {
		for (DataQualityLevel level : DataQualityLevel.values()) {
			assertNoInformalEnding(level.description());
		}
	}

	@Test
	@DisplayName("DataQualityLevel 전 항목의 scoreReason()은 변환 후 해요체 어미가 남지 않는다")
	void dataQualityLevelScoreReasonsLoseInformalEnding() {
		for (DataQualityLevel level : DataQualityLevel.values()) {
			String scoreReason = level.scoreReason();
			if (scoreReason == null || scoreReason.isEmpty()) {
				continue;
			}
			assertNoInformalEnding(scoreReason);
		}
	}

	@Test
	@DisplayName("DataQualityService가 시설 접근성 감점 사유로 추가하는 해요체 문구도 변환 후 어미가 남지 않는다")
	void dataQualityServiceFacilityReasonLosesInformalEnding() {
		// com.easysubway.quality.application.service.DataQualityService#facilityAccessibilityAdjustment
		// 가 addReason(reasons, "시설 상태를 다시 확인해야 해요", 15)로 추가하는 사유. 같은 메서드의
		// 다른 사유("정상 접근성 시설 부족", "시설 정보 확인 필요", "시설 갱신 지연" 등)는 명사형이라
		// 해요체 어미가 없어 이 가드가 필요 없다.
		assertNoInformalEnding("시설 상태를 다시 확인해야 해요");
	}

	private static void assertNoInformalEnding(String text) {
		String converted = OperatorTextStyle.declarative(text);

		assertThat(INFORMAL_ENDING.matcher(converted).find())
			.as("변환 결과에 해요체 어미가 남아있음: \"%s\" -> \"%s\"", text, converted)
			.isFalse();
	}
}
