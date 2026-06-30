package com.easysubway.datapack.adapter.in.web;

import com.easysubway.admin.audit.application.service.AdminAuditWriter;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackCandidateRepository;
import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackCandidateRepository.CandidateInputRow;
import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackCandidateRepository.CandidateRow;
import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackCandidateRepository.EvidenceBundleRow;
import com.easysubway.datapack.application.service.DatapackCandidateCommandService;
import com.easysubway.datapack.application.service.DatapackCandidateCommandService.CandidateGateRerunCommand;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
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
import org.springframework.web.server.ResponseStatusException;

@Controller
class DatapackCandidateAdminPageController {

	private static final int CANDIDATE_LIMIT = 200;

	private final JdbcDatapackCandidateRepository candidateRepository;
	private final DatapackCandidateCommandService commandService;
	private final AdminAuditWriter auditWriter;

	DatapackCandidateAdminPageController(
		JdbcDatapackCandidateRepository candidateRepository,
		DatapackCandidateCommandService commandService,
		AdminAuditWriter auditWriter
	) {
		this.candidateRepository = candidateRepository;
		this.commandService = commandService;
		this.auditWriter = auditWriter;
	}

	@GetMapping("/admin/datapack/candidates/page")
	@PreAuthorize("hasAuthority('admin.datapack.read')")
	String candidates(Model model) {
		model.addAttribute("candidates", candidateRepository.listRecentCandidates(CANDIDATE_LIMIT).stream()
			.map(CandidateView::from)
			.toList());
		return "admin/datapack/candidates/list";
	}

	@GetMapping("/admin/datapack/candidates/{candidateId}/page")
	@PreAuthorize("hasAuthority('admin.datapack.read')")
	String candidateDetail(@PathVariable String candidateId, Model model) {
		var candidate = candidateRepository.findCandidate(candidateId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		model.addAttribute("candidate", CandidateView.from(candidate));
		model.addAttribute("candidateInput", candidateRepository.findInput(candidateId)
			.map(CandidateInputView::from)
			.orElse(CandidateInputView.empty(candidateId)));
		model.addAttribute("evidenceBundle", candidateRepository.findEvidenceBundle(candidateId)
			.map(EvidenceBundleView::from)
			.orElse(EvidenceBundleView.empty(candidateId)));
		return "admin/datapack/candidates/detail";
	}

	@PostMapping("/admin/datapack/candidates/{candidateId}/rerun-gates")
	@PreAuthorize("hasAuthority('admin.datapack.candidate.build')")
	String rerunGates(
		@PathVariable String candidateId,
		@ModelAttribute CandidateGateRerunForm form,
		Authentication authentication,
		HttpServletRequest request
	) {
		commandService.rerunGates(candidateId, form.toCommand());
		auditWriter.datapackCommand(
			authentication,
			request,
			"DATAPACK_CANDIDATE",
			candidateId,
			"RERUN_GATES",
			AdminAuditOutcome.SUCCESS,
			form.reason()
		);
		return "redirect:/admin/datapack/candidates/%s/page".formatted(candidateId);
	}

	record CandidateView(
		String id,
		String scopeId,
		String artifactKind,
		String version,
		String sourceSnapshotSetHash,
		String overrideSetHash,
		String buildSpecSha256,
		String sourceInventorySha256,
		String sqliteSha256,
		String gzipSha256,
		String manifestSha256,
		String coverageStatus,
		String validatorStatus,
		String routeRegressionStatus,
		String androidEvidenceStatus,
		String approvalStatus,
		LocalDateTime createdAt
	) {

		static CandidateView from(CandidateRow row) {
			return new CandidateView(
				row.id(),
				row.scopeId(),
				row.artifactKind(),
				row.version(),
				row.sourceSnapshotSetHash(),
				row.overrideSetHash(),
				row.buildSpecSha256(),
				row.sourceInventorySha256(),
				valueOrDash(row.sqliteSha256()),
				valueOrDash(row.gzipSha256()),
				valueOrDash(row.manifestSha256()),
				row.coverageStatus(),
				row.validatorStatus(),
				row.routeRegressionStatus(),
				row.androidEvidenceStatus(),
				row.approvalStatus(),
				row.createdAt()
			);
		}
	}

	record CandidateInputView(
		String id,
		String candidateId,
		String sourceSnapshotIds,
		String approvedAliasLedgerHash,
		String facilityEvidenceLedgerHash,
		String routeEvidenceLedgerHash,
		String approvedOverrideSetHash,
		LocalDateTime createdAt
	) {

		static CandidateInputView from(CandidateInputRow row) {
			return new CandidateInputView(
				row.id(),
				row.candidateId(),
				row.sourceSnapshotIds(),
				row.approvedAliasLedgerHash(),
				row.facilityEvidenceLedgerHash(),
				row.routeEvidenceLedgerHash(),
				row.approvedOverrideSetHash(),
				row.createdAt()
			);
		}

		static CandidateInputView empty(String candidateId) {
			return new CandidateInputView("-", candidateId, "-", "-", "-", "-", "-", null);
		}
	}

	record EvidenceBundleView(
		String id,
		String candidateId,
		String evidenceBundleSha256,
		String workflowRunUrl,
		String validatorStatus,
		String routeRegressionStatus,
		String manifestSignatureStatus,
		String androidEvidenceStatus,
		LocalDateTime createdAt
	) {

		static EvidenceBundleView from(EvidenceBundleRow row) {
			return new EvidenceBundleView(
				row.id(),
				row.candidateId(),
				row.evidenceBundleSha256(),
				redactedUrl(row.workflowRunUrl()),
				row.validatorStatus(),
				row.routeRegressionStatus(),
				row.manifestSignatureStatus(),
				row.androidEvidenceStatus(),
				row.createdAt()
			);
		}

		static EvidenceBundleView empty(String candidateId) {
			return new EvidenceBundleView("-", candidateId, "-", "-", "-", "-", "-", "-", null);
		}

		public boolean productionPromoteAllowed() {
			return List.of(validatorStatus, routeRegressionStatus, manifestSignatureStatus, androidEvidenceStatus)
				.stream()
				.allMatch("PASS"::equals);
		}

		public String productionPromoteReason() {
			if (productionPromoteAllowed()) {
				return "production promote 가능";
			}
			return "production promote 차단: evidence bundle PASS 필요";
		}
	}

	private static String valueOrDash(String value) {
		if (value == null || value.isBlank()) {
			return "-";
		}
		return value;
	}

	private static String redactedUrl(String value) {
		String text = valueOrDash(value);
		int query = text.indexOf('?');
		int fragment = text.indexOf('#');
		int cut = query < 0 ? fragment : (fragment < 0 ? query : Math.min(query, fragment));
		return cut < 0 ? text : text.substring(0, cut) + "?redacted";
	}

	record CandidateGateRerunForm(String reason, String idempotencyKey) {

		CandidateGateRerunCommand toCommand() {
			return new CandidateGateRerunCommand(reason, idempotencyKey);
		}
	}
}
