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
import java.util.Comparator;
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
	String candidates(
		@RequestParam(required = false) String query,
		@RequestParam(required = false) String status,
		@RequestParam(required = false) String candidateId,
		@RequestParam(required = false) String sort,
		Model model
	) {
		DatapackAdminListQuery filter = DatapackAdminListQuery.of(query, status, candidateId, null, sort);
		model.addAttribute("candidates", candidateRepository.listRecentCandidates(CANDIDATE_LIMIT).stream()
			.map(CandidateView::from)
			.filter(candidate -> filter.matchesCandidate(candidate.id()))
			.filter(candidate -> filter.matchesText(
				candidate.id(),
				candidate.scopeId(),
				candidate.version(),
				candidate.approvalStatus(),
				candidate.coverageStatus(),
				candidate.validatorStatus(),
				candidate.routeRegressionStatus(),
				candidate.androidEvidenceStatus()
			))
			.filter(candidate -> candidateStatusMatches(candidate, filter.statusValue()))
			.sorted(candidateSort(filter.sortValue()))
			.toList());
		model.addAttribute("filter", filter);
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
		auditWriter.adminAction(
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

		public String sourceSnapshotSetHashShort() {
			return shortHash(sourceSnapshotSetHash);
		}

		public String overrideSetHashShort() {
			return shortHash(overrideSetHash);
		}

		public String buildSpecSha256Short() {
			return shortHash(buildSpecSha256);
		}

		public String sourceInventorySha256Short() {
			return shortHash(sourceInventorySha256);
		}

		public String sqliteSha256Short() {
			return shortHash(sqliteSha256);
		}

		public String gzipSha256Short() {
			return shortHash(gzipSha256);
		}

		public String manifestSha256Short() {
			return shortHash(manifestSha256);
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

		public String approvedAliasLedgerHashShort() {
			return shortHash(approvedAliasLedgerHash);
		}

		public String facilityEvidenceLedgerHashShort() {
			return shortHash(facilityEvidenceLedgerHash);
		}

		public String routeEvidenceLedgerHashShort() {
			return shortHash(routeEvidenceLedgerHash);
		}

		public String approvedOverrideSetHashShort() {
			return shortHash(approvedOverrideSetHash);
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

		public String evidenceBundleSha256Short() {
			return shortHash(evidenceBundleSha256);
		}

		public boolean productionPromoteAllowed() {
			return List.of(validatorStatus, routeRegressionStatus, manifestSignatureStatus, androidEvidenceStatus)
				.stream()
				.allMatch("PASS"::equals);
		}

		public String productionPromoteReason() {
			if (productionPromoteAllowed()) {
				return "프로덕션 반영 가능";
			}
			return "프로덕션 반영 차단: 증거 번들 PASS 필요";
		}
	}

	private static boolean candidateStatusMatches(CandidateView candidate, String status) {
		return switch (status) {
			case "ALL" -> true;
			case "BLOCKER" -> List.of(
				candidate.coverageStatus(),
				candidate.validatorStatus(),
				candidate.routeRegressionStatus(),
				candidate.androidEvidenceStatus()
			).stream().anyMatch(value -> !"PASS".equals(value));
			case "READY" -> "READY_FOR_APPROVAL".equals(candidate.approvalStatus());
			default -> status.equals(candidate.approvalStatus());
		};
	}

	private static Comparator<CandidateView> candidateSort(String sort) {
		return switch (sort) {
			case "candidate" -> Comparator.comparing(CandidateView::id);
			case "created_asc" -> Comparator.comparing(CandidateView::createdAt);
			default -> Comparator.comparing(CandidateView::createdAt).reversed().thenComparing(CandidateView::id);
		};
	}

	private static String valueOrDash(String value) {
		if (value == null || value.isBlank()) {
			return "-";
		}
		return value;
	}

	private static String shortHash(String value) {
		if (value == null || value.isBlank() || "-".equals(value)) {
			return "-";
		}
		return value.length() <= 8 ? value : value.substring(0, 8) + "…";
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
