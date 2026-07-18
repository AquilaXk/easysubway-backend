package com.easysubway.admin.operations.application.service;

import com.easysubway.admin.code.application.service.AdminCommonCodeService;
import com.easysubway.admin.code.domain.AdminCommonCode;
import com.easysubway.admin.code.domain.AdminCommonCodeGroups;
import com.easysubway.admin.operations.application.port.out.AdminIncidentRepository;
import com.easysubway.admin.operations.domain.AdminIncident;
import com.easysubway.admin.operations.domain.AdminIncidentStatus;
import com.easysubway.admin.operations.domain.AdminIncidentTransition;
import com.easysubway.common.error.ConflictException;
import com.easysubway.common.error.InvalidRequestException;
import com.easysubway.health.domain.HealthComponent;
import com.easysubway.health.domain.HealthStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AdminIncidentService {

	private static final String INITIAL_STATUS = AdminIncidentStatus.RECEIVED.name();

	private final AdminIncidentRepository repository;
	private final AdminCommonCodeService commonCodeService;
	private final Clock clock;

	@Autowired
	public AdminIncidentService(AdminIncidentRepository repository, AdminCommonCodeService commonCodeService) {
		this(repository, commonCodeService, Clock.systemUTC());
	}

	AdminIncidentService(AdminIncidentRepository repository, AdminCommonCodeService commonCodeService, Clock clock) {
		this.repository = repository;
		this.commonCodeService = commonCodeService;
		this.clock = clock;
	}

	public List<AdminIncident> listRecent(int limit) {
		return repository.findRecent(limit);
	}

	public List<AdminIncident> listRecent(int limit, int offset) {
		return repository.findRecent(limit, offset);
	}

	public List<AdminIncidentTransition> listTransitions(String incidentId) {
		return repository.findTransitions(incidentId);
	}

	public Map<String, List<AdminIncidentTransition>> listTransitions(Collection<String> incidentIds) {
		return incidentIds.isEmpty() ? Map.of() : repository.findTransitions(incidentIds);
	}

	public AdminIncident open(OpenAdminIncidentCommand command) {
		requireEnabled(AdminCommonCodeGroups.INCIDENT_SEVERITY, command.severity());
		requireEnabled(AdminCommonCodeGroups.INCIDENT_STATUS, command.status());
		requireEnabled(AdminCommonCodeGroups.INCIDENT_SOURCE, command.source());
		if (!INITIAL_STATUS.equals(command.status())) {
			throw new InvalidRequestException("새 incident는 접수(RECEIVED) 상태로만 생성할 수 있습니다.");
		}
		LocalDateTime now = LocalDateTime.now(clock);
		AdminIncident incident = repository.save(new AdminIncident(
			nextId(),
			command.severity(),
			AdminIncidentStatus.RECEIVED,
			command.source(),
			command.summary(),
			command.owner(),
			now,
			null,
			null,
			command.stationId(),
			command.lineId()
		));
		repository.saveTransition(new AdminIncidentTransition(
			incident.incidentId(),
			null,
			AdminIncidentStatus.RECEIVED,
			now,
			command.owner(),
			"접수"
		));
		return incident;
	}

	public AdminIncident openFromHealth(HealthStatus health, String owner) {
		if (health == null || "UP".equals(health.status())) {
			throw new InvalidRequestException("incident로 연결할 health 이상 상태가 없습니다.");
		}
		String severity = "DOWN".equals(health.status()) ? "MAJOR" : "MINOR";
		String summary = "Health %s: %s".formatted(health.status(), componentSummary(health.components()));
		return open(new OpenAdminIncidentCommand(severity, INITIAL_STATUS, "HEALTH", summary, owner, null, null));
	}

	/**
	 * 장애 상태를 대상 상태로 전이하고 전이 이력을 남긴다. 종결로 전이할 때만 resolution을 요구한다.
	 * 전이 허용 규칙은 {@link AdminIncidentStatus}가 강제한다.
	 */
	public AdminIncident transition(String incidentId, String targetStatus, String changedBy, String note, String resolution) {
		AdminIncident incident = repository.findById(incidentId)
			.orElseThrow(() -> new InvalidRequestException("incident를 찾을 수 없습니다."));
		AdminIncidentStatus target = parseStatus(targetStatus);
		if (!incident.status().canTransitionTo(target)) {
			throw new InvalidRequestException("%s에서 %s로 전이할 수 없습니다."
				.formatted(incident.status().label(), target.label()));
		}
		if (target.isResolved() && (resolution == null || resolution.isBlank())) {
			throw new InvalidRequestException("종결 전이에는 해결 기록이 필요합니다.");
		}
		AdminIncidentStatus fromStatus = incident.status();
		LocalDateTime now = LocalDateTime.now(clock);
		AdminIncident updated = incident.transitionTo(target, now, target.isResolved() ? resolution : null);
		if (!repository.compareAndSetStatus(updated, fromStatus)) {
			throw new ConflictException(
				"다른 담당자가 먼저 상태를 변경했습니다. 최신 상태를 다시 확인한 뒤 전이해 주세요.");
		}
		repository.saveTransition(new AdminIncidentTransition(
			incidentId,
			fromStatus,
			target,
			now,
			changedBy,
			note
		));
		return updated;
	}

	private static AdminIncidentStatus parseStatus(String targetStatus) {
		try {
			return AdminIncidentStatus.from(targetStatus);
		} catch (IllegalArgumentException exception) {
			throw new InvalidRequestException("알 수 없는 장애 상태입니다.");
		}
	}

	private void requireEnabled(String groupCode, String code) {
		boolean enabled = commonCodeService.enabledCodes(groupCode)
			.stream()
			.map(AdminCommonCode::code)
			.anyMatch(candidate -> candidate.equals(code));
		if (!enabled) {
			throw new InvalidRequestException("선택할 수 없는 운영 코드입니다.");
		}
	}

	private static String componentSummary(List<HealthComponent> components) {
		return components.stream()
			.filter(component -> !"UP".equals(component.status()))
			.findFirst()
			.map(component -> "%s %s".formatted(component.name(), component.status()))
			.orElse("summary");
	}

	private static String nextId() {
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
		return "INC-" + suffix;
	}

	public record OpenAdminIncidentCommand(
		String severity,
		String status,
		String source,
		String summary,
		String owner,
		String stationId,
		String lineId
	) {
	}
}
