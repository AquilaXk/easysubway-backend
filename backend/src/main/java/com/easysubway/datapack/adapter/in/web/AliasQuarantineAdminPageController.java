package com.easysubway.datapack.adapter.in.web;

import com.easysubway.datapack.adapter.out.persistence.JdbcAliasQuarantineQueueRepository;
import com.easysubway.datapack.adapter.out.persistence.JdbcAliasQuarantineQueueRepository.AliasApprovalRow;
import com.easysubway.datapack.adapter.out.persistence.JdbcAliasQuarantineQueueRepository.QuarantineRow;
import com.easysubway.datapack.application.service.AliasQuarantineCommandService;
import com.easysubway.datapack.application.service.AliasQuarantineCommandService.QuarantineResolutionCommand;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
class AliasQuarantineAdminPageController {

	private static final int QUEUE_LIMIT = 100;

	private final JdbcAliasQuarantineQueueRepository queueRepository;
	private final AliasQuarantineCommandService commandService;

	AliasQuarantineAdminPageController(
		JdbcAliasQuarantineQueueRepository queueRepository,
		AliasQuarantineCommandService commandService
	) {
		this.queueRepository = queueRepository;
		this.commandService = commandService;
	}

	@GetMapping("/admin/datapack/alias-quarantine/page")
	@PreAuthorize("hasAuthority('admin.datapack.read')")
	String aliasQuarantineQueue(Model model) {
		model.addAttribute("aliasApprovals", queueRepository.listRecentAliasApprovals(QUEUE_LIMIT).stream()
			.map(AliasApprovalView::from)
			.toList());
		model.addAttribute("quarantineRecords", queueRepository.listRecentQuarantineRecords(QUEUE_LIMIT).stream()
			.map(QuarantineView::from)
			.toList());
		return "admin/datapack/alias-quarantine/list";
	}

	@PostMapping("/admin/datapack/alias-quarantine/aliases/{aliasId}/approve")
	@PreAuthorize("hasAuthority('admin.datapack.alias.review')")
	String approveAlias(@PathVariable String aliasId, Authentication authentication) {
		commandService.approveAlias(aliasId, authentication.getName());
		return "redirect:/admin/datapack/alias-quarantine/page";
	}

	@PostMapping("/admin/datapack/alias-quarantine/aliases/{aliasId}/reject")
	@PreAuthorize("hasAuthority('admin.datapack.alias.review')")
	String rejectAlias(@PathVariable String aliasId, Authentication authentication) {
		commandService.rejectAlias(aliasId, authentication.getName());
		return "redirect:/admin/datapack/alias-quarantine/page";
	}

	@PostMapping("/admin/datapack/alias-quarantine/quarantines/{quarantineId}/resolve")
	@PreAuthorize("hasAuthority('admin.datapack.quarantine.review')")
	String resolveQuarantine(
		@PathVariable String quarantineId,
		@ModelAttribute QuarantineResolutionForm form,
		Authentication authentication
	) {
		commandService.resolveQuarantine(form.toCommand(quarantineId, authentication.getName()));
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

	record QuarantineResolutionForm(
		String resolutionStatus,
		String resolutionReason,
		String canonicalEntityType,
		String canonicalEntityId,
		String evidenceHash
	) {

		QuarantineResolutionCommand toCommand(String quarantineId, String resolvedBy) {
			return new QuarantineResolutionCommand(
				quarantineId,
				resolutionStatus,
				resolutionReason,
				resolvedBy,
				canonicalEntityType,
				canonicalEntityId,
				evidenceHash
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
}
