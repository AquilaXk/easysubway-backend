package com.easysubway.notification.domain;

/**
 * 관리자 발송 이력 콘솔(#1746)의 실패 사유별 분해 한 줄. 같은 필터 컨텍스트(기간·유형·검색)에서
 * status=FAILED 를 사유별로 GROUP BY 한 건수라, 사유 드릴다운으로 필터한 목록 건수와 정합한다.
 */
public record PushNotificationFailureReasonCount(String reason, long count) {
}
