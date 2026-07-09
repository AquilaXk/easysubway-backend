package com.easysubway.datapack.adapter.in.web;

import com.easysubway.admin.audit.application.service.AdminAuditWriter;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import com.easysubway.datapack.adapter.out.persistence.JdbcAliasQuarantineQueueRepository;
import com.easysubway.datapack.adapter.out.persistence.JdbcAliasQuarantineQueueRepository.AliasApprovalRow;
import com.easysubway.datapack.adapter.out.persistence.JdbcAliasQuarantineQueueRepository.QuarantineRow;
import com.easysubway.datapack.application.service.DatapackAliasQuarantineCommandService;
import com.easysubway.datapack.application.service.DatapackAliasQuarantineCommandService.AliasReviewCommand;
import com.easysubway.datapack.application.service.DatapackAliasQuarantineCommandService.QuarantineResolutionCommand;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
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
class AliasQuarantineAdminPageController {

	private static final int QUEUE_LIMIT = 16;
	private static final int QUEUE_FILTER_LIMIT = 500;

	private final JdbcAliasQuarantineQueueRepository queueRepository;
	private final DatapackAliasQuarantineCommandService commandService;
	private final AdminAuditWriter auditWriter;

	AliasQuarantineAdminPageController(
		JdbcAliasQuarantineQueueRepository queueRepository,
		DatapackAliasQuarantineCommandService commandService,
		AdminAuditWriter auditWriter
	) {
		this.queueRepository = queueRepository;
		this.commandService = commandService;
		this.auditWriter = auditWriter;
	}

	@GetMapping("/admin/datapack/alias-quarantine/page")
	@PreAuthorize("hasAuthority('admin.datapack.read')")
	String aliasQuarantineQueue(
		@RequestParam(required = false) String query,
		@RequestParam(required = false) String status,
		@RequestParam(required = false) String candidateId,
		@RequestParam(required = false) String sourceSnapshotId,
		Model model
	) {
		DatapackAdminListQuery filter = DatapackAdminListQuery.of(query, status, candidateId, sourceSnapshotId, null);
		int fetchLimit = filter.active() ? QUEUE_FILTER_LIMIT : QUEUE_LIMIT;
		model.addAttribute("aliasApprovals", queueRepository.listRecentAliasApprovals(fetchLimit).stream()
			.map(AliasApprovalView::from)
			.filter(alias -> filter.matchesSourceSnapshot(alias.sourceSnapshotId()))
			.filter(alias -> filter.matchesText(
				alias.id(),
				alias.sourceId(),
				alias.sourceSnapshotId(),
				alias.providerEntity(),
				alias.canonicalEntity(),
				alias.matchMethod(),
				alias.approvalStatus()
			))
			.filter(alias -> aliasStatusMatches(alias, filter.statusValue()))
			.limit(QUEUE_LIMIT)
			.toList());
		model.addAttribute("quarantineRecords", queueRepository.listRecentQuarantineRecords(fetchLimit).stream()
			.map(QuarantineView::from)
			.filter(record -> filter.matchesSourceSnapshot(record.sourceSnapshotId()))
			.filter(record -> filter.matchesText(
				record.id(),
				record.sourceId(),
				record.sourceSnapshotId(),
				record.reasonCode(),
				record.severity(),
				record.redactedExcerpt(),
				record.resolutionStatus(),
				record.latestResolution(),
				record.latestCanonicalEntity()
			))
			.filter(record -> quarantineStatusMatches(record, filter.statusValue()))
			.limit(QUEUE_LIMIT)
			.toList());
		model.addAttribute("filter", filter);
		return "admin/datapack/alias-quarantine/list";
	}

	@PostMapping("/admin/datapack/alias-approvals/{aliasId}/approve")
	@PreAuthorize("hasAuthority('admin.datapack.alias.review')")
	String approveAlias(
		@PathVariable String aliasId,
		@ModelAttribute AliasReviewForm form,
		Authentication authentication,
		HttpServletRequest request
	) {
		commandService.reviewAlias(aliasId, form.toCommand("APPROVED", authentication.getName()));
		auditWriter.datapackCommand(
			authentication,
			request,
			"DATAPACK_ALIAS",
			aliasId,
			"APPROVE",
			AdminAuditOutcome.SUCCESS,
			form.reason()
		);
		return "redirect:/admin/datapack/alias-quarantine/page";
	}

	private static boolean aliasStatusMatches(AliasApprovalView alias, String status) {
		return switch (status) {
			case "ALL" -> true;
			case "BLOCKER" -> !"APPROVED".equals(alias.approvalStatus()) && "-".equals(alias.supersededBy());
			default -> status.equals(alias.approvalStatus());
		};
	}

	private static boolean quarantineStatusMatches(QuarantineView record, String status) {
		return switch (status) {
			case "ALL" -> true;
			case "BLOCKER" -> "OPEN".equals(record.resolutionStatus());
			default -> status.equals(record.resolutionStatus());
		};
	}

	@PostMapping("/admin/datapack/alias-approvals/{aliasId}/reject")
	@PreAuthorize("hasAuthority('admin.datapack.alias.review')")
	String rejectAlias(
		@PathVariable String aliasId,
		@ModelAttribute AliasReviewForm form,
		Authentication authentication,
		HttpServletRequest request
	) {
		commandService.reviewAlias(aliasId, form.toCommand("REJECTED", authentication.getName()));
		auditWriter.datapackCommand(
			authentication,
			request,
			"DATAPACK_ALIAS",
			aliasId,
			"REJECT",
			AdminAuditOutcome.SUCCESS,
			form.reason()
		);
		return "redirect:/admin/datapack/alias-quarantine/page";
	}

	@PostMapping("/admin/datapack/quarantine-records/{recordId}/resolve")
	@PreAuthorize("hasAuthority('admin.datapack.quarantine.review')")
	String resolveQuarantine(
		@PathVariable String recordId,
		@ModelAttribute QuarantineResolutionForm form,
		Authentication authentication,
		HttpServletRequest request
	) {
		commandService.resolveQuarantine(recordId, form.toCommand(authentication.getName()));
		auditWriter.datapackCommand(
			authentication,
			request,
			"DATAPACK_QUARANTINE",
			recordId,
			"RESOLVE",
			AdminAuditOutcome.SUCCESS,
			form.resolutionReason()
		);
		return "redirect:/admin/datapack/alias-quarantine/page";
	}

	record AliasApprovalView(
		String id,
		String sourceId,
		String sourceSnapshotId,
		String providerEntity,
		String canonicalEntity,
		int confidence,
		String matchMethod,
		String approvalStatus,
		String requestedBy,
		String approvedBy,
		String approvedAt,
		String evidenceHash,
		String supersededBy,
		LocalDateTime createdAt
	) {

		static AliasApprovalView from(AliasApprovalRow row) {
			return new AliasApprovalView(
				row.id(),
				row.sourceId(),
				row.sourceSnapshotId(),
				row.providerEntityType() + ":" + row.providerEntityId(),
				row.canonicalEntityType() + ":" + row.canonicalEntityId(),
				row.confidence(),
				row.matchMethod(),
				row.approvalStatus(),
				row.requestedBy(),
				valueOrDash(row.approvedBy()),
				valueOrDash(row.approvedAt()),
				row.evidenceHash(),
				valueOrDash(row.supersededBy()),
				row.createdAt()
			);
		}
	}

	record QuarantineView(
		String id,
		String sourceId,
		String sourceSnapshotId,
		String providerRecordHash,
		String reasonCode,
		String severity,
		String redactedExcerpt,
		String resolutionStatus,
		String resolvedBy,
		String resolvedAt,
		LocalDateTime createdAt,
		String latestResolution,
		String latestCanonicalEntity
	) {

		static QuarantineView from(QuarantineRow row) {
			return new QuarantineView(
				row.id(),
				row.sourceId(),
				row.sourceSnapshotId(),
				row.providerRecordHash(),
				row.reasonCode(),
				row.severity(),
				valueOrDash(row.redactedExcerpt()),
				row.resolutionStatus(),
				valueOrDash(row.resolvedBy()),
				valueOrDash(row.resolvedAt()),
				row.createdAt(),
				valueOrDash(row.latestResolutionStatus()),
				entityOrDash(row.latestCanonicalEntityType(), row.latestCanonicalEntityId())
			);
		}
	}

	private static String entityOrDash(String type, String id) {
		if (type == null || type.isBlank() || id == null || id.isBlank()) {
			return "-";
		}
		return type + ":" + id;
	}

	private static String valueOrDash(Object value) {
		if (value == null) {
			return "-";
		}
		if (value instanceof String text && text.isBlank()) {
			return "-";
		}
		return value.toString();
	}

	record AliasReviewForm(String reason, String idempotencyKey) {

		AliasReviewCommand toCommand(String approvalStatus, String reviewedBy) {
			return new AliasReviewCommand(approvalStatus, reviewedBy, reason, idempotencyKey);
		}
	}

	record QuarantineResolutionForm(
		String resolutionStatus,
		String resolutionReason,
		String canonicalEntityType,
		String canonicalEntityId,
		String evidenceHash,
		String idempotencyKey
	) {

		QuarantineResolutionCommand toCommand(String resolvedBy) {
			return new QuarantineResolutionCommand(
				resolutionStatus,
				resolutionReason,
				resolvedBy,
				canonicalEntityType,
				canonicalEntityId,
				evidenceHash,
				idempotencyKey
			);
		}
	}
}
