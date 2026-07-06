package com.easysubway.notification.application.service;

import com.easysubway.notification.application.port.in.DeliverPushNotificationsCommand;
import com.easysubway.notification.application.port.in.PushNotificationDeliveryUseCase;
import com.easysubway.notification.application.port.in.PushNotificationResendUseCase;
import com.easysubway.notification.application.port.in.ResendPushNotificationsCommand;
import com.easysubway.notification.application.port.out.SavePushNotificationOutboxPort;
import com.easysubway.notification.application.port.out.SearchPushNotificationOutboxPort;
import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationResendResult;
import com.easysubway.notification.domain.PushNotificationStatus;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class PushNotificationResendService implements PushNotificationResendUseCase {

	private final SearchPushNotificationOutboxPort searchPushNotificationOutboxPort;
	private final SavePushNotificationOutboxPort savePushNotificationOutboxPort;
	private final PushNotificationDeliveryUseCase pushNotificationDeliveryUseCase;

	public PushNotificationResendService(
		SearchPushNotificationOutboxPort searchPushNotificationOutboxPort,
		SavePushNotificationOutboxPort savePushNotificationOutboxPort,
		PushNotificationDeliveryUseCase pushNotificationDeliveryUseCase
	) {
		this.searchPushNotificationOutboxPort = searchPushNotificationOutboxPort;
		this.savePushNotificationOutboxPort = savePushNotificationOutboxPort;
		this.pushNotificationDeliveryUseCase = pushNotificationDeliveryUseCase;
	}

	@Override
	public PushNotificationResendResult resend(ResendPushNotificationsCommand command) {
		List<String> ids = command.notificationIds();
		int requested = ids.size();
		if (requested == 0) {
			return new PushNotificationResendResult(0, 0, 0, false, command.maxPerResend());
		}
		// 대량 오발송 방지: 선택 건수가 1회 상한을 넘으면 아무것도 발송하지 않고 차단한다.
		if (command.maxPerResend() > 0 && requested > command.maxPerResend()) {
			return PushNotificationResendResult.blocked(requested, command.maxPerResend());
		}

		Map<String, PushNotification> byId = searchPushNotificationOutboxPort.loadPushNotificationsByIds(ids).stream()
			.collect(Collectors.toMap(PushNotification::notificationId, Function.identity(), (first, second) -> first));

		// 멱등: 실패 상태였던 건만 대기(PENDING)로 되돌린다(성공·대기·처리중·없는 건은 건너뜀).
		Set<String> affectedUserIds = new LinkedHashSet<>();
		int resent = 0;
		for (String id : ids) {
			PushNotification notification = byId.get(id);
			if (notification == null || notification.status() != PushNotificationStatus.FAILED) {
				continue;
			}
			savePushNotificationOutboxPort.savePushNotification(
				notification.withStatus(PushNotificationStatus.PENDING));
			affectedUserIds.add(notification.userId());
			resent++;
		}

		// 되돌린 건을 즉시 전달 시도한다(기존 배송 유스케이스 재사용). 발송 어댑터 미설정 시 다시 실패로 남는다.
		for (String userId : affectedUserIds) {
			pushNotificationDeliveryUseCase.deliverPending(new DeliverPushNotificationsCommand(userId));
		}

		return new PushNotificationResendResult(requested, resent, requested - resent, false, command.maxPerResend());
	}
}
