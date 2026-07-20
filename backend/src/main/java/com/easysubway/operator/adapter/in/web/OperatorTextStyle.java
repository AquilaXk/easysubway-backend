package com.easysubway.operator.adapter.in.web;

import java.util.Map;

/**
 * 운영기관 리포트 화면 전용 문체 변환(#2349 PR⑩e). {@code DataQualityLevel}·{@code DataQualityService}가
 * 만드는 설명·사유 문구는 {@code /operator/api/*} JSON과 모바일 앱 등 다른 소비처가 공유하는 원본이라
 * 해요체를 그대로 유지해야 한다. 운영기관 리포트 화면만 다른 admin/operator 화면과 같은 합니다체를
 * 쓰도록, 원본(도메인 enum·JSON API)은 건드리지 않고 Thymeleaf 템플릿에서 {@code T(...)}로 이 변환만
 * 거친다(운영기관 페이지 컨트롤러와 JSON 컨트롤러가 같은 assembler를 공유하므로 assembler 단에서
 * 바꾸면 JSON 계약이 깨진다 — OperatorAccessibilityReportControllerTest 참고).
 */
public final class OperatorTextStyle {

	private static final Map<String, String> DECLARATIVE_OVERRIDES = Map.of(
		"일부 정보는 확인 중이에요", "일부 정보는 확인 중입니다",
		"시설 정보를 함께 볼 수 있어요", "시설 정보를 함께 볼 수 있습니다",
		"쉬운 길 안내를 볼 수 있어요", "쉬운 길 안내를 볼 수 있습니다",
		"고장·공사 소식이 반영됐어요", "고장·공사 소식이 반영되었습니다",
		"쉬운 길 확인이 더 필요해요", "쉬운 길 확인이 더 필요합니다",
		"고장·공사 소식 확인이 필요해요", "고장·공사 소식 확인이 필요합니다",
		"시설 상태를 다시 확인해야 해요", "시설 상태를 다시 확인해야 합니다"
	);

	private OperatorTextStyle() {
	}

	public static String declarative(String text) {
		if (text == null) {
			return null;
		}
		String result = text;
		for (Map.Entry<String, String> override : DECLARATIVE_OVERRIDES.entrySet()) {
			result = result.replace(override.getKey(), override.getValue());
		}
		return result;
	}
}
