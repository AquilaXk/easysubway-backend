package com.easysubway.notification.application.service;

import com.easysubway.notification.application.port.in.PushDeliveryAvailabilityUseCase;
import com.easysubway.notification.application.port.out.PushNotificationSenderPort;
import com.easysubway.notification.domain.PushDeliveryAvailability;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PushDeliveryAvailabilityService implements PushDeliveryAvailabilityUseCase {

	static final String DELIVERY_DISABLED_REASON = "푸시 자동 발송이 꺼져 있습니다.";
	static final String SENDER_NOT_CONFIGURED_REASON = "외부 푸시 발송 어댑터가 설정되지 않았습니다.";

	private final boolean deliveryEnabled;
	private final PushNotificationSenderPort senderPort;

	public PushDeliveryAvailabilityService(
		@Value("${easysubway.notifications.push.delivery.enabled:false}") boolean deliveryEnabled,
		PushNotificationSenderPort senderPort
	) {
		this.deliveryEnabled = deliveryEnabled;
		this.senderPort = senderPort;
	}

	@Override
	public PushDeliveryAvailability check() {
		List<String> reasons = new ArrayList<>();
		if (!deliveryEnabled) {
			reasons.add(DELIVERY_DISABLED_REASON);
		}
		if (!senderPort.isConfigured()) {
			reasons.add(SENDER_NOT_CONFIGURED_REASON);
		}
		return new PushDeliveryAvailability(reasons.isEmpty(), reasons);
	}
}
