package com.easysubway.health.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.health.application.port.in.CheckHealthUseCase;
import com.easysubway.health.domain.HealthComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password",
	"easysubway.user.username=anonymous-user-1",
	"easysubway.user.password=user-test-password"
})
@DisplayName("푸시 outbox 헬스 배선")
class PushOutboxHealthWiringTest {

	@Autowired
	private CheckHealthUseCase checkHealthUseCase;

	@Autowired
	private HealthCheckService baseHealthCheckService;

	@Test
	@DisplayName("CheckHealthUseCase 주입은 데코레이터이고 실제 기본 결과의 pushOutbox 하나를 교체한다")
	void primaryHealthCheckIsDecoratorReplacingTheRealPushOutbox() {
		assertThat(checkHealthUseCase).isInstanceOf(PushOutboxAwareHealthCheckService.class);

		assertThat(baseHealthCheckService.checkHealth().components())
			.filteredOn(component -> component.name().equals("pushOutbox"))
			.singleElement()
			.extracting(HealthComponent::status)
			.isEqualTo("UNKNOWN");

		assertThat(checkHealthUseCase.checkHealth().components())
			.filteredOn(component -> component.name().equals("pushOutbox"))
			.singleElement()
			.satisfies(component -> {
				assertThat(component.status()).isEqualTo("UNAVAILABLE");
				assertThat(component.reason()).contains("푸시 자동 발송이 꺼져 있습니다.");
			});
	}
}
