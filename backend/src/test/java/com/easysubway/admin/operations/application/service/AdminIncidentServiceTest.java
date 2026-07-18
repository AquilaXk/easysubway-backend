package com.easysubway.admin.operations.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.admin.code.adapter.out.persistence.InMemoryAdminCommonCodeRepository;
import com.easysubway.admin.code.application.service.AdminCommonCodeService;
import com.easysubway.admin.operations.adapter.out.persistence.InMemoryAdminIncidentRepository;
import com.easysubway.admin.operations.application.service.AdminIncidentService.OpenAdminIncidentCommand;
import com.easysubway.admin.operations.domain.AdminIncident;
import com.easysubway.admin.operations.domain.AdminIncidentStatus;
import com.easysubway.admin.operations.domain.AdminIncidentTransition;
import com.easysubway.common.error.ConflictException;
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
	@DisplayName("접수 → 조치 중 → 모니터링 → 종결 워크플로를 전이하고 타임라인을 남긴다")
	void openAndTransitionThroughWorkflow() {
		AdminIncident opened = service.open(new OpenAdminIncidentCommand(
			"MAJOR",
			"RECEIVED",
			"HEALTH",
			"database DOWN",
			"ops",
			null,
			null
		));

		service.transition(opened.incidentId(), "IN_PROGRESS", "ops", "복구 시작", null);
		service.transition(opened.incidentId(), "MONITORING", "ops", "복구 확인", null);
		AdminIncident resolved = service.transition(opened.incidentId(), "RESOLVED", "ops", null, "DB connection restored");

		assertThat(opened.incidentId()).startsWith("INC-");
		assertThat(opened.status()).isEqualTo(AdminIncidentStatus.RECEIVED);
		assertThat(resolved.status()).isEqualTo(AdminIncidentStatus.RESOLVED);
		assertThat(resolved.resolvedAt()).isNotNull();
		assertThat(resolved.resolution()).isEqualTo("DB connection restored");

		List<AdminIncidentTransition> timeline = service.listTransitions(opened.incidentId());
		assertThat(timeline)
			.extracting(AdminIncidentTransition::toStatus)
			.containsExactly(
				AdminIncidentStatus.RECEIVED,
				AdminIncidentStatus.IN_PROGRESS,
				AdminIncidentStatus.MONITORING,
				AdminIncidentStatus.RESOLVED
			);
		assertThat(timeline.getFirst().isInitial()).isTrue();
	}

	@Test
	@DisplayName("상반된 동시 전이는 compare-and-set로 정확히 하나만 성공하고 나머지는 충돌로 거부된다")
	void concurrentOpposingTransitionsKeepExactlyOneWinner() {
		var repository = new RacingIncidentRepository();
		var service = new AdminIncidentService(
			repository, new AdminCommonCodeService(new InMemoryAdminCommonCodeRepository()));
		AdminIncident opened = service.open(
			new OpenAdminIncidentCommand("MAJOR", "RECEIVED", "HEALTH", "database DOWN", "ops", null, null));
		String id = opened.incidentId();
		service.transition(id, "IN_PROGRESS", "ops", null, null);
		service.transition(id, "MONITORING", "ops", null, null);

		// 운영자 B가 MONITORING을 읽고 조치 중 재개를 시도하는 창(window)에서,
		// 운영자 A가 먼저 종결(상반된 전이)을 커밋한다.
		repository.commitInNextCasWindow(() -> service.transition(id, "RESOLVED", "opsA", null, "DB 복구"));

		assertThatThrownBy(() -> service.transition(id, "IN_PROGRESS", "opsB", "재개 시도", null))
			.isInstanceOf(ConflictException.class)
			.hasMessageContaining("다른 담당자가 먼저 상태를 변경");

		AdminIncident current = repository.findById(id).orElseThrow();
		assertThat(current.status()).isEqualTo(AdminIncidentStatus.RESOLVED);

		List<AdminIncidentTransition> timeline = service.listTransitions(id);
		// 충돌로 거부된 B의 전이(IN_PROGRESS 재개)는 기록되지 않는다.
		assertThat(timeline)
			.extracting(AdminIncidentTransition::toStatus)
			.containsExactly(
				AdminIncidentStatus.RECEIVED,
				AdminIncidentStatus.IN_PROGRESS,
				AdminIncidentStatus.MONITORING,
				AdminIncidentStatus.RESOLVED
			);
		// current 상태는 항상 history 마지막 to_status와 일치한다.
		assertThat(timeline.getLast().toStatus()).isEqualTo(current.status());
	}

	@Test
	@DisplayName("모니터링 → 조치 중 역방향 전이를 허용한다")
	void allowsReverseFromMonitoringToInProgress() {
		AdminIncident opened = openReceived();
		service.transition(opened.incidentId(), "IN_PROGRESS", "ops", null, null);
		service.transition(opened.incidentId(), "MONITORING", "ops", null, null);

		AdminIncident reopened = service.transition(opened.incidentId(), "IN_PROGRESS", "ops", "재발 조치", null);

		assertThat(reopened.status()).isEqualTo(AdminIncidentStatus.IN_PROGRESS);
		assertThat(reopened.resolvedAt()).isNull();
	}

	@Test
	@DisplayName("단계를 건너뛰는 전이는 거부한다")
	void rejectsSkippingTransition() {
		AdminIncident opened = openReceived();

		assertThatThrownBy(() -> service.transition(opened.incidentId(), "RESOLVED", "ops", null, "즉시 종결"))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessageContaining("전이할 수 없습니다");
	}

	@Test
	@DisplayName("종결 전이에는 해결 기록이 필요하다")
	void resolveRequiresResolution() {
		AdminIncident opened = openReceived();
		service.transition(opened.incidentId(), "IN_PROGRESS", "ops", null, null);
		service.transition(opened.incidentId(), "MONITORING", "ops", null, null);

		assertThatThrownBy(() -> service.transition(opened.incidentId(), "RESOLVED", "ops", null, "  "))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessageContaining("해결 기록");
	}

	@Test
	@DisplayName("미해결 incident는 해결 필드를 가질 수 없다")
	void unresolvedIncidentCannotHaveResolutionFields() {
		assertThatThrownBy(() -> new AdminIncident(
			"INC-OPEN",
			"MAJOR",
			AdminIncidentStatus.RECEIVED,
			"HEALTH",
			"database DOWN",
			"ops",
			LocalDateTime.parse("2026-06-27T00:00:00"),
			null,
			"already fixed",
			null,
			null
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("열린 incident는 resolvedAt과 resolution을 가질 수 없습니다.");
	}

	@Test
	@DisplayName("새 incident는 접수 상태로만 생성할 수 있다")
	void openRejectsNonReceivedStatus() {
		assertThatThrownBy(() -> service.open(new OpenAdminIncidentCommand(
			"MAJOR",
			"RESOLVED",
			"HEALTH",
			"database restored",
			"ops",
			null,
			null
		))).isInstanceOf(InvalidRequestException.class)
			.hasMessageContaining("접수");
	}

	@Test
	@DisplayName("health DOWN 상태는 접수 incident 생성 후보로 연결된다")
	void openFromHealthStatus() {
		HealthStatus health = HealthStatus.of(
			"DOWN",
			"easysubway-backend",
			List.of(new HealthComponent("database", "DOWN", "데이터베이스", "DB 연결 실패"))
		);

		AdminIncident incident = service.openFromHealth(health, "ops");

		assertThat(incident.severity()).isEqualTo("MAJOR");
		assertThat(incident.source()).isEqualTo("HEALTH");
		assertThat(incident.status()).isEqualTo(AdminIncidentStatus.RECEIVED);
		assertThat(incident.summary()).contains("Health DOWN", "database DOWN");
	}

	private AdminIncident openReceived() {
		return service.open(new OpenAdminIncidentCommand("MAJOR", "RECEIVED", "HEALTH", "database DOWN", "ops", null, null));
	}

	/**
	 * compare-and-set 창에서 상반된 동시 전이를 결정적으로 재현하는 저장소.
	 * 다음 {@code compareAndSetStatus} 호출 직전에 예약된 경쟁 write를 한 번 실행한다.
	 */
	private static final class RacingIncidentRepository extends InMemoryAdminIncidentRepository {

		private Runnable pendingConcurrentCommit;

		void commitInNextCasWindow(Runnable concurrentCommit) {
			this.pendingConcurrentCommit = concurrentCommit;
		}

		@Override
		public boolean compareAndSetStatus(AdminIncident next, AdminIncidentStatus expectedStatus) {
			if (pendingConcurrentCommit != null) {
				Runnable concurrentCommit = pendingConcurrentCommit;
				pendingConcurrentCommit = null;
				concurrentCommit.run();
			}
			return super.compareAndSetStatus(next, expectedStatus);
		}
	}
}
