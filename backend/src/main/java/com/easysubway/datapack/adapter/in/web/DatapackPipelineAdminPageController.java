package com.easysubway.datapack.adapter.in.web;

import com.easysubway.datapack.application.port.in.DatapackReleaseBlockerSummaryUseCase;
import com.easysubway.datapack.application.port.in.DatapackReleaseBlockerSummaryUseCase.DatapackReleaseBlockerSummary;
import com.easysubway.datapack.application.port.in.DatapackReleaseBlockerSummaryUseCase.ReleaseReadinessRow;
import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackCandidateRepository;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.util.UriComponentsBuilder;

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
	private final JdbcDatapackCandidateRepository candidateRepository;

	DatapackPipelineAdminPageController(
		DatapackReleaseBlockerSummaryUseCase releaseBlockerSummaryUseCase,
		JdbcDatapackCandidateRepository candidateRepository
	) {
		this.releaseBlockerSummaryUseCase = releaseBlockerSummaryUseCase;
		this.candidateRepository = candidateRepository;
	}

	@GetMapping("/admin/datapack/pipeline/page")
	@PreAuthorize("hasAuthority('admin.datapack.read')")
	String pipeline(Model model) {
		DatapackReleaseBlockerSummary summary = releaseBlockerSummaryUseCase.summarize();
		CandidateContext candidateContext = CandidateContext.from(summary.candidateId(), candidateRepository);
		model.addAttribute("pipeline", PipelineView.from(summary, candidateContext));
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
		String sourceSnapshotIds,
		String firstSourceSnapshotId,
		long totalBlockers,
		boolean productionPromoteAllowed,
		String productionPromoteReason,
		List<StageNode> stages,
		List<GateNode> gateNodes
	) {

		static PipelineView from(DatapackReleaseBlockerSummary summary, CandidateContext candidateContext) {
			boolean hasCandidate = !"-".equals(summary.candidateId());
			String candidateId = hasCandidate ? summary.candidateId() : null;
			String sourceSnapshotId = candidateContext.sourceSnapshotFilter();
			String candidateDrill = hasCandidate
				? "/admin/datapack/candidates/" + summary.candidateId() + "/page"
				: "/admin/datapack/candidates/page";
			List<StageNode> stages = List.of(
				stage(1, "원천 스냅샷", summary.sourceFreshnessBlockers(),
					url("/admin/datapack/source-snapshots/page", candidateId, sourceSnapshotId, null),
					"원천 스냅샷 목록"),
				stage(2, "후보 생성", 0, candidateDrill, hasCandidate ? "후보 상세" : "후보 목록"),
				stage(3, "후보 게이트", summary.candidateGateBlockers(),
					url("/admin/datapack/candidates/page", candidateId, null, "BLOCKER"),
					"후보 팩"),
				stage(4, "별칭·격리", summary.aliasBlockers() + summary.quarantineBlockers(),
					url("/admin/datapack/alias-quarantine/page", null, null, "BLOCKER"),
					"별칭·격리 검토"),
				stage(5, "시설 근거", summary.facilityBlockers(),
					url("/admin/datapack/facility-evidence/page", null, null, "BLOCKER"),
					"시설 근거 검토"),
				stage(6, "경로 게이트", summary.routeGateBlockers(),
					url("/admin/datapack/route-gates/page", null, null, "BLOCKER"),
					"경로 게이트"),
				stage(7, "수동 오버라이드", summary.manualOverrideBlockers(),
					url("/admin/datapack/manual-overrides/page", null, null, "BLOCKER"),
					"수동 오버라이드"),
				stage(8, "매니페스트 서명", summary.manifestBlockers(), candidateDrill, "후보 상세"),
				stage(9, "채널 승격", 0,
					url("/admin/datapack/release-channels/page", candidateId, null, null),
					"배포 채널"));
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
				candidateContext.sourceSnapshotIds(),
				valueOrDash(candidateContext.firstSourceSnapshotId()),
				summary.totalBlockers(),
				summary.productionPromoteAllowed(),
				summary.productionPromoteReason(),
				stages,
				summary.readinessRows().stream().map(GateNode::from).toList());
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

		private static String url(String path, String candidateId, String sourceSnapshotId, String status) {
			UriComponentsBuilder builder = UriComponentsBuilder.fromPath(path);
			if (candidateId != null && !candidateId.isBlank()) {
				builder.queryParam("candidateId", candidateId);
			}
			if (sourceSnapshotId != null && !sourceSnapshotId.isBlank()) {
				builder.queryParam("sourceSnapshotId", sourceSnapshotId);
			}
			if (status != null && !status.isBlank()) {
				builder.queryParam("status", status);
			}
			return builder.build().encode().toUriString();
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

	record GateNode(String label, String status, String tone, long blockerCount, String note) {

		static GateNode from(ReleaseReadinessRow row) {
			return new GateNode(row.label(), row.status(), tone(row.status()), row.blockerCount(), row.note());
		}

		private static String tone(String status) {
			return "PASS".equals(status) ? "good" : "bad";
		}
	}

	record CandidateContext(String sourceSnapshotIds, String firstSourceSnapshotId) {

		static CandidateContext from(String candidateId, JdbcDatapackCandidateRepository candidateRepository) {
			if (candidateId == null || candidateId.isBlank() || "-".equals(candidateId)) {
				return new CandidateContext("-", null);
			}
			return candidateRepository.findInput(candidateId)
				.map(input -> new CandidateContext(
					valueOrDash(input.sourceSnapshotIds()),
					firstCsv(input.sourceSnapshotIds())
				))
				.orElse(new CandidateContext("-", null));
		}

		String sourceSnapshotFilter() {
			return "-".equals(sourceSnapshotIds) ? null : sourceSnapshotIds;
		}
	}

	private static String firstCsv(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		for (String item : value.split(",")) {
			if (!item.isBlank()) {
				return item.trim();
			}
		}
		return null;
	}

	private static String valueOrDash(String value) {
		return value == null || value.isBlank() ? "-" : value;
	}
}
