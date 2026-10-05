package com.easysubway.health.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.health.application.port.in.CheckHealthUseCase;
import com.easysubway.health.domain.HealthComponent;
import com.easysubway.health.domain.HealthStatus;
import com.easysubway.notification.domain.PushDeliveryAvailability;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("푸시 outbox 헬스 판정")
class PushOutboxAwareHealthCheckServiceTest {

	private static final CheckHealthUseCase BASE = () -> HealthStatus.of("UP", "easysubway-backend", List.of(
		new HealthComponent("application", "UP", "애플리케이션", "정상"),
		new HealthComponent("pushOutbox", "UNKNOWN", "푸시 outbox", "기본 판정")
	));

	@Test
	@DisplayName("푸시 발송이 불가하면 pushOutbox를 UNAVAILABLE과 사유로 보여 주고 전체 상태는 유지한다")
	void reportsUnavailableWithReasons() {
		HealthStatus status = new PushOutboxAwareHealthCheckService(
			BASE,
			() -> new PushDeliveryAvailability(false, List.of("푸시 자동 발송이 꺼져 있습니다."))
		).checkHealth();

		assertThat(status.status()).isEqualTo("UP");
		assertThat(status.components()).extracting("name").containsExactly("application", "pushOutbox");
		assertThat(status.components().get(1).status()).isEqualTo("UNAVAILABLE");
		assertThat(status.components().get(1).reason()).contains("푸시 자동 발송이 꺼져 있습니다.");
	}

	@Test
	@DisplayName("푸시 발송이 가능하면 pushOutbox는 UP이다")
	void reportsUpWhenAvailable() {
		HealthStatus status = new PushOutboxAwareHealthCheckService(
			BASE,
			() -> new PushDeliveryAvailability(true, List.of())
		).checkHealth();

		assertThat(status.components().get(1).status()).isEqualTo("UP");
	}

	@Test
	@DisplayName("판정 중 예외가 나면 성공으로 덮지 않고 UNKNOWN으로 드러낸다")
	void reportsUnknownWhenCheckFails() {
		HealthStatus status = new PushOutboxAwareHealthCheckService(
			BASE,
			() -> {
				throw new IllegalStateException("boom");
			}
		).checkHealth();

		assertThat(status.components().get(1).status()).isEqualTo("UNKNOWN");
	}
}
