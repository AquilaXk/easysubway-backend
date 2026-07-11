package com.easysubway.datapack.adapter.in.web;

import com.easysubway.admin.audit.application.service.AdminAuditWriter;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import com.easysubway.datapack.adapter.out.persistence.JdbcManualOverrideLedgerRepository;
import com.easysubway.datapack.adapter.out.persistence.JdbcManualOverrideLedgerRepository.ManualOverrideRow;
import com.easysubway.datapack.application.service.DatapackManualOverrideCommandService;
import com.easysubway.datapack.application.service.DatapackManualOverrideCommandService.ManualOverrideDecisionCommand;
import com.easysubway.datapack.application.service.DatapackManualOverrideCommandService.ManualOverrideRequestCommand;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.Comparator;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
class ManualOverrideLedgerAdminPageController {

	private static final int LEDGER_LIMIT = 200;

	private final JdbcManualOverrideLedgerRepository ledgerRepository;
	private final DatapackManualOverrideCommandService commandService;
	private final AdminAuditWriter auditWriter;

	ManualOverrideLedgerAdminPageController(
		JdbcManualOverrideLedgerRepository ledgerRepository,
		DatapackManualOverrideCommandService commandService,
		AdminAuditWriter auditWriter
	) {
		this.ledgerRepository = ledgerRepository;
		this.commandService = commandService;
		this.auditWriter = auditWriter;
	}

	@GetMapping("/admin/datapack/manual-overrides/page")
	@PreAuthorize("hasAuthority('admin.datapack.read')")
	String manualOverrides(
		@ModelAttribute("overrideForm") ManualOverrideRequestForm form,
		@RequestParam(required = false) String query,
		@RequestParam(required = false) String status,
		@RequestParam(required = false) String candidateId,
		@RequestParam(required = false) String sort,
		Model model
	) {
		DatapackAdminListQuery filter = DatapackAdminListQuery.of(query, status, candidateId, null, sort);
		model.addAttribute("overrideRows", ledgerRepository.listRecentOverrides(LEDGER_LIMIT).stream()
			.map(ManualOverrideView::from)
			.filter(row -> filter.matchesText(
				row.id(),
				row.entityType(),
				row.entityId(),
				row.fieldName(),
				row.reasonCode(),
				row.reason(),
				row.requestedBy(),
				row.approvedBy(),
				row.approvalStatus(),
				row.conflictStatus(),
				row.productionStatus()
			))
			.filter(row -> manualStatusMatches(row, filter.statusValue()))
			.sorted(manualSort(filter.sortValue()))
			.toList());
		model.addAttribute("overrideForm", form);
		model.addAttribute("filter", filter);
		return "admin/datapack/manual-overrides/list";
	}

	@PostMapping("/admin/datapack/manual-overrides")
	@PreAuthorize("hasAuthority('admin.datapack.override.request')")
	String requestOverride(
		@ModelAttribute ManualOverrideRequestForm form,
		Authentication authentication,
		HttpServletRequest request
	) {
		commandService.request(form.toCommand(authentication.getName()));
		auditWriter.adminAction(
			authentication,
			request,
			"DATAPACK_MANUAL_OVERRIDE",
			form.id(),
			"REQUEST",
			AdminAuditOutcome.SUCCESS,
			form.reason()
		);
		return "redirect:/admin/datapack/manual-overrides/page";
	}

	private static boolean manualStatusMatches(ManualOverrideView row, String status) {
		return switch (status) {
			case "ALL" -> true;
			case "BLOCKER" -> !productionReady(row);
			case "STRICT" -> row.strictRouteEligible();
			default -> status.equals(row.approvalStatus()) || status.equals(row.conflictStatus());
		};
	}

	private static Comparator<ManualOverrideView> manualSort(String sort) {
		return switch (sort) {
			case "entity" -> Comparator.comparing(ManualOverrideView::entityType)
				.thenComparing(ManualOverrideView::entityId)
				.thenComparing(ManualOverrideView::fieldName);
			case "created" -> Comparator.comparing(ManualOverrideView::effectiveFrom).reversed()
				.thenComparing(ManualOverrideView::id);
			default -> Comparator.comparing(ManualOverrideLedgerAdminPageController::productionReady)
				.thenComparing(ManualOverrideView::productionStatus)
				.thenComparing(ManualOverrideView::entityType)
				.thenComparing(ManualOverrideView::entityId);
		};
	}

	private static boolean productionReady(ManualOverrideView row) {
		return "candidate 가능".equals(row.productionStatus());
	}

	@PostMapping("/admin/datapack/manual-overrides/{overrideId}/approve")
	@PreAuthorize("hasAuthority('admin.datapack.override.approve')")
	String approveOverride(
		@PathVariable String overrideId,
		@ModelAttribute ManualOverrideDecisionForm form,
		Authentication authentication,
		HttpServletRequest request
	) {
		commandService.approve(overrideId, form.toCommand(authentication.getName()));
		auditWriter.adminAction(
			authentication,
			request,
			"DATAPACK_MANUAL_OVERRIDE",
			overrideId,
			"APPROVE",
			AdminAuditOutcome.SUCCESS,
			form.reason()
		);
		return "redirect:/admin/datapack/manual-overrides/page";
	}

	@PostMapping("/admin/datapack/manual-overrides/{overrideId}/expire")
	@PreAuthorize("hasAuthority('admin.datapack.override.approve')")
	String expireOverride(
		@PathVariable String overrideId,
		@ModelAttribute ManualOverrideDecisionForm form,
		Authentication authentication,
		HttpServletRequest request
	) {
		commandService.expire(overrideId, form.toCommand(authentication.getName()));
		auditWriter.adminAction(
			authentication,
			request,
			"DATAPACK_MANUAL_OVERRIDE",
			overrideId,
			"EXPIRE",
			AdminAuditOutcome.SUCCESS,
			form.reason()
		);
		return "redirect:/admin/datapack/manual-overrides/page";
	}

	record ManualOverrideView(
		String id,
		String entityType,
		String entityId,
		String fieldName,
		String beforeValue,
		String afterValue,
		String reasonCode,
		String reason,
		String evidenceUri,
		String evidenceHash,
		String requestedBy,
		String approvedBy,
		LocalDateTime approvedAt,
		String routeSafetyApprovedBy,
		String approvalStatus,
		String conflictStatus,
		boolean strictRouteEligible,
		LocalDateTime effectiveFrom,
		LocalDateTime expiresAt,
		String supersededBy,
		String productionStatus
	) {

		static ManualOverrideView from(ManualOverrideRow row) {
			return new ManualOverrideView(
				row.id(),
				row.entityType(),
				row.entityId(),
				row.fieldName(),
				valueOrDash(row.beforeValue()),
				row.afterValue(),
				row.reasonCode(),
				row.reason(),
				row.evidenceUri(),
				row.evidenceHash(),
				row.requestedBy(),
				valueOrDash(row.approvedBy()),
				row.approvedAt(),
				valueOrDash(row.routeSafetyApprovedBy()),
				row.approvalStatus(),
				row.conflictStatus(),
				row.strictRouteEligible(),
				row.effectiveFrom(),
				row.expiresAt(),
				valueOrDash(row.supersededBy()),
				ManualOverrideLedgerAdminPageController.productionStatus(row)
			);
		}
	}

	private static String productionStatus(ManualOverrideRow row) {
		if ("EXPIRED".equals(row.approvalStatus())) {
			return "expired";
		}
		if ("SUPERSEDED".equals(row.approvalStatus()) || hasText(row.supersededBy())) {
			return "superseded";
		}
		if ("REJECTED".equals(row.approvalStatus())) {
			return "rejected";
		}
		if ("UNRESOLVED".equals(row.conflictStatus())) {
			return "unresolved conflict";
		}
		if (row.strictRouteEligible() && !hasText(row.routeSafetyApprovedBy())) {
			return "route safety approval missing";
		}
		if (!"APPROVED".equals(row.approvalStatus())) {
			return "approval pending";
		}
		if (!hasText(row.approvedBy()) || row.approvedAt() == null) {
			return "approval missing";
		}
		if (row.requestedBy().equals(row.approvedBy())) {
			return "requester approver same";
		}
		return "candidate 가능";
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private static String valueOrDash(String value) {
		return hasText(value) ? value : "-";
	}

	record ManualOverrideRequestForm(
		String id,
		String entityType,
		String entityId,
		String fieldName,
		String beforeValue,
		String afterValue,
		String reasonCode,
		String reason,
		String evidenceUri,
		String evidenceHash,
		Boolean strictRouteEligible,
		LocalDateTime effectiveFrom,
		LocalDateTime expiresAt,
		String idempotencyKey
	) {

		ManualOverrideRequestCommand toCommand(String requestedBy) {
			return new ManualOverrideRequestCommand(
				id,
				entityType,
				entityId,
				fieldName,
				beforeValue,
				afterValue,
				reasonCode,
				reason,
				evidenceUri,
				evidenceHash,
				requestedBy,
				Boolean.TRUE.equals(strictRouteEligible),
				effectiveFrom,
				expiresAt,
				idempotencyKey
			);
		}
	}

	record ManualOverrideDecisionForm(String reason, String idempotencyKey) {

		ManualOverrideDecisionCommand toCommand(String actor) {
			return new ManualOverrideDecisionCommand(actor, reason, idempotencyKey);
		}
	}
}
