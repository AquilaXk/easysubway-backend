package com.easysubway.datapack.adapter.in.web;

import com.easysubway.datapack.application.port.in.DatapackReleaseBlockerSummaryUseCase;
import com.easysubway.datapack.application.port.in.DatapackReleaseBlockerSummaryUseCase.DatapackReleaseBlockerSummary;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 데이터팩 파이프라인 개요(#1745). 원천 스냅샷 → 후보 생성 → 게이트 6종 → 채널 승격을 한 화면의
 * 단계 그래프로 보여주고, 각 단계에서 해당 화면으로 드릴다운한다. 단계별 blocker 수는 대시보드·역
 * 요약과 같은 {@link DatapackReleaseBlockerSummaryUseCase}를 재사용해 정합을 보장한다(중복 집계 없음).
 *
 * <p>승인·게시 트리거 자체는 #1694 범위다. 이 화면은 정보 구조·드릴다운까지만 제공한다.
 */
@Controller
class DatapackPipelineAdminPageController {

	private final DatapackReleaseBlockerSummaryUseCase releaseBlockerSummaryUseCase;

	DatapackPipelineAdminPageController(DatapackReleaseBlockerSummaryUseCase releaseBlockerSummaryUseCase) {
		this.releaseBlockerSummaryUseCase = releaseBlockerSummaryUseCase;
	}

	@GetMapping("/admin/datapack/pipeline/page")
	@PreAuthorize("hasAuthority('admin.datapack.read')")
	String pipeline(Model model) {
		model.addAttribute("pipeline", PipelineView.from(releaseBlockerSummaryUseCase.summarize()));
		return "admin/datapack/pipeline/list";
	}

	/**
	 * 파이프라인 화면 뷰. 단계 그래프 노드와 sha 축약·채널 승격 정보를 표시용으로 정리한다.
	 */
	record PipelineView(
		String candidateId,
		String candidateIdShort,
		String scopeId,
		String status,
		String statusTone,
		String candidateCreatedAt,
		Sha sourceSnapshotSetHash,
		Sha manifestSha256,
		Sha evidenceBundleSha256,
		String evidenceWorkflowRunUrl,
		String productionCandidateId,
		String rollbackCandidateId,
		long totalBlockers,
		boolean productionPromoteAllowed,
		String productionPromoteReason,
		List<StageNode> stages
	) {

		static PipelineView from(DatapackReleaseBlockerSummary summary) {
			boolean hasCandidate = !"-".equals(summary.candidateId());
			String candidateDrill = hasCandidate
				? "/admin/datapack/candidates/" + summary.candidateId() + "/page"
				: "/admin/datapack/candidates/page";
			List<StageNode> stages = List.of(
				stage(1, "원천 스냅샷", 0, "/admin/datapack/source-snapshots/page", "원천 스냅샷 목록"),
				stage(2, "후보 생성", 0, candidateDrill, hasCandidate ? "후보 상세" : "후보 목록"),
				stage(3, "후보 게이트", summary.candidateGateBlockers(), "/admin/datapack/candidates/page", "후보 팩"),
				stage(4, "별칭·격리", summary.aliasBlockers() + summary.quarantineBlockers(),
					"/admin/datapack/alias-quarantine/page", "별칭·격리 검토"),
				stage(5, "시설 근거", summary.facilityBlockers(), "/admin/datapack/facility-evidence/page", "시설 근거 검토"),
				stage(6, "경로 게이트", summary.routeGateBlockers(), "/admin/datapack/route-gates/page", "경로 게이트"),
				stage(7, "수동 오버라이드", summary.manualOverrideBlockers(),
					"/admin/datapack/manual-overrides/page", "수동 오버라이드"),
				stage(8, "매니페스트 서명", summary.manifestBlockers(), candidateDrill, "후보 상세"),
				stage(9, "채널 승격", 0, "/admin/datapack/release-channels/page", "배포 채널"));
			return new PipelineView(
				summary.candidateId(),
				shortHash(summary.candidateId()),
				summary.scopeId(),
				summary.status(),
				statusTone(summary.status()),
				summary.candidateCreatedAt() == null ? "-" : summary.candidateCreatedAt().toString(),
				Sha.of(summary.sourceSnapshotSetHash()),
				Sha.of(summary.manifestSha256()),
				Sha.of(summary.evidenceBundleSha256()),
				summary.evidenceWorkflowRunUrl(),
				summary.productionCandidateId(),
				summary.rollbackCandidateId(),
				summary.totalBlockers(),
				summary.productionPromoteAllowed(),
				summary.productionPromoteReason(),
				stages);
		}

		private static StageNode stage(int order, String label, long blockerCount, String drillUrl, String drillLabel) {
			return new StageNode(order, label, blockerCount, blockerCount > 0, drillUrl, drillLabel);
		}

		private static String statusTone(String status) {
			return switch (status) {
				case "READY" -> "good";
				case "확인 필요" -> "warn";
				default -> "bad";
			};
		}

		private static String shortHash(String value) {
			if (value == null || value.isBlank() || "-".equals(value)) {
				return "-";
			}
			return value.length() <= 8 ? value : value.substring(0, 8) + "…";
		}
	}

	/** 단계 그래프 노드. blocker>0이면 blocked 강조하고, 클릭 시 해당 화면으로 드릴다운한다. */
	record StageNode(int order, String label, long blockerCount, boolean blocked, String drillUrl, String drillLabel) {
	}

	/** sha 표기: 축약(첫 8자)과 원문을 함께 담아 복사·hover 전체 확인을 지원한다. */
	record Sha(String shortValue, String fullValue) {

		static Sha of(String value) {
			if (value == null || value.isBlank() || "-".equals(value)) {
				return new Sha("-", "-");
			}
			String shortValue = value.length() <= 8 ? value : value.substring(0, 8) + "…";
			return new Sha(shortValue, value);
		}
	}
}
