package com.easysubway.admin.operations.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.admin.operations.domain.AdminIncident;
import java.time.LocalDateTime;
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

	private static AdminIncident incident(String incidentId, LocalDateTime openedAt) {
		return new AdminIncident(
			incidentId,
			"MAJOR",
			"OPEN",
			"HEALTH",
			"database DOWN",
			"ops",
			openedAt,
			null,
			null
		);
	}
}
