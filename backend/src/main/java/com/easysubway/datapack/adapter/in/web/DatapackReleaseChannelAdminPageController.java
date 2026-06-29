package com.easysubway.datapack.adapter.in.web;

import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseChannelRepository;
import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseChannelRepository.ReleaseChannelEventRow;
import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseChannelRepository.ReleaseChannelRow;
import java.time.LocalDateTime;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class DatapackReleaseChannelAdminPageController {

	private static final int EVENT_LIMIT = 20;

	private final JdbcDatapackReleaseChannelRepository releaseChannelRepository;

	DatapackReleaseChannelAdminPageController(JdbcDatapackReleaseChannelRepository releaseChannelRepository) {
		this.releaseChannelRepository = releaseChannelRepository;
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
}
