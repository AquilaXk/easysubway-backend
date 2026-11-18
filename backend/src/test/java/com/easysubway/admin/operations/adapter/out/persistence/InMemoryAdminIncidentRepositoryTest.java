package com.easysubway.admin.operations.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.admin.operations.domain.AdminIncident;
import com.easysubway.admin.operations.domain.AdminIncidentStatus;
import com.easysubway.admin.operations.domain.AdminIncidentTransition;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("인메모리 관리자 incident 저장소")
class InMemoryAdminIncidentRepositoryTest {

	private final InMemoryAdminIncidentRepository repository = new InMemoryAdminIncidentRepository();

	@Test
	@DisplayName("최근 incident는 JDBC와 같은 openedAt desc, incidentId desc 순서로 조회한다")
	void findRecentOrdersLikeJdbcRepository() {
		LocalDateTime openedAt = LocalDateTime.parse("2026-06-27T00:00:00");
		repository.save(incident("INC-A", openedAt));
		repository.save(incident("INC-C", openedAt));
		repository.save(incident("INC-B", openedAt));

		assertThat(repository.findRecent(3))
			.extracting(AdminIncident::incidentId)
			.containsExactly("INC-C", "INC-B", "INC-A");
	}

	@Test
	@DisplayName("전이 이력을 incident별로 오래된 순으로 벌크 조회한다")
	void findsTransitionsInBulkOrderedByChangedAt() {
		LocalDateTime base = LocalDateTime.parse("2026-06-27T00:00:00");
		repository.saveTransition(transition("INC-A", null, AdminIncidentStatus.RECEIVED, base));
		repository.saveTransition(transition("INC-B", null, AdminIncidentStatus.RECEIVED, base.plusMinutes(1)));
		repository.saveTransition(transition("INC-A", AdminIncidentStatus.RECEIVED, AdminIncidentStatus.IN_PROGRESS, base.plusMinutes(2)));

		var byIncident = repository.findTransitions(List.of("INC-A", "INC-B"));

		assertThat(byIncident.get("INC-A"))
			.extracting(AdminIncidentTransition::toStatus)
			.containsExactly(AdminIncidentStatus.RECEIVED, AdminIncidentStatus.IN_PROGRESS);
		assertThat(byIncident.get("INC-B"))
			.singleElement()
			.satisfies(step -> assertThat(step.isInitial()).isTrue());
	}

	private static AdminIncidentTransition transition(
		String incidentId,
		AdminIncidentStatus from,
		AdminIncidentStatus to,
		LocalDateTime at
	) {
		return new AdminIncidentTransition(incidentId, from, to, at, "ops", null);
	}

	private static AdminIncident incident(String incidentId, LocalDateTime openedAt) {
		return new AdminIncident(
			incidentId,
			"MAJOR",
			AdminIncidentStatus.RECEIVED,
			"HEALTH",
			"database DOWN",
			"ops",
			openedAt,
			null,
			null,
			null,
			null
		);
	}
}
