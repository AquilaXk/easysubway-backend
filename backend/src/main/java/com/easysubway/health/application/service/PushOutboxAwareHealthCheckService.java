package com.easysubway.health.application.service;

import com.easysubway.health.application.port.in.CheckHealthUseCase;
import com.easysubway.health.domain.HealthComponent;
import com.easysubway.health.domain.HealthStatus;
import com.easysubway.notification.application.port.in.PushDeliveryAvailabilityUseCase;
import com.easysubway.notification.domain.PushDeliveryAvailability;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * 기본 헬스 판정의 pushOutbox(항상 UNKNOWN) 항목을 푸시 발송 가능 여부 판정으로 교체한다.
 * 발송 불가는 UNAVAILABLE과 사유로 드러내며, 전체 status 집계는 기본 판정을 그대로 따른다.
 */
@Service
@Primary
public class PushOutboxAwareHealthCheckService implements CheckHealthUseCase {

	private static final String COMPONENT_NAME = "pushOutbox";
	private static final String LABEL = "푸시 outbox";

	private final CheckHealthUseCase baseHealthCheck;
	private final PushDeliveryAvailabilityUseCase pushDeliveryAvailabilityUseCase;

	@Autowired
	public PushOutboxAwareHealthCheckService(
		HealthCheckService baseHealthCheck,
		PushDeliveryAvailabilityUseCase pushDeliveryAvailabilityUseCase
	) {
		this((CheckHealthUseCase) baseHealthCheck, pushDeliveryAvailabilityUseCase);
	}

	PushOutboxAwareHealthCheckService(
		CheckHealthUseCase baseHealthCheck,
		PushDeliveryAvailabilityUseCase pushDeliveryAvailabilityUseCase
	) {
		this.baseHealthCheck = baseHealthCheck;
		this.pushDeliveryAvailabilityUseCase = pushDeliveryAvailabilityUseCase;
	}

	@Override
	public HealthStatus checkHealth() {
		HealthStatus base = baseHealthCheck.checkHealth();
		HealthComponent pushOutbox = pushOutboxComponent();
		List<HealthComponent> components = base.components().stream()
			.map(component -> COMPONENT_NAME.equals(component.name()) ? pushOutbox : component)
			.toList();
		return HealthStatus.of(base.status(), base.service(), components);
	}

	private HealthComponent pushOutboxComponent() {
		try {
			PushDeliveryAvailability availability = pushDeliveryAvailabilityUseCase.check();
			if (availability.available()) {
				return new HealthComponent(COMPONENT_NAME, "UP", LABEL, "푸시 발송이 가능합니다.");
			}
			return new HealthComponent(
				COMPONENT_NAME,
				"UNAVAILABLE",
				LABEL,
				"푸시 발송 불가: " + String.join(" ", availability.reasons())
			);
		} catch (RuntimeException exception) {
			return new HealthComponent(COMPONENT_NAME, "UNKNOWN", LABEL, "푸시 발송 가능 여부를 확인할 수 없습니다.");
		}
	}
}
