package com.easysubway.notification.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.easysubway.notification.application.port.in.DeliverPushNotificationsCommand;
import com.easysubway.notification.application.port.in.PushNotificationDeliveryUseCase;
import com.easysubway.notification.application.port.out.LoadPendingPushNotificationOutboxPort;
import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationDeliveryResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@DisplayName("푸시 알림 자동 발송 스케줄러")
class PushNotificationDeliverySchedulerTest {

	private PendingUserOutbox pendingUserOutbox;
	private RecordingDeliveryUseCase deliveryUseCase;
	private StringRedisTemplate redisTemplate;
	private PushNotificationDeliveryScheduler scheduler;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		pendingUserOutbox = new PendingUserOutbox();
		deliveryUseCase = new RecordingDeliveryUseCase();
		redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
		when(redisTemplate.execute(
			any(RedisScript.class),
			eq(List.of("easysubway:notifications:push:delivery:scheduler-lock")),
			anyString(),
			eq("300000")
		)).thenReturn(1L);
		when(redisTemplate.execute(
			any(RedisScript.class),
			eq(List.of("easysubway:notifications:push:delivery:scheduler-lock")),
			anyString()
		)).thenReturn(1L);
		scheduler = new PushNotificationDeliveryScheduler(pendingUserOutbox, deliveryUseCase, redisTemplate, 300000L);
	}

	@Test
	@DisplayName("대기 중인 알림이 있는 사용자별로 발송 유스케이스를 호출한다")
	void deliverPendingNotificationsCallsDeliveryUseCaseForPendingUsers() {
		pendingUserOutbox.userIds = List.of("anonymous-user-1", "anonymous-user-2");

		scheduler.deliverPendingNotifications();

		assertThat(deliveryUseCase.deliveredUserIds)
			.containsExactly("anonymous-user-1", "anonymous-user-2");
	}

	@Test
	@DisplayName("다른 인스턴스가 lock을 보유하면 발송 유스케이스를 호출하지 않는다")
	@SuppressWarnings("unchecked")
	void deliverPendingNotificationsSkipsWhenLockIsAlreadyHeld() {
		when(redisTemplate.execute(
			any(RedisScript.class),
			eq(List.of("easysubway:notifications:push:delivery:scheduler-lock")),
			anyString(),
			eq("300000")
		)).thenReturn(0L);
		pendingUserOutbox.userIds = List.of("anonymous-user-1");

		scheduler.deliverPendingNotifications();

		assertThat(deliveryUseCase.deliveredUserIds).isEmpty();
	}

	@Test
	@DisplayName("lock을 획득한 실행은 완료 후 같은 token으로 lock을 해제한다")
	@SuppressWarnings("unchecked")
	void deliverPendingNotificationsReleasesAcquiredLock() {
		pendingUserOutbox.userIds = List.of("anonymous-user-1");

		scheduler.deliverPendingNotifications();

		verify(redisTemplate).execute(
			any(RedisScript.class),
			eq(List.of("easysubway:notifications:push:delivery:scheduler-lock")),
			anyString()
		);
	}

	@Test
	@DisplayName("한 사용자 발송이 실패해도 다음 사용자 처리를 계속한다")
	void deliverPendingNotificationsContinuesWhenOneUserFails() {
		pendingUserOutbox.userIds = List.of("anonymous-user-1", "anonymous-user-2");
		deliveryUseCase.failedUserId = "anonymous-user-1";

		scheduler.deliverPendingNotifications();

		assertThat(deliveryUseCase.deliveredUserIds)
			.containsExactly("anonymous-user-1", "anonymous-user-2");
	}

	@Test
	@DisplayName("자동 발송 스케줄러는 설정이 꺼져 있으면 빈을 등록하지 않는다")
	void schedulerBeanIsDisabledByDefault() {
		schedulerContextRunner()
			.run(context -> assertThat(context)
				.doesNotHaveBean(PushNotificationDeliveryScheduler.class));
	}

	@Test
	@DisplayName("자동 발송 스케줄러는 설정이 켜져 있으면 빈을 등록한다")
	void schedulerBeanIsEnabledWhenConfigured() {
		schedulerContextRunner()
			.withPropertyValues("easysubway.notifications.push.delivery.enabled=true")
			.run(context -> assertThat(context)
				.hasSingleBean(PushNotificationDeliveryScheduler.class));
	}

	private ApplicationContextRunner schedulerContextRunner() {
		return new ApplicationContextRunner()
			.withBean(LoadPendingPushNotificationOutboxPort.class, PendingUserOutbox::new)
			.withBean(PushNotificationDeliveryUseCase.class, RecordingDeliveryUseCase::new)
			.withBean(StringRedisTemplate.class, () -> org.mockito.Mockito.mock(StringRedisTemplate.class))
			.withUserConfiguration(PushNotificationDeliveryScheduler.class);
	}

	private static class PendingUserOutbox implements LoadPendingPushNotificationOutboxPort {

		private List<String> userIds = List.of();

		@Override
		public List<PushNotification> loadPendingPushNotifications(String userId) {
			return List.of();
		}

		@Override
		public List<String> loadPendingPushNotificationUserIds() {
			return userIds;
		}
	}

	private static class RecordingDeliveryUseCase implements PushNotificationDeliveryUseCase {

		private final List<String> deliveredUserIds = new ArrayList<>();
		private String failedUserId;

		@Override
		public PushNotificationDeliveryResult deliverPending(DeliverPushNotificationsCommand command) {
			deliveredUserIds.add(command.userId());
			if (command.userId().equals(failedUserId)) {
				throw new IllegalStateException("푸시 알림 발송 실패");
			}
			return new PushNotificationDeliveryResult(command.userId(), 0, 0, List.of());
		}
	}
}
