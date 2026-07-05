package com.easysubway.admin.operations.application.port.out;

import com.easysubway.admin.operations.domain.AdminIncident;
import com.easysubway.admin.operations.domain.AdminIncidentTransition;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface AdminIncidentRepository {

	List<AdminIncident> findRecent(int limit);

	default List<AdminIncident> findRecent(int limit, int offset) {
		return offset <= 0 ? findRecent(limit) : List.of();
	}

	Optional<AdminIncident> findById(String incidentId);

	AdminIncident save(AdminIncident incident);

	void saveTransition(AdminIncidentTransition transition);

	List<AdminIncidentTransition> findTransitions(String incidentId);

	/**
	 * 여러 incident의 전이 이력을 한 번에 읽는다. 목록 화면 타임라인의 incident별 N+1을 피하기 위한 벌크 조회다.
	 * 결과는 incident id → 오래된 순 전이 목록.
	 */
	Map<String, List<AdminIncidentTransition>> findTransitions(Collection<String> incidentIds);
}
