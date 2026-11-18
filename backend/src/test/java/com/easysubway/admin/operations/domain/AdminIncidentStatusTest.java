package com.easysubway.admin.operations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("장애 처리 상태 전이 규칙")
class AdminIncidentStatusTest {

	@Test
	@DisplayName("접수 → 조치 중 → 모니터링 → 종결 정방향 전이를 허용한다")
	void allowsForwardTransitions() {
		assertThat(AdminIncidentStatus.RECEIVED.canTransitionTo(AdminIncidentStatus.IN_PROGRESS)).isTrue();
		assertThat(AdminIncidentStatus.IN_PROGRESS.canTransitionTo(AdminIncidentStatus.MONITORING)).isTrue();
		assertThat(AdminIncidentStatus.MONITORING.canTransitionTo(AdminIncidentStatus.RESOLVED)).isTrue();
	}

	@Test
	@DisplayName("모니터링 → 조치 중 역방향 전이를 허용한다")
	void allowsReverseFromMonitoringToInProgress() {
		assertThat(AdminIncidentStatus.MONITORING.canTransitionTo(AdminIncidentStatus.IN_PROGRESS)).isTrue();
	}

	@Test
	@DisplayName("단계를 건너뛰거나 역행하는 전이를 거부한다")
	void rejectsSkippingAndBackwardsTransitions() {
		assertThat(AdminIncidentStatus.RECEIVED.canTransitionTo(AdminIncidentStatus.MONITORING)).isFalse();
		assertThat(AdminIncidentStatus.RECEIVED.canTransitionTo(AdminIncidentStatus.RESOLVED)).isFalse();
		assertThat(AdminIncidentStatus.IN_PROGRESS.canTransitionTo(AdminIncidentStatus.RECEIVED)).isFalse();
		assertThat(AdminIncidentStatus.IN_PROGRESS.canTransitionTo(AdminIncidentStatus.RESOLVED)).isFalse();
	}

	@Test
	@DisplayName("종결은 최종 상태로 어떤 전이도 허용하지 않는다")
	void resolvedIsTerminal() {
		assertThat(AdminIncidentStatus.RESOLVED.isTerminal()).isTrue();
		assertThat(AdminIncidentStatus.RESOLVED.canTransitionTo(AdminIncidentStatus.IN_PROGRESS)).isFalse();
		assertThat(AdminIncidentStatus.RECEIVED.isTerminal()).isFalse();
	}

	@Test
	@DisplayName("레거시 OPEN 상태는 접수로 이관한다")
	void legacyOpenMapsToReceived() {
		assertThat(AdminIncidentStatus.from("OPEN")).isEqualTo(AdminIncidentStatus.RECEIVED);
		assertThat(AdminIncidentStatus.from("received")).isEqualTo(AdminIncidentStatus.RECEIVED);
		assertThat(AdminIncidentStatus.from("MONITORING")).isEqualTo(AdminIncidentStatus.MONITORING);
	}

	@Test
	@DisplayName("알 수 없는 상태 문자열은 거부한다")
	void rejectsUnknownStatus() {
		assertThatThrownBy(() -> AdminIncidentStatus.from("CLOSED"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("CLOSED");
	}
}
