package com.easysubway.datapack.adapter.in.web;

import com.easysubway.admin.audit.application.service.AdminAuditWriter;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackCandidateRepository;
import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackCandidateRepository.CandidateRow;
import com.easysubway.datapack.application.port.out.DatapackReleaseDeliveryRepository;
import com.easysubway.datapack.domain.DatapackReleaseDelivery;
import com.easysubway.datapack.application.port.out.DatapackReleaseRequestRepository;
import com.easysubway.datapack.application.service.DatapackReleaseRequestService;
import com.easysubway.datapack.application.service.DatapackReleaseRequestService.CreateReleaseRequestCommand;
import com.easysubway.datapack.domain.DatapackReleaseRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
class DatapackReleaseRequestAdminPageController {

	private static final int CANDIDATE_LIMIT = 20;
	private static final int REQUEST_LIMIT = 20;
	private static final int DELIVERY_LIMIT = 20;
	// 릴리스 요청 대상은 승인/승격을 통과한 candidate만(미승인·실패 후보의 게시 경로 차단).
	private static final Set<String> RELEASE_ELIGIBLE_CANDIDATE_STATUSES = Set.of("APPROVED", "PROMOTED");

	private final JdbcDatapackCandidateRepository candidateRepository;
	private final DatapackReleaseRequestRepository releaseRequestRepository;
	private final DatapackReleaseRequestService releaseRequestService;
	private final DatapackReleaseDeliveryRepository deliveryRepository;
	private final AdminAuditWriter auditWriter;

	DatapackReleaseRequestAdminPageController(
		JdbcDatapackCandidateRepository candidateRepository,
		DatapackReleaseRequestRepository releaseRequestRepository,
		DatapackReleaseRequestService releaseRequestService,
		DatapackReleaseDeliveryRepository deliveryRepository,
		AdminAuditWriter auditWriter
	) {
		this.candidateRepository = candidateRepository;
		this.releaseRequestRepository = releaseRequestRepository;
		this.releaseRequestService = releaseRequestService;
		this.deliveryRepository = deliveryRepository;
		this.auditWriter = auditWriter;
	}

	@GetMapping("/admin/datapack/release-requests/page")
	@PreAuthorize("hasAuthority('admin.datapack.read')")
	String page(Model model) {
		model.addAttribute("candidates", candidateRepository.listRecentCandidates(CANDIDATE_LIMIT).stream()
			.filter(c -> RELEASE_ELIGIBLE_CANDIDATE_STATUSES.contains(c.approvalStatus()))
			.map(CandidateOption::from)
			.toList());
		model.addAttribute("requests", releaseRequestRepository.findRecent(REQUEST_LIMIT).stream()
			.map(ReleaseRequestView::from)
			.toList());
		model.addAttribute("deliveries", deliveryRepository.findRecent(DELIVERY_LIMIT).stream()
			.map(DeliveryView::from).toList());
		return "admin/datapack/release-requests/list";
	}

	record DeliveryView(String idempotencyKey, String releaseRequestId, long releaseSequence,
		String channel, String state, boolean repairable,
		int attempts, LocalDateTime nextAttemptAt, LocalDateTime reconcileDeadline,
		LocalDateTime deadLetterDeadline, String httpClass, String detail,
		String payloadSha256, String signatureSha256) {
		static DeliveryView from(DatapackReleaseDelivery delivery) {
			boolean repairable = delivery.state() == DatapackReleaseDelivery.State.RECONCILIATION_REQUIRED
				|| delivery.state() == DatapackReleaseDelivery.State.DEAD_LETTER;
			return new DeliveryView(delivery.idempotencyKey(), delivery.releaseRequestId(),
				delivery.releaseSequence(), delivery.channel(), delivery.state().name(), repairable,
				delivery.attempts(), delivery.nextAttemptAt(),
				delivery.reconcileDeadline(), delivery.deadLetterDeadline(), delivery.httpClass(),
				delivery.sanitizedDetail(), delivery.payloadSha256(), delivery.signatureSha256());
		}
	}

	@PostMapping("/admin/datapack/release-deliveries/{idempotencyKey}/repair")
	@PreAuthorize("hasAuthority('admin.datapack.production.approve')")
	@Transactional
	String repairDelivery(
		@PathVariable("idempotencyKey") String idempotencyKey,
		@RequestParam("reason") String reason,
		Authentication authentication,
		HttpServletRequest request
	) {
		String operatorReason = manualRepairReason(reason);
		var repaired = deliveryRepository.scheduleManualRepair(
			idempotencyKey, LocalDateTime.now(Clock.systemUTC()));
		auditWriter.adminAction(
			authentication,
			request,
			"DATAPACK_RELEASE_DELIVERY",
			auditWriter.sha256TargetId(repaired.after().idempotencyKey()),
			"MANUAL_REPAIR",
			AdminAuditOutcome.SUCCESS,
			"before=" + repaired.before().name()
				+ ";after=" + repaired.after().state().name()
				+ ";operatorReason=" + operatorReason
		);
		return "redirect:/admin/datapack/release-requests/page";
	}

	private static String manualRepairReason(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("manual repair reason is required");
		}
		String reason = value.trim();
		if (reason.length() > 200 || reason.indexOf(';') >= 0
			|| reason.chars().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException("manual repair reason is invalid");
		}
		return reason;
	}

	@PostMapping("/admin/datapack/release-requests")
	@PreAuthorize("hasAuthority('admin.datapack.staging.promote')")
	String create(
		@RequestParam("candidateId") String candidateId,
		@RequestParam("targetChannel") String targetChannel,
		Authentication authentication
	) {
		CandidateRow candidate = candidateRepository.findCandidate(candidateId)
			.orElseThrow(() -> new IllegalArgumentException("candidate not found: " + candidateId));
		// 서버측 적격성 강제: 폼 우회로 미승인/실패 candidate가 release request가 되는 것을 차단.
		if (!RELEASE_ELIGIBLE_CANDIDATE_STATUSES.contains(candidate.approvalStatus())) {
			throw new IllegalArgumentException(
				"candidate is not release-eligible: " + candidate.approvalStatus());
		}
		// 스펙 A-1: 파생값은 candidate에서 채운다(사용자 자유 입력 아님).
		// approvedLedgerHash ← candidate.overrideSetHash(승인된 오버라이드 장부 해시).
		releaseRequestService.create(new CreateReleaseRequestCommand(
			candidate.id(),
			candidate.scopeId(),
			targetChannel,
			candidate.buildSpecSha256(),
			candidate.sourceSnapshotSetHash(),
			candidate.overrideSetHash(),
			authentication.getName()));
		return "redirect:/admin/datapack/release-requests/page";
	}

	// 승인은 레코드 상태 전이까지다 — 게시 워크플로는 여기서 발화하지 않는다(#2564).
	@PostMapping("/admin/datapack/release-requests/{approvalId}/approve")
	@PreAuthorize("hasAuthority('admin.datapack.production.approve')")
	String approve(@PathVariable("approvalId") String approvalId, Authentication authentication) {
		releaseRequestService.approve(approvalId, authentication.getName());
		return "redirect:/admin/datapack/release-requests/page";
	}

	record CandidateOption(String id, String version, String scopeId, String approvalStatus) {
		static CandidateOption from(CandidateRow row) {
			return new CandidateOption(row.id(), row.version(), row.scopeId(), row.approvalStatus());
		}
	}

	record ReleaseRequestView(
		String approvalId,
		String candidateId,
		String scopeId,
		String targetChannel,
		String status,
		String requestedBy,
		String approvedBy,
		String workflowRunUrl,
		LocalDateTime createdAt,
		String promoteOutcome,
		String promoteDetail
	) {
		static ReleaseRequestView from(DatapackReleaseRequest r) {
			return new ReleaseRequestView(
				r.approvalId(), r.candidateId(), r.scopeId(), r.targetChannel(),
				r.status().name(), r.requestedBy(),
				r.approvedBy() == null ? "—" : r.approvedBy(),
				r.workflowRunUrl(), r.createdAt(),
				r.promoteOutcome(), r.promoteDetail());
		}
	}
}
