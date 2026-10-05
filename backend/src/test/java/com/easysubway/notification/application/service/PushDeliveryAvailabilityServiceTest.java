package com.easysubway.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.notification.application.port.out.PushNotificationSenderPort;
import com.easysubway.notification.domain.PushDeliveryAvailability;
import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationSendResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("푸시 발송 가능 여부 판정")
class PushDeliveryAvailabilityServiceTest {

	private static final PushNotificationSenderPort CONFIGURED_SENDER = new PushNotificationSenderPort() {
		@Override
		public PushNotificationSendResult send(PushNotification notification) {
			return PushNotificationSendResult.sent();
		}
	};

	private static final PushNotificationSenderPort UNCONFIGURED_SENDER = new PushNotificationSenderPort() {
		@Override
		public PushNotificationSendResult send(PushNotification notification) {
			return PushNotificationSendResult.failed("미설정");
		}

		@Override
		public boolean isConfigured() {
			return false;
		}
	};

	@Test
	@DisplayName("자동 발송이 켜져 있고 어댑터가 구성되면 발송 가능이다")
	void availableWhenEnabledAndConfigured() {
		PushDeliveryAvailability availability =
			new PushDeliveryAvailabilityService(true, CONFIGURED_SENDER).check();

		assertThat(availability.available()).isTrue();
		assertThat(availability.reasons()).isEmpty();
	}

	@Test
	@DisplayName("자동 발송이 꺼져 있으면 사유와 함께 발송 불가다")
	void unavailableWhenDeliveryDisabled() {
		PushDeliveryAvailability availability =
			new PushDeliveryAvailabilityService(false, CONFIGURED_SENDER).check();

		assertThat(availability.available()).isFalse();
		assertThat(availability.reasons()).containsExactly("푸시 자동 발송이 꺼져 있습니다.");
	}

	@Test
	@DisplayName("발송 어댑터가 없으면 사유와 함께 발송 불가다")
	void unavailableWhenSenderNotConfigured() {
		PushDeliveryAvailability availability =
			new PushDeliveryAvailabilityService(true, UNCONFIGURED_SENDER).check();

		assertThat(availability.available()).isFalse();
		assertThat(availability.reasons()).containsExactly("외부 푸시 발송 어댑터가 설정되지 않았습니다.");
	}

	@Test
	@DisplayName("두 사유가 겹치면 모두 보여 준다")
	void reportsAllReasons() {
		PushDeliveryAvailability availability =
			new PushDeliveryAvailabilityService(false, UNCONFIGURED_SENDER).check();

		assertThat(availability.reasons()).hasSize(2);
	}
}
