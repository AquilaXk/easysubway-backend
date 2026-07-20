package com.easysubway.datapack.adapter.in.web;

import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseChannelRepository;
import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseChannelRepository.ReleaseChannelEventRow;
import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseChannelRepository.ReleaseChannelRow;
import com.easysubway.datapack.application.service.DatapackReleaseChannelCommandService;
import com.easysubway.datapack.application.service.DatapackReleaseChannelCommandService.ReleaseChannelCommand;
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
	String releaseChannels(
		@RequestParam(required = false) String query,
		@RequestParam(required = false) String status,
		@RequestParam(required = false) String candidateId,
		@RequestParam(required = false) String sort,
		Model model
	) {
		DatapackAdminListQuery filter = DatapackAdminListQuery.of(query, status, candidateId, null, sort);
		model.addAttribute("channels", releaseChannelRepository.listChannels().stream()
			.map(ReleaseChannelView::from)
			.filter(channel -> filter.matchesCandidate(channel.candidateId())
				|| filter.matchesCandidate(channel.previousStableCandidateId()))
			.filter(channel -> filter.matchesText(
				channel.channel(),
				channel.candidateId(),
				channel.candidateVersion(),
				channel.previousStableCandidateId(),
				channel.previousStableCandidateVersion(),
				channel.lastOperationType(),
				channel.lastOperationStatus(),
				channel.reason()
			))
			.filter(channel -> channelStatusMatches(channel, filter.statusValue()))
			.sorted(channelSort(filter.sortValue()))
			.toList());
		model.addAttribute("events", releaseChannelRepository.listRecentEvents(EVENT_LIMIT).stream()
			.map(ReleaseChannelEventView::from)
			.filter(event -> filter.matchesCandidate(event.nextCandidateId())
				|| filter.matchesCandidate(event.previousCandidateId()))
			.filter(event -> filter.matchesText(
				event.id(),
				event.channel(),
				event.previousCandidateId(),
				event.nextCandidateId(),
				event.operationType(),
				event.operationStatus(),
				event.requestedBy(),
				event.approvedBy(),
				event.reason()
			))
			.filter(event -> eventStatusMatches(event, filter.statusValue()))
			.toList());
		model.addAttribute("filter", filter);
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

	private static boolean channelStatusMatches(ReleaseChannelView channel, String status) {
		return switch (status) {
			case "ALL" -> true;
			case "ROLLBACK_AVAILABLE" -> "rollback 가능".equals(channel.rollbackLabel());
			default -> status.equals(channel.channel())
				|| status.equals(channel.lastOperationType())
				|| status.equals(channel.lastOperationStatus());
		};
	}

	private static boolean eventStatusMatches(ReleaseChannelEventView event, String status) {
		return switch (status) {
			case "ALL", "ROLLBACK_AVAILABLE" -> true;
			default -> status.equals(event.channel())
				|| status.equals(event.operationType())
				|| status.equals(event.operationStatus());
		};
	}

	private static Comparator<ReleaseChannelView> channelSort(String sort) {
		return switch (sort) {
			case "candidate" -> Comparator.comparing(ReleaseChannelView::candidateId);
			default -> Comparator.comparingInt(DatapackReleaseChannelAdminPageController::channelOrder)
				.thenComparing(ReleaseChannelView::channel);
		};
	}

	private static int channelOrder(ReleaseChannelView channel) {
		return switch (channel.channel()) {
			case "production" -> 0;
			case "staging" -> 1;
			case "dev" -> 2;
			default -> 3;
		};
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
				valueOrDash(row.workflowRunUrl()),
				row.createdAt()
			);
		}
	}

	private static String valueOrDash(String value) {
		if (value == null || value.isBlank() || "-".equals(value)) {
			return "—";
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
		String workflowRunUrl,
		String evidenceBundleSha256
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
				workflowRunUrl,
				evidenceBundleSha256
			);
		}
	}
}
