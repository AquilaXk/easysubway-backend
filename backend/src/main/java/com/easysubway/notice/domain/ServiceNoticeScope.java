package com.easysubway.notice.domain;

/**
 * 공지 대상 범위. REGION·LINE은 대상 값(지역/노선 id)이 필수, ALL은 값이 없다.
 */
public enum ServiceNoticeScope {
	ALL,
	REGION,
	LINE
}
