package com.easysubway.admin.operations.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.admin.code.adapter.out.persistence.InMemoryAdminCommonCodeRepository;
import com.easysubway.admin.code.application.service.AdminCommonCodeService;
import com.easysubway.admin.operations.adapter.out.persistence.InMemoryAdminIncidentRepository;
import com.easysubway.admin.operations.application.service.AdminIncidentService.OpenAdminIncidentCommand;
import com.easysubway.admin.operations.domain.AdminIncident;
import com.easysubway.common.error.InvalidRequestException;
import com.easysubway.health.domain.HealthComponent;
import com.easysubway.health.domain.HealthStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관리자 장애관리 서비스")
class AdminIncidentServiceTest {

	private final AdminIncidentService service = new AdminIncidentService(
		new InMemoryAdminIncidentRepository(),
		new AdminCommonCodeService(new InMemoryAdminCommonCodeRepository())
	);

	@Test
	@DisplayName("incident를 생성하고 해결 기록을 남긴다")
	void openAndResolveIncident() {
		AdminIncident opened = service.open(new OpenAdminIncidentCommand(
			"MAJOR",
			"OPEN",
			"HEALTH",
			"database DOWN",
			"ops"
		));

		AdminIncident resolved = service.resolve(opened.incidentId(), "DB connection restored");

		assertThat(opened.incidentId()).startsWith("INC-");
		assertThat(resolved.status()).isEqualTo("RESOLVED");
		assertThat(resolved.resolvedAt()).isNotNull();
		assertThat(resolved.resolution()).isEqualTo("DB connection restored");
	}

	@Test
	@DisplayName("미해결 incident는 해결 필드를 가질 수 없다")
	void unresolvedIncidentCannotHaveResolutionFields() {
		assertThatThrownBy(() -> new AdminIncident(
			"INC-OPEN",
			"MAJOR",
			"OPEN",
			"HEALTH",
			"database DOWN",
			"ops",
			LocalDateTime.parse("2026-06-27T00:00:00"),
			null,
			"already fixed"
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("열린 incident는 resolvedAt과 resolution을 가질 수 없습니다.");
	}

	@Test
	@DisplayName("새 incident는 OPEN 상태로만 생성할 수 있다")
	void openRejectsResolvedStatus() {
		assertThatThrownBy(() -> service.open(new OpenAdminIncidentCommand(
			"MAJOR",
			"RESOLVED",
			"HEALTH",
			"database restored",
			"ops"
		))).isInstanceOf(InvalidRequestException.class)
			.hasMessageContaining("OPEN");
	}

	@Test
	@DisplayName("health DOWN 상태는 incident 생성 후보로 연결된다")
	void openFromHealthStatus() {
		HealthStatus health = HealthStatus.of(
			"DOWN",
			"easysubway-backend",
			List.of(new HealthComponent("database", "DOWN", "데이터베이스", "DB 연결 실패"))
		);

		AdminIncident incident = service.openFromHealth(health, "ops");

		assertThat(incident.severity()).isEqualTo("MAJOR");
		assertThat(incident.source()).isEqualTo("HEALTH");
		assertThat(incident.summary()).contains("Health DOWN", "database DOWN");
	}
}
