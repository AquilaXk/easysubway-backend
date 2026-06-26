package com.easysubway.health.adapter.out.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.health.domain.HealthComponent;
import com.easysubway.health.domain.HealthStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;

@DisplayName("백엔드 컴포넌트 actuator health indicator")
class BackendComponentHealthIndicatorTest {

	@Test
	@DisplayName("top-level health status를 actuator status에 그대로 반영한다")
	void healthPreservesSummaryStatus() {
		BackendComponentHealthIndicator indicator = new BackendComponentHealthIndicator(() -> HealthStatus.of(
			"DEGRADED",
			"easysubway-backend",
			List.of(new HealthComponent("database", "DEGRADED", "데이터베이스", "응답 지연"))
		));

		Health health = indicator.health();

		assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
		assertThat(health.getDetails()).containsEntry("summaryStatus", "DEGRADED");
	}
}
