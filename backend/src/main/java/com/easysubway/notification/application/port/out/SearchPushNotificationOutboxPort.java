package com.easysubway.notification.application.port.out;

import com.easysubway.notification.application.port.in.PushNotificationHistoryQuery;
import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationFailureReasonCount;
import java.util.List;

/**
 * 관리자 발송 이력 콘솔(#1746)의 outbox 조회 포트.
 *
 * <p>필터·페이지네이션이 적용된 이력 목록과 그 총 건수, 실패 사유별 분해를 준다. 목록·건수·분해가
 * 같은 질의를 공유해 페이지네이션·정합을 보장한다.
 */
public interface SearchPushNotificationOutboxPort {

	List<PushNotification> searchPushNotifications(PushNotificationHistoryQuery query);

	long countPushNotifications(PushNotificationHistoryQuery query);

	/** 선택한 알림 식별자들의 현재 상태를 로드한다(재발송 대상 검증·멱등 판정용). 순서·존재는 보장하지 않는다. */
	List<PushNotification> loadPushNotificationsByIds(List<String> notificationIds);

	/**
	 * 같은 필터 컨텍스트(기간·유형·검색)에서 status=FAILED 를 사유별로 GROUP BY 한 분해. 사유 드릴다운·상태
	 * 필터는 무시한다(분해는 항상 실패 전체를 사유별로 보여줘야 함). 건수 내림차순.
	 */
	List<PushNotificationFailureReasonCount> countFailureReasons(PushNotificationHistoryQuery query);
}
