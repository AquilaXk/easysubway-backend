package com.easysubway.datapack.adapter.in.web;

import com.easysubway.admin.audit.application.service.AdminAuditWriter;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import com.easysubway.datapack.adapter.out.persistence.JdbcFacilityEvidenceMatrixRepository;
import com.easysubway.datapack.adapter.out.persistence.JdbcFacilityEvidenceMatrixRepository.FacilityEvidenceRow;
import com.easysubway.datapack.application.service.DatapackFacilityEvidenceCommandService;
import com.easysubway.datapack.application.service.DatapackFacilityEvidenceCommandService.FacilityEvidenceReviewCommand;
import jakarta.servlet.http.HttpServletRequest;
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
class FacilityEvidenceAdminPageController {

	private static final int MATRIX_LIMIT = 200;

	private final JdbcFacilityEvidenceMatrixRepository matrixRepository;
	private final DatapackFacilityEvidenceCommandService commandService;
	private final AdminAuditWriter auditWriter;

	FacilityEvidenceAdminPageController(
		JdbcFacilityEvidenceMatrixRepository matrixRepository,
		DatapackFacilityEvidenceCommandService commandService,
		AdminAuditWriter auditWriter
	) {
		this.matrixRepository = matrixRepository;
		this.commandService = commandService;
		this.auditWriter = auditWriter;
	}

	@GetMapping("/admin/datapack/facility-evidence/page")
	@PreAuthorize("hasAuthority('admin.datapack.read')")
	String facilityEvidenceMatrix(Model model) {
		model.addAttribute("evidenceRows", matrixRepository.listRecentEvidence(MATRIX_LIMIT).stream()
			.map(FacilityEvidenceView::from)
			.toList());
		return "admin/datapack/facility-evidence/list";
	}

	@PostMapping("/admin/datapack/facility-evidence/{evidenceId}/review")
	@PreAuthorize("hasAuthority('admin.datapack.evidence.review')")
	String reviewFacilityEvidence(
		@PathVariable String evidenceId,
		@ModelAttribute FacilityEvidenceReviewForm form,
		Authentication authentication,
		HttpServletRequest request
	) {
		commandService.reviewEvidence(evidenceId, form.toCommand());
		auditWriter.datapackCommand(
			authentication,
			request,
			"DATAPACK_FACILITY_EVIDENCE",
			evidenceId,
			"REVIEW",
			AdminAuditOutcome.SUCCESS,
			form.reason()
		);
		return "redirect:/admin/datapack/facility-evidence/page";
	}

	record FacilityEvidenceView(
		String id,
		String stationId,
		String lineId,
		String facilityType,
		String evidenceKind,
		String sourceId,
		String sourceSnapshotId,
		String providerRecordHash,
		String statusMeaning,
		String installationStatus,
		String operationalStatus,
		LocalDateTime verifiedAt,
		LocalDateTime retrievedAt,
		LocalDateTime freshnessExpiresAt,
		int confidence,
		boolean strictRouteEligible,
		String strictRouteLabel,
		String strictRouteReason,
		String conflictStatus,
		String manualOverrideId
	) {

		static FacilityEvidenceView from(FacilityEvidenceRow row) {
			return new FacilityEvidenceView(
				row.id(),
				row.stationId(),
				valueOrDash(row.lineId()),
				row.facilityType(),
				row.evidenceKind(),
				row.sourceId(),
				row.sourceSnapshotId(),
				row.providerRecordHash(),
				row.statusMeaning(),
				row.installationStatus(),
				row.operationalStatus(),
				row.verifiedAt(),
				row.retrievedAt(),
				row.freshnessExpiresAt(),
				row.confidence(),
				row.strictRouteEligible(),
				row.strictRouteEligible() ? "strict 가능" : "strict 불가",
				valueOrDash(row.strictRouteEligibleReason()),
				row.conflictStatus(),
				valueOrDash(row.manualOverrideId())
			);
		}
	}

	private static String valueOrDash(String value) {
		if (value == null || value.isBlank()) {
			return "-";
		}
		return value;
	}

	record FacilityEvidenceReviewForm(
		boolean strictRouteEligible,
		String strictRouteEligibleReason,
		String conflictStatus,
		String reason,
		String idempotencyKey
	) {

		FacilityEvidenceReviewCommand toCommand() {
			return new FacilityEvidenceReviewCommand(
				strictRouteEligible,
				strictRouteEligibleReason,
				conflictStatus,
				reason,
				idempotencyKey
			);
		}
	}
}
