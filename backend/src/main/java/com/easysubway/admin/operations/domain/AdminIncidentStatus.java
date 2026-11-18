package com.easysubway.admin.operations.domain;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 장애 처리 상태와 전이 규칙을 캡슐화한다.
 *
 * <p>운영 콘솔(교통 SPOT·Trapeze RISC)의 접수 → 조치 → 모니터 → 종결 워크플로를 따른다.
 * 정방향 진행과 모니터링 → 조치 중 역방향 재개만 허용하고, 종결은 최종 상태다.
 */
public enum AdminIncidentStatus {

	RECEIVED("접수"),
	IN_PROGRESS("조치 중"),
	MONITORING("모니터링"),
	RESOLVED("종결");

	private static final Map<AdminIncidentStatus, Set<AdminIncidentStatus>> ALLOWED_TRANSITIONS = Map.of(
		RECEIVED, Set.of(IN_PROGRESS),
		IN_PROGRESS, Set.of(MONITORING),
		MONITORING, Set.of(IN_PROGRESS, RESOLVED),
		RESOLVED, Set.of()
	);

	private final String label;

	AdminIncidentStatus(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	public boolean isResolved() {
		return this == RESOLVED;
	}

	public boolean isTerminal() {
		return ALLOWED_TRANSITIONS.get(this).isEmpty();
	}

	public boolean canTransitionTo(AdminIncidentStatus target) {
		return target != null && ALLOWED_TRANSITIONS.get(this).contains(target);
	}

	public Set<AdminIncidentStatus> allowedTransitions() {
		return ALLOWED_TRANSITIONS.get(this);
	}

	/**
	 * 저장된 상태 문자열을 enum으로 변환한다. 4상태 전이 도입 이전의 레거시 {@code OPEN}은 접수({@link #RECEIVED})로 본다.
	 */
	public static AdminIncidentStatus from(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException("incident status가 필요합니다.");
		}
		String normalized = raw.trim().toUpperCase(Locale.ROOT);
		if ("OPEN".equals(normalized)) {
			return RECEIVED;
		}
		try {
			return valueOf(normalized);
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("알 수 없는 incident status입니다: " + raw);
		}
	}
}
