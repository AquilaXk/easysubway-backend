package com.easysubway.datapack.adapter.in.web;

import com.easysubway.datapack.adapter.out.persistence.JdbcManualOverrideLedgerRepository;
import com.easysubway.datapack.adapter.out.persistence.JdbcManualOverrideLedgerRepository.ManualOverrideRow;
import java.time.LocalDateTime;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class ManualOverrideLedgerAdminPageController {

	private static final int LEDGER_LIMIT = 200;

	private final JdbcManualOverrideLedgerRepository ledgerRepository;

	ManualOverrideLedgerAdminPageController(JdbcManualOverrideLedgerRepository ledgerRepository) {
		this.ledgerRepository = ledgerRepository;
	}

	@GetMapping("/admin/datapack/manual-overrides/page")
	@PreAuthorize("hasAuthority('admin.datapack.read')")
	String manualOverrides(Model model) {
		model.addAttribute("overrideRows", ledgerRepository.listRecentOverrides(LEDGER_LIMIT).stream()
			.map(ManualOverrideView::from)
			.toList());
		return "admin/datapack/manual-overrides/list";
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
}
