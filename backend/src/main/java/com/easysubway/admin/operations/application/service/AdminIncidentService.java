package com.easysubway.admin.operations.application.service;

import com.easysubway.admin.code.application.service.AdminCommonCodeService;
import com.easysubway.admin.code.domain.AdminCommonCode;
import com.easysubway.admin.code.domain.AdminCommonCodeGroups;
import com.easysubway.admin.operations.application.port.out.AdminIncidentRepository;
import com.easysubway.admin.operations.domain.AdminIncident;
import com.easysubway.common.error.InvalidRequestException;
import com.easysubway.health.domain.HealthComponent;
import com.easysubway.health.domain.HealthStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AdminIncidentService {

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

	public AdminIncident open(OpenAdminIncidentCommand command) {
		requireEnabled(AdminCommonCodeGroups.INCIDENT_SEVERITY, command.severity());
		requireEnabled(AdminCommonCodeGroups.INCIDENT_STATUS, command.status());
		requireEnabled(AdminCommonCodeGroups.INCIDENT_SOURCE, command.source());
		if (!"OPEN".equals(command.status())) {
			throw new InvalidRequestException("새 incident는 OPEN 상태로만 생성할 수 있습니다.");
		}
		LocalDateTime now = LocalDateTime.now(clock);
		return repository.save(new AdminIncident(
			nextId(),
			command.severity(),
			command.status(),
			command.source(),
			command.summary(),
			command.owner(),
			now,
			null,
			null
		));
	}

	public AdminIncident openFromHealth(HealthStatus health, String owner) {
		if (health == null || "UP".equals(health.status())) {
			throw new InvalidRequestException("incident로 연결할 health 이상 상태가 없습니다.");
		}
		String severity = "DOWN".equals(health.status()) ? "MAJOR" : "MINOR";
		String summary = "Health %s: %s".formatted(health.status(), componentSummary(health.components()));
		return open(new OpenAdminIncidentCommand(severity, "OPEN", "HEALTH", summary, owner));
	}

	public AdminIncident resolve(String incidentId, String resolution) {
		AdminIncident incident = repository.findById(incidentId)
			.orElseThrow(() -> new InvalidRequestException("incident를 찾을 수 없습니다."));
		if ("RESOLVED".equals(incident.status())) {
			throw new InvalidRequestException("이미 해결된 incident입니다.");
		}
		return repository.save(incident.resolve(resolution, LocalDateTime.now(clock)));
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
		String owner
	) {
	}
}
