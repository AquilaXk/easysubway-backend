package com.easysubway.admin.operations.adapter.in.web;

import com.easysubway.admin.audit.application.service.AdminAuditWriter;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import com.easysubway.admin.code.application.service.AdminCommonCodeService;
import com.easysubway.admin.code.application.service.AdminCommonCodeService.SaveAdminCommonCodeCommand;
import com.easysubway.admin.code.domain.AdminCommonCode;
import com.easysubway.admin.code.domain.AdminCommonCodeGroup;
import com.easysubway.admin.code.domain.AdminCommonCodeGroups;
import com.easysubway.admin.operations.application.service.AdminIncidentService;
import com.easysubway.admin.operations.application.service.AdminIncidentService.OpenAdminIncidentCommand;
import com.easysubway.admin.operations.domain.AdminIncident;
import com.easysubway.admin.operations.domain.AdminIncidentStatus;
import com.easysubway.admin.operations.domain.AdminIncidentTransition;
import com.easysubway.common.web.pagination.AdminPageRequest;
import com.easysubway.common.web.pagination.EgovPaginationView;
import com.easysubway.health.application.port.in.CheckHealthUseCase;
import com.easysubway.health.domain.HealthStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
class AdminOperationsPageController {

	private final AdminCommonCodeService commonCodeService;
	private final AdminIncidentService incidentService;
	private final CheckHealthUseCase checkHealthUseCase;
	private final AdminAuditWriter auditWriter;

	AdminOperationsPageController(
		AdminCommonCodeService commonCodeService,
		AdminIncidentService incidentService,
		CheckHealthUseCase checkHealthUseCase,
		AdminAuditWriter auditWriter
	) {
		this.commonCodeService = commonCodeService;
		this.incidentService = incidentService;
		this.checkHealthUseCase = checkHealthUseCase;
		this.auditWriter = auditWriter;
	}

	@GetMapping("/admin/codes/page")
	String codesPage(
		@RequestParam(required = false) String groupCode,
		@RequestParam(required = false) Integer page,
		@RequestParam(required = false) Integer size,
		Model model
	) {
		List<AdminCommonCodeGroup> groups = commonCodeService.listGroups();
		String selectedGroup = selectedGroup(groupCode, groups);
		AdminPageRequest pageRequest = AdminPageRequest.of(page, size);
		List<CodeRow> codes = commonCodeService.listCodes(selectedGroup, true).stream().map(CodeRow::from).toList();
		EgovPaginationView pageView = EgovPaginationView.from(pageRequest.page(), pageRequest.size(), codes.size());
		model.addAttribute("groups", groups.stream().map(CodeGroupRow::from).toList());
		model.addAttribute("selectedGroup", selectedGroup);
		model.addAttribute("codes", pageView.pageItems(codes));
		model.addAttribute("page", pageView);
		model.addAttribute("paginationLinks", pageView.links(
			"/admin/codes/page",
			Collections.singletonMap("groupCode", selectedGroup)
		));
		return "admin/codes/list";
	}

	@PostMapping("/admin/codes")
	@Transactional
	String saveCode(
		@RequestParam String groupCode,
		@RequestParam String code,
		@RequestParam String displayName,
		@RequestParam(required = false) String description,
		@RequestParam(defaultValue = "0") int sortOrder,
		@RequestParam(defaultValue = "false") boolean enabled,
		Authentication authentication,
		HttpServletRequest request
	) {
		AdminCommonCode saved = commonCodeService.saveCode(new SaveAdminCommonCodeCommand(
			groupCode,
			code,
			displayName,
			description,
			sortOrder,
			enabled
		));
		auditWriter.commonCodeChange(
			authentication,
			request,
			auditCodeTarget(saved),
			"UPSERT_COMMON_CODE",
			AdminAuditOutcome.SUCCESS,
			"enabled=%s".formatted(saved.enabled())
		);
		return "redirect:/admin/codes/page?groupCode=" + saved.groupCode();
	}

	@PostMapping("/admin/codes/{groupCode}/{code}/disable")
	@Transactional
	String disableCode(
		@PathVariable String groupCode,
		@PathVariable String code,
		Authentication authentication,
		HttpServletRequest request
	) {
		AdminCommonCode disabled = commonCodeService.disableCode(groupCode, code);
		auditWriter.commonCodeChange(
			authentication,
			request,
			auditCodeTarget(disabled),
			"DISABLE_COMMON_CODE",
			AdminAuditOutcome.SUCCESS,
			"disabled for new selections"
		);
		return "redirect:/admin/codes/page?groupCode=" + disabled.groupCode();
	}

	@GetMapping("/admin/incidents/page")
	String incidentsPage(
		@RequestParam(required = false) Integer page,
		@RequestParam(required = false) Integer size,
		Model model
	) {
		populateIncidents(model, page, size);
		model.addAttribute("severityOptions", optionRows(AdminCommonCodeGroups.INCIDENT_SEVERITY));
		model.addAttribute("statusOptions", optionRows(AdminCommonCodeGroups.INCIDENT_STATUS)
			.stream()
			.filter(option -> AdminIncidentStatus.RECEIVED.name().equals(option.code()))
			.toList());
		model.addAttribute("sourceOptions", optionRows(AdminCommonCodeGroups.INCIDENT_SOURCE));
		model.addAttribute("healthStatus", checkHealthUseCase.checkHealth().status());
		return "admin/incidents/list";
	}

	// 장애 목록 자동 갱신(#1742): 60초 폴링이 목록 live 영역만 받아간다(생성 폼·health 섹션은 유지).
	@GetMapping("/admin/incidents/page/live")
	String incidentsLive(
		@RequestParam(required = false) Integer page,
		@RequestParam(required = false) Integer size,
		Model model
	) {
		populateIncidents(model, page, size);
		return "admin/incidents/list :: live";
	}

	private void populateIncidents(Model model, Integer page, Integer size) {
		AdminPageRequest pageRequest = AdminPageRequest.of(page, size);
		List<AdminIncident> recent = incidentService.listRecent(pageRequest.limitForHasNext(), pageRequest.offset());
		Map<String, List<AdminIncidentTransition>> timelines = incidentService.listTransitions(
			recent.stream().map(AdminIncident::incidentId).toList());
		List<IncidentRow> incidents = recent.stream()
			.map(incident -> IncidentRow.from(incident, timelines.getOrDefault(incident.incidentId(), List.of())))
			.toList();
		EgovPaginationView pageView = EgovPaginationView.fromSlice(pageRequest.page(), pageRequest.size(), incidents.size());
		model.addAttribute("incidents", pageView.visibleItems(incidents));
		model.addAttribute("page", pageView);
		model.addAttribute("paginationLinks", pageView.links("/admin/incidents/page", Collections.emptyMap()));
	}

	@PostMapping("/admin/incidents")
	@Transactional
	String openIncident(
		@RequestParam String severity,
		@RequestParam String status,
		@RequestParam String source,
		@RequestParam String summary,
		@RequestParam(required = false) String owner,
		@RequestParam(required = false) String stationId,
		@RequestParam(required = false) String lineId,
		Principal principal,
		Authentication authentication,
		HttpServletRequest request
	) {
		AdminIncident incident = incidentService.open(new OpenAdminIncidentCommand(
			severity,
			status,
			source,
			summary,
			ownerOrPrincipal(owner, principal),
			stationId,
			lineId
		));
		auditWriter.incidentChange(
			authentication,
			request,
			incident.incidentId(),
			"OPEN_INCIDENT",
			AdminAuditOutcome.SUCCESS,
			"incident opened"
		);
		return "redirect:/admin/incidents/page";
	}

	@PostMapping("/admin/incidents/health")
	@Transactional
	String openHealthIncident(Principal principal, Authentication authentication, HttpServletRequest request) {
		AdminIncident incident = incidentService.openFromHealth(checkHealthUseCase.checkHealth(), principal.getName());
		auditWriter.incidentChange(
			authentication,
			request,
			incident.incidentId(),
			"OPEN_HEALTH_INCIDENT",
			AdminAuditOutcome.SUCCESS,
			"health incident opened"
		);
		return "redirect:/admin/incidents/page";
	}

	@PostMapping("/admin/incidents/{incidentId}/transition")
	@Transactional
	String transitionIncident(
		@PathVariable String incidentId,
		@RequestParam String targetStatus,
		@RequestParam(required = false) String note,
		@RequestParam(required = false) String resolution,
		Principal principal,
		Authentication authentication,
		HttpServletRequest request
	) {
		AdminIncident incident = incidentService.transition(incidentId, targetStatus, principal.getName(), note, resolution);
		boolean resolved = incident.status().isResolved();
		auditWriter.incidentChange(
			authentication,
			request,
			incident.incidentId(),
			resolved ? "RESOLVE_INCIDENT" : "TRANSITION_INCIDENT",
			AdminAuditOutcome.SUCCESS,
			resolved ? "resolutionLength=%d".formatted(incident.resolution().length()) : "to=%s".formatted(incident.status().name())
		);
		return "redirect:/admin/incidents/page";
	}

	private List<CodeOptionRow> optionRows(String groupCode) {
		return commonCodeService.enabledCodes(groupCode).stream().map(CodeOptionRow::from).toList();
	}

	private static String selectedGroup(String requested, List<AdminCommonCodeGroup> groups) {
		if (requested != null && groups.stream().anyMatch(group -> group.groupCode().equals(requested))) {
			return requested;
		}
		return groups.isEmpty() ? "" : groups.get(0).groupCode();
	}

	private static String ownerOrPrincipal(String owner, Principal principal) {
		return owner == null || owner.isBlank() ? principal.getName() : owner;
	}

	private String auditCodeTarget(AdminCommonCode code) {
		return "code-" + auditWriter.sha256TargetId("%s:%s".formatted(code.groupCode(), code.code()));
	}

	record CodeGroupRow(String groupCode, String displayName, String description, boolean enabled) {

		static CodeGroupRow from(AdminCommonCodeGroup group) {
			return new CodeGroupRow(group.groupCode(), group.displayName(), group.description(), group.enabled());
		}
	}

	record CodeRow(
		String groupCode,
		String code,
		String displayName,
		String description,
		int sortOrder,
		boolean enabled,
		boolean requiredIncidentCode,
		String enabledLabel
	) {

		static CodeRow from(AdminCommonCode code) {
			return new CodeRow(
				code.groupCode(),
				code.code(),
				code.displayName(),
				code.description(),
				code.sortOrder(),
				code.enabled(),
				AdminCommonCodeGroups.isRequiredIncidentCode(code.groupCode(), code.code()),
				code.enabled() ? "신규 선택 가능" : "신규 선택 불가"
			);
		}
	}

	record CodeOptionRow(String code, String displayName) {

		static CodeOptionRow from(AdminCommonCode code) {
			return new CodeOptionRow(code.code(), code.displayName());
		}
	}

	record IncidentRow(
		String incidentId,
		String severity,
		String status,
		String statusLabel,
		String source,
		String summary,
		String owner,
		String openedAt,
		String resolvedAt,
		String resolution,
		boolean open,
		String stationId,
		String lineId,
		String stationHubUrl,
		List<TransitionRow> timeline,
		List<TransitionOption> transitionOptions
	) {

		static IncidentRow from(AdminIncident incident, List<AdminIncidentTransition> transitions) {
			return new IncidentRow(
				incident.incidentId(),
				incident.severity(),
				incident.status().name(),
				incident.status().label(),
				incident.source(),
				incident.summary(),
				incident.owner(),
				String.valueOf(incident.openedAt()),
				incident.resolvedAt() == null ? "-" : String.valueOf(incident.resolvedAt()),
				incident.resolution(),
				!incident.status().isResolved(),
				incident.stationId(),
				incident.lineId(),
				incident.stationId() == null ? null : "/admin/stations/" + incident.stationId() + "/page",
				transitions.stream().map(TransitionRow::from).toList(),
				incident.status().allowedTransitions().stream()
					.sorted()
					.map(TransitionOption::from)
					.toList()
			);
		}
	}

	record TransitionRow(
		String fromLabel,
		String toLabel,
		String changedAt,
		String changedBy,
		String note,
		boolean initial
	) {

		static TransitionRow from(AdminIncidentTransition transition) {
			return new TransitionRow(
				transition.isInitial() ? "-" : transition.fromStatus().label(),
				transition.toStatus().label(),
				String.valueOf(transition.changedAt()),
				transition.changedBy(),
				transition.note() == null ? "" : transition.note(),
				transition.isInitial()
			);
		}
	}

	record TransitionOption(String code, String label, boolean requiresResolution) {

		static TransitionOption from(AdminIncidentStatus status) {
			return new TransitionOption(status.name(), status.label(), status.isResolved());
		}
	}
}
