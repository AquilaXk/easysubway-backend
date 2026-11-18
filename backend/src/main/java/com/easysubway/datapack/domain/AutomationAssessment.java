package com.easysubway.datapack.domain;

import java.time.Duration;
import java.util.List;

/**
 * 자동화 상태 판정. 가장 심한 항목 하나가 상단 요약이 되고, 값이 없으면 정상으로 채우지 않고 UNKNOWN(수신 전)으로 둔다.
 *
 * @param remaining 활성 데이터팩 만료까지 남은 시간(만료되면 음수). snapshot이 없으면 null
 * @param receivedAge 마지막 수신 뒤 지난 시간. snapshot이 없으면 null
 */
public record AutomationAssessment(
	Level level,
	String headline,
	List<Finding> findings,
	boolean received,
	Duration remaining,
	Duration receivedAge
) {

	public AutomationAssessment {
		findings = List.copyOf(findings);
	}

	public enum Level { OK, WARNING, FAILURE, UNKNOWN }

	public record Finding(Level level, String code, String message) {
	}
}
