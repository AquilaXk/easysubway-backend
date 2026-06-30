package com.easysubway.datapack.adapter.in.web;

import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseChannelRepository;
import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseChannelRepository.ReleaseChannelEventRow;
import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseChannelRepository.ReleaseChannelRow;
import com.easysubway.datapack.application.service.DatapackReleaseChannelCommandService;
import com.easysubway.datapack.application.service.DatapackReleaseChannelCommandService.ReleaseChannelCommand;
import java.time.LocalDateTime;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
class DatapackReleaseChannelAdminPageController {

	private static final int EVENT_LIMIT = 20;

	private final JdbcDatapackReleaseChannelRepository releaseChannelRepository;
	private final DatapackReleaseChannelCommandService releaseChannelCommandService;

	DatapackReleaseChannelAdminPageController(
		JdbcDatapackReleaseChannelRepository releaseChannelRepository,
		DatapackReleaseChannelCommandService releaseChannelCommandService
	) {
		this.releaseChannelRepository = releaseChannelRepository;
		this.releaseChannelCommandService = releaseChannelCommandService;
	}

	@GetMapping("/admin/datapack/release-channels/page")
	@PreAuthorize("hasAuthority('admin.datapack.read')")
	String releaseChannels(Model model) {
		model.addAttribute("channels", releaseChannelRepository.listChannels().stream()
			.map(ReleaseChannelView::from)
			.toList());
		model.addAttribute("events", releaseChannelRepository.listRecentEvents(EVENT_LIMIT).stream()
			.map(ReleaseChannelEventView::from)
			.toList());
		return "admin/datapack/release-channels/list";
	}

	@PostMapping("/admin/datapack/release-channels/{channel}/promote")
	@PreAuthorize("(#channel == 'production' and hasAuthority('admin.datapack.production.approve'))"
		+ " or (#channel != 'production' and hasAuthority('admin.datapack.staging.promote'))")
	String promote(
		@PathVariable("channel") String channel,
		@ModelAttribute ReleaseChannelCommandForm form,
		Authentication authentication
	) {
		releaseChannelCommandService.promote(form.toCommand(channel, authentication.getName()));
		return "redirect:/admin/datapack/release-channels/page";
	}

	@PostMapping("/admin/datapack/release-channels/{channel}/rollback")
	@PreAuthorize("hasAuthority('admin.datapack.rollback')")
	String rollback(
		@PathVariable("channel") String channel,
		@ModelAttribute ReleaseChannelCommandForm form,
		Authentication authentication
	) {
		releaseChannelCommandService.rollback(form.toCommand(channel, authentication.getName()));
		return "redirect:/admin/datapack/release-channels/page";
	}

	record ReleaseChannelView(
		String channel,
		String candidateId,
		String candidateVersion,
		String manifestUrl,
		String manifestSha256,
		String previousStableCandidateId,
		String previousStableCandidateVersion,
		String previousManifestSha256,
		String rollbackLabel,
		String lastOperationType,
		String lastOperationStatus,
		String requestedBy,
		String approvedBy,
		String reason,
		String idempotencyKey,
		LocalDateTime updatedAt
	) {

		static ReleaseChannelView from(ReleaseChannelRow row) {
			return new ReleaseChannelView(
				row.channel(),
				row.candidateId(),
				row.candidateVersion(),
				row.manifestUrl(),
				row.manifestSha256(),
				valueOrDash(row.previousStableCandidateId()),
				valueOrDash(row.previousStableCandidateVersion()),
				valueOrDash(row.previousManifestSha256()),
				row.rollbackAvailable() ? "rollback 가능" : "rollback 불가",
				row.lastOperationType(),
				row.lastOperationStatus(),
				row.requestedBy(),
				row.approvedBy(),
				row.reason(),
				row.idempotencyKey(),
				row.updatedAt()
			);
		}
	}

	record ReleaseChannelEventView(
		String id,
		String channel,
		String previousCandidateId,
		String nextCandidateId,
		String previousManifestSha256,
		String nextManifestSha256,
		String operationType,
		String operationStatus,
		String requestedBy,
		String approvedBy,
		String reason,
		String idempotencyKey,
		String workflowRunUrl,
		LocalDateTime createdAt
	) {

		static ReleaseChannelEventView from(ReleaseChannelEventRow row) {
			return new ReleaseChannelEventView(
				row.id(),
				row.channel(),
				valueOrDash(row.previousCandidateId()),
				row.nextCandidateId(),
				valueOrDash(row.previousManifestSha256()),
				row.nextManifestSha256(),
				row.operationType(),
				row.operationStatus(),
				row.requestedBy(),
				row.approvedBy(),
				row.reason(),
				row.idempotencyKey(),
				row.workflowRunUrl(),
				row.createdAt()
			);
		}
	}

	private static String valueOrDash(String value) {
		if (value == null || value.isBlank()) {
			return "-";
		}
		return value;
	}

	record ReleaseChannelCommandForm(
		String previousCandidateId,
		String nextCandidateId,
		String previousManifestSha256,
		String nextManifestSha256,
		String requestedBy,
		String reason,
		String idempotencyKey,
		String workflowRunUrl
	) {

		ReleaseChannelCommand toCommand(String channel, String approvedBy) {
			return new ReleaseChannelCommand(
				channel,
				previousCandidateId,
				nextCandidateId,
				previousManifestSha256,
				nextManifestSha256,
				requestedBy,
				approvedBy,
				reason,
				idempotencyKey,
				workflowRunUrl
			);
		}
	}
}
