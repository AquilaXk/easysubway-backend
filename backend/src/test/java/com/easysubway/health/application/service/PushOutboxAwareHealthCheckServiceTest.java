package com.easysubway.health.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.easysubway.health.application.port.in.CheckHealthUseCase;
import com.easysubway.health.domain.HealthComponent;
import com.easysubway.health.domain.HealthStatus;
import com.easysubway.notification.domain.PushDeliveryAvailability;
import com.easysubway.transit.adapter.out.persistence.InMemoryTransitMasterRepository;
import java.sql.Connection;
import java.util.List;
import javax.sql.DataSource;
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

	@Test
	@DisplayName("실제 HealthCheckService 결과와 함께 써도 pushOutbox 하나만 교체하고 나머지는 그대로다")
	void replacesOnlyPushOutboxOfRealHealthCheck() throws Exception {
		HealthCheckService real = new HealthCheckService(availableDataSource(), new InMemoryTransitMasterRepository());
		HealthStatus base = real.checkHealth();
		assertThat(base.components()).filteredOn(component -> component.name().equals("pushOutbox")).hasSize(1);

		for (var scenario : List.<java.util.function.Supplier<PushDeliveryAvailability>>of(
			() -> new PushDeliveryAvailability(true, List.of()),
			() -> new PushDeliveryAvailability(false, List.of("사유")),
			() -> {
				throw new IllegalStateException("boom");
			}
		)) {
			HealthStatus decorated = new PushOutboxAwareHealthCheckService(real, scenario::get).checkHealth();

			assertThat(decorated.status()).isEqualTo(base.status());
			assertThat(decorated.service()).isEqualTo(base.service());
			assertThat(decorated.components()).extracting("name").containsExactlyElementsOf(
				base.components().stream().map(HealthComponent::name).toList());
			assertThat(decorated.components())
				.filteredOn(component -> !component.name().equals("pushOutbox"))
				.containsExactlyElementsOf(
					base.components().stream().filter(component -> !component.name().equals("pushOutbox")).toList());
			assertThat(decorated.components())
				.filteredOn(component -> component.name().equals("pushOutbox"))
				.singleElement()
				.isNotEqualTo(base.components().stream().filter(c -> c.name().equals("pushOutbox")).findFirst().orElseThrow());
		}
	}

	@Test
	@DisplayName("실제 HealthCheckService와 함께 available·unavailable·예외 상태를 각각 보고한다")
	void reportsAllThreeStatesWithRealHealthCheck() throws Exception {
		HealthCheckService real = new HealthCheckService(availableDataSource(), new InMemoryTransitMasterRepository());

		assertThat(pushOutbox(new PushOutboxAwareHealthCheckService(real, () -> new PushDeliveryAvailability(true, List.of()))))
			.extracting(HealthComponent::status, HealthComponent::reason)
			.containsExactly("UP", "푸시 발송이 가능합니다.");
		assertThat(pushOutbox(new PushOutboxAwareHealthCheckService(
			real, () -> new PushDeliveryAvailability(false, List.of("사유 A", "사유 B")))))
			.extracting(HealthComponent::status, HealthComponent::reason)
			.containsExactly("UNAVAILABLE", "푸시 발송 불가: 사유 A 사유 B");
		assertThat(pushOutbox(new PushOutboxAwareHealthCheckService(real, () -> {
			throw new IllegalStateException("boom");
		})))
			.extracting(HealthComponent::status, HealthComponent::reason)
			.containsExactly("UNKNOWN", "푸시 발송 가능 여부를 확인할 수 없습니다.");
	}

	private static HealthComponent pushOutbox(CheckHealthUseCase useCase) {
		return useCase.checkHealth().components().stream()
			.filter(component -> component.name().equals("pushOutbox"))
			.findFirst()
			.orElseThrow();
	}

	private static DataSource availableDataSource() throws Exception {
		DataSource dataSource = mock(DataSource.class);
		Connection connection = mock(Connection.class);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.isValid(2)).thenReturn(true);
		return dataSource;
	}
}
