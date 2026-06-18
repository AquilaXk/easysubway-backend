package com.easysubway.transit.adapter.in.web;

import com.easysubway.transit.application.port.in.TransitMasterAdminUseCase;
import com.easysubway.transit.application.port.in.TransitMasterQueryUseCase;
import com.easysubway.transit.application.port.in.UpdateRouteNodeDisplayCommand;
import com.easysubway.transit.application.port.in.UpdateSimplifiedStationLayoutStatusCommand;
import com.easysubway.transit.domain.RouteEdge;
import com.easysubway.transit.domain.RouteNode;
import com.easysubway.transit.domain.RouteNodeNotFoundException;
import com.easysubway.transit.domain.SimplifiedStationLayout;
import com.easysubway.transit.domain.SimplifiedStationLayoutNotFoundException;
import com.easysubway.transit.domain.SimplifiedStationLayoutStatus;
import com.easysubway.transit.domain.StationLayoutSource;
import com.easysubway.transit.domain.StationLineSummary;
import com.easysubway.transit.domain.StationWithLines;
import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
class TransitStationLayoutAdminPageController {

	private final TransitMasterAdminUseCase transitMasterAdminUseCase;
	private final TransitMasterQueryUseCase transitMasterQueryUseCase;

	TransitStationLayoutAdminPageController(
		TransitMasterAdminUseCase transitMasterAdminUseCase,
		TransitMasterQueryUseCase transitMasterQueryUseCase
	) {
		this.transitMasterAdminUseCase = transitMasterAdminUseCase;
		this.transitMasterQueryUseCase = transitMasterQueryUseCase;
	}

	@GetMapping("/admin/stations/{stationId}/layouts/page")
	String stationLayoutsPage(@PathVariable String stationId, Model model) {
		StationWithLines station = transitMasterQueryUseCase.getStation(stationId);
		model.addAttribute("station", StationLayoutPageStation.from(station));
		model.addAttribute("layoutSources", layoutSourceRows(stationId));
		model.addAttribute("layouts", layoutRows(stationId));
		model.addAttribute("layoutStatusOptions", layoutStatusOptions());
		model.addAttribute("routeNodes", routeNodeRows(stationId));
		model.addAttribute("routeEdges", routeEdgeRows(stationId));
		return "admin/stations/layouts";
	}

	@PostMapping("/admin/stations/{stationId}/layouts/{layoutId}/page/status")
	String updateLayoutStatusFromPage(
		@PathVariable String stationId,
		@PathVariable String layoutId,
		@RequestParam SimplifiedStationLayoutStatus status,
		Principal principal
	) {
		requireLayoutInStation(stationId, layoutId);
		transitMasterAdminUseCase.updateSimplifiedStationLayoutStatus(new UpdateSimplifiedStationLayoutStatusCommand(
			layoutId,
			status,
			principal.getName()
		));
		return "redirect:/admin/stations/%s/layouts/page".formatted(stationId);
	}

	@PostMapping("/admin/stations/{stationId}/route-nodes/{nodeId}/page")
	String updateRouteNodeDisplayFromPage(
		@PathVariable String stationId,
		@PathVariable String nodeId,
		@RequestParam int displayX,
		@RequestParam int displayY,
		@RequestParam String displayLabel,
		@RequestParam(required = false) String accessibilityNote,
		Principal principal
	) {
		requireRouteNodeInStation(stationId, nodeId);
		transitMasterAdminUseCase.updateRouteNodeDisplay(new UpdateRouteNodeDisplayCommand(
			stationId,
			nodeId,
			displayX,
			displayY,
			displayLabel,
			accessibilityNote,
			principal.getName()
		));
		return "redirect:/admin/stations/%s/layouts/page".formatted(stationId);
	}

	private void requireLayoutInStation(String stationId, String layoutId) {
		transitMasterQueryUseCase.getStation(stationId);
		boolean matched = transitMasterQueryUseCase.listSimplifiedStationLayouts(stationId)
			.stream()
			.anyMatch(layout -> layout.id().equals(layoutId));
		if (!matched) {
			throw new SimplifiedStationLayoutNotFoundException();
		}
	}

	private void requireRouteNodeInStation(String stationId, String nodeId) {
		transitMasterQueryUseCase.getStation(stationId);
		boolean matched = transitMasterQueryUseCase.listRouteNodes(stationId)
			.stream()
			.anyMatch(node -> node.id().equals(nodeId));
		if (!matched) {
			throw new RouteNodeNotFoundException();
		}
	}

	private List<StationLayoutSourceRow> layoutSourceRows(String stationId) {
		return transitMasterQueryUseCase.listStationLayoutSources(stationId)
			.stream()
			.map(StationLayoutSourceRow::from)
			.toList();
	}

	private List<SimplifiedStationLayoutRow> layoutRows(String stationId) {
		return transitMasterQueryUseCase.listSimplifiedStationLayouts(stationId)
			.stream()
			.map(SimplifiedStationLayoutRow::from)
			.toList();
	}

	private List<RouteNodeRow> routeNodeRows(String stationId) {
		return transitMasterQueryUseCase.listRouteNodes(stationId)
			.stream()
			.map(RouteNodeRow::from)
			.toList();
	}

	private List<RouteEdgeRow> routeEdgeRows(String stationId) {
		return transitMasterQueryUseCase.listRouteEdges(stationId)
			.stream()
			.map(RouteEdgeRow::from)
			.toList();
	}

	private static List<LayoutStatusOption> layoutStatusOptions() {
		return Arrays.stream(SimplifiedStationLayoutStatus.values())
			.map(status -> new LayoutStatusOption(status, status.name()))
			.toList();
	}

	record StationLayoutPageStation(String stationId, String stationName, String lineNames) {

		static StationLayoutPageStation from(StationWithLines stationWithLines) {
			return new StationLayoutPageStation(
				stationWithLines.station().id(),
				stationWithLines.station().nameKo(),
				stationWithLines.lines()
					.stream()
					.map(StationLineSummary::name)
					.collect(Collectors.joining(", "))
			);
		}
	}

	record StationLayoutSourceRow(
		String sourceName,
		String sourceType,
		String license,
		String commercialUseLabel,
		String attributionLabel,
		String capturedAt,
		String reviewedAt
	) {

		static StationLayoutSourceRow from(StationLayoutSource source) {
			return new StationLayoutSourceRow(
				source.sourceName(),
				source.sourceType().name(),
				source.license(),
				source.commercialUseAllowed() ? "상업적 사용 가능" : "상업적 사용 불가",
				source.attributionRequired() ? "출처 표시 필요" : "출처 표시 불필요",
				source.capturedAt().toString(),
				source.reviewedAt() == null ? "검수 전" : source.reviewedAt().toString()
			);
		}
	}

	record SimplifiedStationLayoutRow(
		String id,
		int version,
		SimplifiedStationLayoutStatus statusValue,
		String status,
		String confidenceLevel,
		String baseFloor,
		String sourceIds,
		String lastVerifiedAt
	) {

		static SimplifiedStationLayoutRow from(SimplifiedStationLayout layout) {
			return new SimplifiedStationLayoutRow(
				layout.id(),
				layout.version(),
				layout.status(),
				layout.status().name(),
				layout.confidenceLevel().name(),
				layout.baseFloor(),
				String.join(", ", layout.sourceIds()),
				layout.lastVerifiedAt().toString()
			);
		}
	}

	record LayoutStatusOption(SimplifiedStationLayoutStatus value, String label) {
	}

	record RouteNodeRow(
		String id,
		String type,
		String name,
		String floor,
		String layoutId,
		int displayX,
		int displayY,
		String displayLabel,
		String positionLabel,
		String rawAccessibilityNote,
		String accessibilityNote
	) {

		static RouteNodeRow from(RouteNode node) {
			return new RouteNodeRow(
				node.id(),
				node.type().name(),
				node.name(),
				node.floor(),
				node.layoutId(),
				node.displayX(),
				node.displayY(),
				node.displayLabel(),
				"x %d, y %d".formatted(node.displayX(), node.displayY()),
				node.accessibilityNote(),
				node.accessibilityNote() == null ? "접근성 메모 없음" : node.accessibilityNote()
			);
		}
	}

	record RouteEdgeRow(
		String id,
		String fromNodeId,
		String toNodeId,
		String type,
		String distanceLabel,
		String estimatedSecondsLabel,
		String stairsLabel,
		String elevatorLabel,
		String escalatorLabel,
		String reliabilityLabel
	) {

		static RouteEdgeRow from(RouteEdge edge) {
			return new RouteEdgeRow(
				edge.id(),
				edge.fromNodeId(),
				edge.toNodeId(),
				edge.type().name(),
				edge.distanceMeters() + "m",
				edge.estimatedSeconds() + "초",
				edge.hasStairs() ? "계단 포함" : "계단 없음",
				edge.requiresElevator() ? "엘리베이터 필요" : "엘리베이터 불필요",
				edge.requiresEscalator() ? "에스컬레이터 필요" : "에스컬레이터 불필요",
				"신뢰도 " + edge.reliabilityScore()
			);
		}
	}
}
