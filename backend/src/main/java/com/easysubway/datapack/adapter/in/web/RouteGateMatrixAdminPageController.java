package com.easysubway.datapack.adapter.in.web;

import com.easysubway.datapack.adapter.out.persistence.JdbcRouteGateMatrixRepository;
import com.easysubway.datapack.adapter.out.persistence.JdbcRouteGateMatrixRepository.RouteGateRow;
import java.time.LocalDateTime;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class RouteGateMatrixAdminPageController {

	private static final int MATRIX_LIMIT = 200;

	private final JdbcRouteGateMatrixRepository matrixRepository;

	RouteGateMatrixAdminPageController(JdbcRouteGateMatrixRepository matrixRepository) {
		this.matrixRepository = matrixRepository;
	}

	@GetMapping("/admin/datapack/route-gates/page")
	@PreAuthorize("hasAuthority('admin.datapack.read')")
	String routeGateMatrix(Model model) {
		model.addAttribute("routeGateRows", matrixRepository.listRecentEdges(MATRIX_LIMIT).stream()
			.map(RouteGateView::from)
			.toList());
		return "admin/datapack/route-gates/list";
	}

	record RouteGateView(
		String id,
		String stationId,
		String lineId,
		String edgeId,
		String edgeType,
		String sourceId,
		String sourceSnapshotId,
		String provenanceKind,
		String verificationStatus,
		LocalDateTime lastVerifiedAt,
		String evidenceHash,
		boolean generatedConnector,
		boolean strictRouteEligible,
		String strictRouteLabel,
		String blockerReason
	) {

		static RouteGateView from(RouteGateRow row) {
			boolean generated = isGenerated(row);
			return new RouteGateView(
				row.id(),
				row.stationId(),
				valueOrDash(row.lineId()),
				row.edgeId(),
				row.edgeType(),
				row.sourceId(),
				row.sourceSnapshotId(),
				row.provenanceKind(),
				row.verificationStatus(),
				row.lastVerifiedAt(),
				row.evidenceHash(),
				generated,
				row.strictRouteEligible(),
				row.strictRouteEligible() ? "strict 가능" : "strict 불가",
				RouteGateMatrixAdminPageController.blockerReason(row, generated)
			);
		}
	}

	private static boolean isGenerated(RouteGateRow row) {
		return "GENERATED_CONNECTOR".equals(row.edgeType())
			|| "GENERATED".equals(row.provenanceKind())
			|| "GENERATED".equals(row.verificationStatus());
	}

	private static String blockerReason(RouteGateRow row, boolean generated) {
		if (row.strictRouteEligible()) {
			return "-";
		}
		if (row.blockerReason() != null && !row.blockerReason().isBlank()) {
			return row.blockerReason();
		}
		if (generated) {
			return "generated connector";
		}
		return switch (row.verificationStatus()) {
			case "UNKNOWN" -> "unknown edge";
			case "STALE" -> "stale source";
			case "MISSING" -> "missing edge";
			default -> "strict route blocker";
		};
	}

	private static String valueOrDash(String value) {
		if (value == null || value.isBlank()) {
			return "-";
		}
		return value;
	}
}
