package com.easysubway.datapack.adapter.in.web;

import com.easysubway.common.web.pagination.AdminPageRequest;
import com.easysubway.common.web.pagination.EgovPaginationView;
import com.easysubway.datapack.adapter.out.persistence.JdbcDataSourceSnapshotRepository;
import com.easysubway.datapack.application.service.DatapackSourceSnapshotCommandService;
import com.easysubway.datapack.application.service.DatapackSourceSnapshotCommandService.SourceSnapshotCommand;
import com.easysubway.datapack.domain.DataSourceSnapshot;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
class DataSourceSnapshotAdminPageController {

	private final JdbcDataSourceSnapshotRepository snapshotRepository;
	private final DatapackSourceSnapshotCommandService snapshotCommandService;

	DataSourceSnapshotAdminPageController(
		JdbcDataSourceSnapshotRepository snapshotRepository,
		DatapackSourceSnapshotCommandService snapshotCommandService
	) {
		this.snapshotRepository = snapshotRepository;
		this.snapshotCommandService = snapshotCommandService;
	}

	@GetMapping("/admin/datapack/source-snapshots/page")
	@PreAuthorize("hasAuthority('admin.datapack.read')")
	String listSourceSnapshots(
		@RequestParam(required = false) Integer page,
		@RequestParam(required = false) Integer size,
		Model model
	) {
		AdminPageRequest pageRequest = AdminPageRequest.of(page, size);
		List<SourceSnapshotRow> rows = snapshotRepository
			.listRecentSnapshots(pageRequest.limitForHasNext(), pageRequest.offset())
			.stream()
			.map(SourceSnapshotRow::from)
			.toList();
		EgovPaginationView pageView = EgovPaginationView.fromSlice(pageRequest.page(), pageRequest.size(), rows.size());
		model.addAttribute("snapshots", pageView.visibleItems(rows));
		model.addAttribute("page", pageView);
		model.addAttribute(
			"paginationLinks",
			pageView.links("/admin/datapack/source-snapshots/page", Collections.emptyMap())
		);
		return "admin/datapack/source-snapshots/list";
	}

	@GetMapping("/admin/datapack/source-snapshots/{snapshotId}/page")
	@PreAuthorize("hasAuthority('admin.datapack.read')")
	String sourceSnapshotDetail(@PathVariable String snapshotId, Model model) {
		SourceSnapshotRow snapshot = snapshotRepository.loadSnapshot(snapshotId)
			.map(SourceSnapshotRow::from)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source snapshot not found."));
		model.addAttribute("snapshot", snapshot);
		return "admin/datapack/source-snapshots/detail";
	}

	@PostMapping("/admin/datapack/source-snapshots")
	@PreAuthorize("hasAuthority('admin.datapack.source.run')")
	String createSourceSnapshot(@ModelAttribute SourceSnapshotCommandForm form, Authentication authentication) {
		String snapshotId = snapshotCommandService.createLockedSnapshot(form.toCommand(authentication.getName()));
		return "redirect:/admin/datapack/source-snapshots/%s/page".formatted(snapshotId);
	}

	record SourceSnapshotRow(
		String snapshotId,
		String sourceId,
		String provider,
		LocalDateTime retrievedAt,
		LocalDateTime sourceUpdatedAt,
		int rowCount,
		String rawSha256,
		String rawObjectUri,
		String redactedRequestFingerprint,
		String schemaFingerprint,
		String snapshotStatus,
		String schemaStatus,
		String licenseStatus,
		String fetchStatus,
		boolean redistributionAllowed,
		boolean credentialRedacted,
		String previousSnapshotId,
		String diffSummary,
		LocalDateTime freshnessExpiresAt,
		LocalDateTime rawRetentionExpiresAt
	) {

		static SourceSnapshotRow from(DataSourceSnapshot snapshot) {
			return new SourceSnapshotRow(
				snapshot.snapshotId(),
				snapshot.sourceId(),
				snapshot.provider(),
				snapshot.retrievedAt(),
				snapshot.sourceUpdatedAt(),
				snapshot.rowCount(),
				snapshot.rawSha256(),
				snapshot.rawObjectUri(),
				snapshot.redactedRequestFingerprint(),
				snapshot.schemaFingerprint(),
				snapshot.snapshotStatus(),
				snapshot.schemaStatus(),
				snapshot.licenseStatus(),
				snapshot.fetchStatus(),
				snapshot.redistributionAllowed(),
				snapshot.credentialRedacted(),
				valueOrDash(snapshot.previousSnapshotId()),
				valueOrDash(snapshot.diffSummary()),
				snapshot.freshnessExpiresAt(),
				snapshot.rawRetentionExpiresAt()
			);
		}

		public String credentialRedactedLabel() {
			return credentialRedacted ? "credential redacted" : "credential redaction 필요";
		}

		public String redistributionLabel() {
			return redistributionAllowed ? "허용" : "불가";
		}

		private static String valueOrDash(String value) {
			if (value == null || value.isBlank()) {
				return "-";
			}
			return value;
		}
	}

	record SourceSnapshotCommandForm(
		String snapshotId,
		String sourceId,
		String provider,
		LocalDateTime retrievedAt,
		LocalDateTime sourceUpdatedAt,
		int rowCount,
		String rawSha256,
		String rawObjectUri,
		String redactedRequestFingerprint,
		String schemaFingerprint,
		String schemaStatus,
		String licenseStatus,
		String fetchStatus,
		boolean redistributionAllowed,
		boolean credentialRedacted,
		String previousSnapshotId,
		String diffSummary,
		LocalDateTime freshnessExpiresAt,
		LocalDateTime rawRetentionExpiresAt,
		String reason,
		String idempotencyKey
	) {

		SourceSnapshotCommand toCommand(String requestedBy) {
			return new SourceSnapshotCommand(
				snapshotId,
				sourceId,
				provider,
				retrievedAt,
				sourceUpdatedAt,
				rowCount,
				rawSha256,
				rawObjectUri,
				redactedRequestFingerprint,
				schemaFingerprint,
				schemaStatus,
				licenseStatus,
				fetchStatus,
				redistributionAllowed,
				credentialRedacted,
				previousSnapshotId,
				diffSummary,
				freshnessExpiresAt,
				rawRetentionExpiresAt,
				requestedBy,
				reason,
				idempotencyKey
			);
		}
	}
}
