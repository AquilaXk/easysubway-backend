package com.easysubway.datapack.adapter.in.web;

import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackCandidateRepository;
import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackCandidateRepository.CandidateRow;
import com.easysubway.datapack.application.port.out.DatapackReleaseDeliveryRepository;
import com.easysubway.datapack.domain.DatapackReleaseDelivery;
import com.easysubway.datapack.application.port.out.DatapackReleaseRequestRepository;
import com.easysubway.datapack.application.service.DatapackReleaseRequestService;
import com.easysubway.datapack.application.service.DatapackReleaseRequestService.CreateReleaseRequestCommand;
import com.easysubway.datapack.domain.DatapackReleaseRequest;
import java.time.LocalDateTime;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
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

	DatapackReleaseRequestAdminPageController(
		JdbcDatapackCandidateRepository candidateRepository,
		DatapackReleaseRequestRepository releaseRequestRepository,
		DatapackReleaseRequestService releaseRequestService,
		DatapackReleaseDeliveryRepository deliveryRepository
	) {
		this.candidateRepository = candidateRepository;
		this.releaseRequestRepository = releaseRequestRepository;
		this.releaseRequestService = releaseRequestService;
		this.deliveryRepository = deliveryRepository;
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

	record DeliveryView(String releaseRequestId, long releaseSequence, String channel, String state,
		int attempts, LocalDateTime nextAttemptAt, LocalDateTime reconcileDeadline,
		LocalDateTime deadLetterDeadline, String httpClass, String detail,
		String payloadSha256, String signatureSha256) {
		static DeliveryView from(DatapackReleaseDelivery delivery) {
			return new DeliveryView(delivery.releaseRequestId(), delivery.releaseSequence(),
				delivery.channel(), delivery.state().name(), delivery.attempts(), delivery.nextAttemptAt(),
				delivery.reconcileDeadline(), delivery.deadLetterDeadline(), delivery.httpClass(),
				delivery.sanitizedDetail(), delivery.payloadSha256(), delivery.signatureSha256());
		}
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

	@PostMapping("/admin/datapack/release-requests/{approvalId}/approve")
	@PreAuthorize("hasAuthority('admin.datapack.production.approve')")
	String approve(@PathVariable("approvalId") String approvalId, Authentication authentication) {
		releaseRequestService.approve(approvalId, authentication.getName());
		return "redirect:/admin/datapack/release-requests/page";
	}

	// 자동 dispatch가 실패한(DISPATCH_FAILED) 승인 건을 재시도한다. 재시도는 production 게시
	// 워크플로를 재발화하므로 approve와 동일한 production approve 권한을 요구한다(권한 경계 일치).
	@PostMapping("/admin/datapack/release-requests/{approvalId}/retry-dispatch")
	@PreAuthorize("hasAuthority('admin.datapack.production.approve')")
	String retryDispatch(@PathVariable("approvalId") String approvalId) {
		releaseRequestService.retryDispatch(approvalId);
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
				r.approvedBy() == null ? "-" : r.approvedBy(),
				r.workflowRunUrl(), r.createdAt(),
				r.promoteOutcome(), r.promoteDetail());
		}
	}
}
