package com.easysubway.admin.operations.application.port.out;

import com.easysubway.admin.operations.domain.AdminIncident;
import com.easysubway.admin.operations.domain.AdminIncidentStatus;
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

	/**
	 * incident_id와 {@code expectedStatus}가 모두 일치하는 행만 {@code next} 상태로 갱신하는 compare-and-set.
	 * 동시 전이로 기대 상태가 이미 바뀌었으면(영향 행 0) 아무 것도 바꾸지 않고 {@code false}를 돌려준다.
	 *
	 * <p>기본 구현은 읽고-검사-쓰기라 단일 스레드 컨텍스트(테스트·인메모리 프로파일)의 의미만 보장한다.
	 * 실제 운영 동시성 정합은 이 메서드를 원자적 조건부 UPDATE로 override하는 저장소가 책임진다.
	 */
	default boolean compareAndSetStatus(AdminIncident next, AdminIncidentStatus expectedStatus) {
		return findById(next.incidentId())
			.filter(current -> current.status() == expectedStatus)
			.map(current -> {
				save(next);
				return true;
			})
			.orElse(false);
	}

	void saveTransition(AdminIncidentTransition transition);

	List<AdminIncidentTransition> findTransitions(String incidentId);

	/**
	 * 여러 incident의 전이 이력을 한 번에 읽는다. 목록 화면 타임라인의 incident별 N+1을 피하기 위한 벌크 조회다.
	 * 결과는 incident id → 오래된 순 전이 목록.
	 */
	Map<String, List<AdminIncidentTransition>> findTransitions(Collection<String> incidentIds);
}
